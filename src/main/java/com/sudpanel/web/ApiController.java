package com.sudpanel.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sudpanel.adb.AdbClient;
import com.sudpanel.adb.Device;
import com.sudpanel.config.TestConfigRepository;
import com.sudpanel.model.Direction;
import com.sudpanel.model.JudgeMode;
import com.sudpanel.model.ResultStatus;
import com.sudpanel.model.TestConfig;
import com.sudpanel.model.TestPoint;
import com.sudpanel.model.TriggerType;
import com.sudpanel.point.TestPointRepository;
import com.sudpanel.report.ReportService;
import com.sudpanel.runner.RunService;

/**
 * REST 接口层：功能测试（自动上报/人工走查）的前端调用入口，统一挂在 /api 前缀下。
 *
 * 【关键设计点】
 * 1. 纯转发层：所有业务逻辑都在 service 层（RunService/ReportService 等），
 *    本类只做"参数接收 → 调用 service → 返回结果"，保持 Controller 薄而清晰。
 * 2. 请求体用 Java record 承载（StartRequest/JudgeRequest 等），
 *    Spring Boot 3 自动把 JSON 反序列化成 record，天然不可变、无需样板代码。
 * 3. 路径设计：
 *    - /api/points       测试点 CRUD（含 mgtoapp 专用轻量创建/更新接口）；
 *    - /api/configs      测试配置（有序测试点组合）CRUD；
 *    - /api/runs         执行生命周期：创建/列表/汇总/当前点/触发/跳过/判定/停止；
 *    - /api/runs/{id}/report|export  结果页 HTML 报告 / CSV 导出；
 *    - /api/debug/adb    调试入口：向任意设备执行原始 adb 命令。
 * 4. 方向字符串统一用 Direction.valueOf(...toUpperCase()) 解析，容错小写输入。
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    // ADB 客户端：设备枚举、原始命令执行（debug 接口用）
    private final AdbClient adb;
    // 测试点仓库：测试点 CRUD
    private final TestPointRepository points;
    // 执行引擎：启动/查询/操作 run（核心业务）
    private final RunService runs;
    // 报告服务：生成 HTML 报告与 CSV 导出
    private final ReportService report;
    // 测试配置仓库：测试配置（方向+名称+有序测试点 id）CRUD
    private final TestConfigRepository configs;

    /**
     * 构造函数：Spring 自动注入依赖（构造器注入，final 字段保证不可变）。
     * @param adb     ADB 客户端
     * @param points  测试点仓库
     * @param runs    执行引擎（RunService）
     * @param report  报告服务
     * @param configs 测试配置仓库
     */
    public ApiController(AdbClient adb, TestPointRepository points, RunService runs, ReportService report,
                         TestConfigRepository configs) {
        this.adb = adb;
        this.points = points;
        this.runs = runs;
        this.report = report;
        this.configs = configs;
    }

    /**
     * 启动执行的请求体。
     * @param direction     方向字符串（"apptomg"/"mgtoapp"，不区分大小写）
     * @param deviceSerial  目标设备序列号
     * @param pointIds      选中的测试点 id（null/空 = 全量）
     * @param buttonGroupId 复用的性能测试按钮坐标分组 id（v1.6 #26，仅 apptomg 生效；
     *                      null = 不启用，全部走 dump 定位）
     */
    public record StartRequest(String direction, String deviceSerial, List<Long> pointIds, Long buttonGroupId) {
    }

    /**
     * 人工判定请求体。
     * @param result 判定结果字符串（"pass"/"fail" 等，不区分大小写）
     * @param note   判定备注（原因说明）
     */
    public record JudgeRequest(String result, String note) {
    }

    /**
     * mgtoapp 测试点管理表格的轻量输入：组 + state + 备注 + 测试点名称。
     * @param group     所属分组（游戏/模块名）
     * @param state     状态名（也是 resourceKey 与判定关键字的来源）
     * @param note      备注说明
     * @param shortName 测试点名称（表格里展示的短名）
     */
    public record MgPointRequest(String group, String state, String note, String shortName) {
    }

    /**
     * 测试点配置输入：方向 + 名称 + 有序测试点 id。
     * @param direction 配置所属方向（apptomg/mgtoapp）
     * @param name      配置名称
     * @param pointIds  按执行顺序排列的测试点 id 列表
     */
    public record ConfigRequest(String direction, String name, List<Long> pointIds) {
    }

    /**
     * 查询当前连接的 Android 设备列表（首页设备下拉框数据源）。
     * @return 设备列表（序列号/型号/状态等，由 AdbClient 封装）
     */
    @GetMapping("/devices")
    public List<Device> devices() {
        return adb.devices();
    }

    /**
     * 查询测试点列表。不带 direction 返回全部测试点；
     * 带 direction 时按 all 参数决定返回全部还是仅启用（enabled=true）的。
     * @param direction 方向（可选，apptomg/mgtoapp，不区分大小写）
     * @param all       是否包含禁用测试点（默认 false = 只返回启用的）
     * @return 测试点列表
     */
    @GetMapping("/points")
    public List<TestPoint> points(@RequestParam(required = false) String direction,
                                  @RequestParam(required = false, defaultValue = "false") boolean all) {
        // 未指定方向 → 全部测试点（管理页用，含禁用项）
        if (direction == null || direction.isBlank()) {
            return points.findAll();
        }
        // 指定方向 → 统一转大写再解析枚举，小写输入也能容错
        Direction d = Direction.valueOf(direction.toUpperCase());
        // all=true 返回该方向全部（含禁用，管理页编辑用）；false 只返回启用（执行页勾选用）
        return all ? points.findAllByDirection(d) : points.findEnabledByDirection(d);
    }

    /**
     * 新增测试点（完整字段方式，管理页"新增"用）。
     * @param tp 请求体直接反序列化为 TestPoint 实体
     * @return 插入后的测试点（含数据库生成的自增 id 等）
     */
    @PostMapping("/points")
    public TestPoint createPoint(@RequestBody TestPoint tp) {
        return points.insert(tp);
    }

    /**
     * mgtoapp 新增测试点：仅需 group + state + 备注 + 测试点名称，其余字段服务端固定。
     * 适用场景：mgtoapp 走查表新增一行时，用户只关心"组/状态/名称/备注"，
     * 无需关心 resourceKey、触发方式等技术字段。
     * @param req 轻量请求体（group/state/note/shortName）
     * @return 插入后的完整测试点
     */
    @PostMapping("/points/mgtoapp")
    public TestPoint createMgPoint(@RequestBody MgPointRequest req) {
        // 组内下一个序号（决定组内展示/执行顺序）
        int seq = points.nextSeq(req.group());
        // 组装完整测试点：方向固定 MGTOAPP；
        // resourceKey 与 keyword 都用 state（走查时按状态名匹配日志关键字）；
        // triggerType 固定 MANUAL（人工触发）、judgeMode 固定 MANUAL（人工判定）；
        // autoPass=false、enabled=true、tapCount=1（默认值，走查场景不连点）。
        TestPoint tp = new TestPoint(null, Direction.MGTOAPP, req.group(), seq, req.state(), req.state(), req.shortName(),
                null, TriggerType.MANUAL, req.state(), req.note(), JudgeMode.MANUAL, false, true, 1);
        return points.insert(tp);
    }

    /**
     * mgtoapp 更新测试点：state + 备注 + 测试点名称。
     * 走查表的行内编辑只允许改这三个业务字段，其余字段保持不变。
     * @param id  测试点 id（URL 路径参数）
     * @param req 轻量请求体
     */
    @PutMapping("/points/mgtoapp/{id}")
    public void updateMgPoint(@PathVariable Long id, @RequestBody MgPointRequest req) {
        points.updateMg(id, req.state(), req.note(), req.shortName());
    }

    /**
     * 更新测试点（完整字段方式）。
     * 请求体 TestPoint 不带 id（或带旧 id），这里强制用路径里的 id 覆盖，
     * 防止前端传错 id 把别的测试点改掉。
     * @param id 测试点 id（URL 路径参数，以此为准）
     * @param tp 更新后的测试点内容
     */
    @PutMapping("/points/{id}")
    public void updatePoint(@PathVariable Long id, @RequestBody TestPoint tp) {
        points.update(withId(tp, id));
    }

    /**
     * 删除测试点（相关结果记录的清理由仓库层负责）。
     * @param id 测试点 id
     */
    @DeleteMapping("/points/{id}")
    public void deletePoint(@PathVariable Long id) {
        points.delete(id);
    }

    /**
     * 查询某方向下的全部测试配置（配置 = 方向 + 名称 + 有序测试点组合）。
     * @param direction 方向（必填，不区分大小写）
     * @return 测试配置列表
     */
    @GetMapping("/configs")
    public List<TestConfig> configs(@RequestParam String direction) {
        return configs.findAllByDirection(Direction.valueOf(direction.toUpperCase()));
    }

    /**
     * 新建测试配置。
     * @param req 请求体（direction/name/pointIds）
     * @return 插入后的配置（含 id）
     */
    @PostMapping("/configs")
    public TestConfig createConfig(@RequestBody ConfigRequest req) {
        return configs.insert(Direction.valueOf(req.direction().toUpperCase()), req.name(), req.pointIds());
    }

    /**
     * 更新测试配置（改名或调整测试点顺序/成员）。
     * @param id  配置 id
     * @param req 新的名称与测试点 id 列表
     */
    @PutMapping("/configs/{id}")
    public void updateConfig(@PathVariable Long id, @RequestBody ConfigRequest req) {
        configs.update(id, req.name(), req.pointIds());
    }

    /**
     * 删除测试配置。
     * @param id 配置 id
     */
    @DeleteMapping("/configs/{id}")
    public void deleteConfig(@PathVariable Long id) {
        configs.delete(id);
    }

    /**
     * apptomg 测试点与「性能测试按钮坐标分组」的坐标来源对应关系。
     *
     * <p>供 apptomg 执行页的「坐标选择」区展示：选中的分组能覆盖哪几个测试点、
     * 哪几个仍走 dump 定位。映射规则（含「结束游戏」别名）由服务端单点维护。
     *
     * @param groupId 选中的按钮坐标分组 id（不传 = 未选分组）
     * @return 每条含 {resourceKey, label, perfKey, perfLabel, matched}
     */
    @GetMapping("/points/apptomg-coord-sources")
    public List<Map<String, Object>> apptomgCoordSources(
            @RequestParam(required = false) Long groupId) {
        return runs.apptomgCoordSources(groupId);
    }

    /**
     * 启动一次执行（apptomg 自动 / mgtoapp 人工走查，由 direction 决定）。
     * @param req 启动请求体
     * @return RunService.start 的返回：{runId, mode, total}
     */
    @PostMapping("/runs")
    public Map<String, Object> start(@RequestBody StartRequest req) {
        return runs.start(Direction.valueOf(req.direction().toUpperCase()), req.deviceSerial(),
                req.pointIds(), req.buttonGroupId());
    }

    /**
     * 历史 run 列表（首页/历史记录页数据源）。
     * @return run 元信息列表（含选中的测试点 id）
     */
    @GetMapping("/runs")
    public List<Map<String, Object>> listRuns() {
        return runs.listRuns();
    }

    /**
     * 某次 run 的汇总（含全部测试点判定结果，结果页用）。
     * @param id run id
     * @return 汇总 Map
     */
    @GetMapping("/runs/{id}")
    public Map<String, Object> summary(@PathVariable long id) {
        return runs.summary(id);
    }

    /**
     * 历史 run 的完整测试结果（DB 直查，app 重启后仍可用）。
     * @param id run id
     * @return { run, stats, results }；run 不存在返回 404
     */
    @GetMapping("/runs/{id}/results")
    public ResponseEntity<?> runResults(@PathVariable long id) {
        try {
            return ResponseEntity.ok(runs.resultsOf(id));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.notFound().build();
        }
    }

    /**
     * mgtoapp 当前测试点视图（走查页每次"下一个/跳过/判定"前轮询刷新）。
     * @param id run id
     * @return 当前点详情 + 进度
     */
    @GetMapping("/runs/{id}/current")
    public Map<String, Object> current(@PathVariable long id) {
        return runs.current(id);
    }

    /**
     * mgtoapp [下一个]：抓日志过滤，autoPass 点自动判定，其余等人工判定。
     * @param id run id
     * @return 判定结果/命中日志（见 RunService.trigger）
     */
    @PostMapping("/runs/{id}/current/trigger")
    public Map<String, Object> trigger(@PathVariable long id) {
        return runs.trigger(id);
    }

    /**
     * mgtoapp [跳过]：当前点记 SKIP 并推进。
     * @param id run id
     * @return {ok: true}
     */
    @PostMapping("/runs/{id}/current/skip")
    public Map<String, Object> skip(@PathVariable long id) {
        runs.skip(id);
        return Map.of("ok", true);
    }

    /**
     * mgtoapp [判定]：人工给出当前点结论并推进。
     * @param id  run id
     * @param req 判定结果 + 备注
     * @return {ok: true}
     */
    @PostMapping("/runs/{id}/current/judge")
    public Map<String, Object> judge(@PathVariable long id, @RequestBody JudgeRequest req) {
        runs.judge(id, ResultStatus.valueOf(req.result().toUpperCase()), req.note());
        return Map.of("ok", true);
    }

    /**
     * 停止一次运行（自动执行立即在下一测试点前停下；人工走查直接置停止）。
     * @param id run id
     * @return {ok: true}
     */
    @PostMapping("/runs/{id}/stop")
    public Map<String, Object> stop(@PathVariable long id) {
        runs.stop(id);
        return Map.of("ok", true);
    }

    /**
     * 生成某次 run 的自包含 HTML 报告（浏览器直接打开即可看，结果页"报告"按钮）。
     * produces=TEXT_HTML 告诉浏览器响应是 HTML 而非 JSON。
     *
     * <p>数据来自 test_run / test_result 的 DB 直查，app 重启后历史 run 依然可出报告
     * （2026-09-23 修复：此前依赖内存态，重启后报「运行不存在」）。
     *
     * @param id run id
     * @return 完整 HTML 文档；run 不存在时返回 404 + 提示页
     */
    @GetMapping(value = "/runs/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> report(@PathVariable long id) {
        try {
            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(report.html(id));
        } catch (EmptyResultDataAccessException e) {
            // run 不存在：给出人能看懂的 404 页面，而不是 Spring 默认的 500 JSON
            return ResponseEntity.status(404).contentType(MediaType.TEXT_HTML)
                    .body("<!DOCTYPE html><html lang='zh'><head><meta charset='utf-8'>"
                            + "<title>404</title></head><body style='font-family:system-ui;margin:24px'>"
                            + "<h1>运行不存在</h1><p>runId=" + id + " 在数据库中查不到记录。</p></body></html>");
        }
    }

    /**
     * 导出某次 run 的 CSV（结果表格数据，Excel/WPS 可直接打开）。
     * 显式指定 UTF-8 编码，避免中文在 Excel 里乱码。数据同样走 DB 直查。
     *
     * @param id run id
     * @return CSV 文本；run 不存在时返回 404
     */
    @GetMapping(value = "/runs/{id}/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> export(@PathVariable long id) {
        try {
            return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                    .body(report.csv(id));
        } catch (EmptyResultDataAccessException e) {
            return ResponseEntity.status(404).contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                    .body("运行不存在,runId=" + id + "\n");
        }
    }

    /**
     * 调试入口：向设备执行原始 adb 命令（前端"ADB 调试"面板用）。
     * 注意：这是有意的调试后门，仅限本地调试环境使用——生产部署应移除或加鉴权，
     * 否则任何人可通过它操作任意连接的设备。
     * @param body 请求体 {command: "adb 命令字符串，如 shell dumpsys activity top"}
     * @return 命令执行结果（退出码/标准输出/错误输出）
     */
    @PostMapping("/debug/adb")
    public AdbClient.ExecResult debug(@RequestBody Map<String, String> body) {
        String command = body.get("command"); // 取出命令字符串
        if (command == null || command.isBlank()) {
            // 空命令直接返回错误结果（退出码 -1），不往下执行
            return new AdbClient.ExecResult(-1, "", "command 为空");
        }
        // 按空白符拆成参数列表（如 "shell dumpsys activity top" → [shell, dumpsys, activity, top]）
        List<String> args = new ArrayList<>(Arrays.asList(command.trim().split("\\s+")));
        return adb.raw(args); // 交给 AdbClient 直接执行（不经过封装方法）
    }

    /**
     * 工具方法：把请求体中的 TestPoint 拷贝一份并强制覆盖 id。
     * record 没有 with 方法（不可变），所以手动 new 一个等价对象。
     * @param tp 请求体 TestPoint
     * @param id 路径里的权威 id
     * @return 带正确 id 的新 TestPoint
     */
    private static TestPoint withId(TestPoint tp, Long id) {
        return new TestPoint(id, tp.direction(), tp.group(), tp.seq(), tp.stateName(), tp.resourceKey(), tp.description(),
                tp.dataSchema(), tp.triggerType(), tp.keyword(), tp.prompt(), tp.judgeMode(), tp.autoPass(), tp.enabled(),
                tp.tapCount());
    }
}
