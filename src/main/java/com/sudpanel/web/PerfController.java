package com.sudpanel.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudpanel.perf.PerfReportService;
import com.sudpanel.perf.PerfRepository;
import com.sudpanel.perf.PerfRunService;

/**
 * M6 性能测试 REST。
 *
 * 【关键设计点】
 * 1. 独立于功能测试：路径统一挂在 /api/perf 下，由 PerfRunService / PerfRepository /
 *    PerfReportService 三个服务支撑，与 ApiController 的功能测试互不干扰。
 * 2. 能力分组（代码用分隔注释按区块组织）：
 *    - 游戏列表：扫描游戏大厅（滚动 dump 控件树）、增删、排序、标定 mock 按钮坐标；
 *    - 执行：启动/停止/断点续跑/删除性能测试 run、实时进度；
 *    - 数据：某 run 某游戏的圈列表、逐秒采样点、聚合判定结果、跨 run 趋势数据；
 *    - 报告：per-game 自包含 HTML 报告（内嵌 SVG 折线图）与 CSV 导出；
 *    - 人工复核：对自动判定结果（checks/loops）追加人工结论与备注。
 * 3. 删除保护：RUNNING 状态的 run 拒绝删除（必须先停止），避免数据不一致。
 * 4. 查询兜底：记录不存在时抛 IllegalStateException 并带清晰中文错误，不静默返回空。
 */
@RestController
@RequestMapping("/api/perf")
public class PerfController {

    // 性能测试执行引擎：扫描/标定/启停/续跑/进度
    private final PerfRunService perf;
    // 性能数据仓库：游戏/圈/采样/判定记录的 CRUD 与聚合查询
    private final PerfRepository repo;
    // 性能报告服务：生成 HTML 报告与 CSV 导出
    private final PerfReportService perfReport;
    // 全局配置：判定阈值（CPU 阈值等）要透给前端画参考线，否则图与结论会对不上
    private final com.sudpanel.config.AppProperties props;

    /**
     * 构造函数：Spring 自动注入三个性能相关服务与全局配置。
     * @param perf       性能执行引擎
     * @param repo       性能数据仓库
     * @param perfReport 性能报告服务
     * @param props      全局配置（阈值来源）
     */
    public PerfController(PerfRunService perf, PerfRepository repo, PerfReportService perfReport,
                          com.sudpanel.config.AppProperties props) {
        this.perf = perf;
        this.repo = repo;
        this.perfReport = perfReport;
        this.props = props;
    }

    // ---------- 游戏列表 ----------

    /**
     * 扫描/标定请求体。
     * @param deviceSerial 目标设备序列号（adb 操作对象）
     */
    public record ScanRequest(String deviceSerial) {
    }

    /**
     * 滚动扫描 app 内游戏列表，upsert 后返回全量。
     * 流程：反复 dump 屏幕控件树 → 滚动列表 → 识别游戏项 → 已存在则更新、不存在则插入（upsert）。
     * @param req 含 deviceSerial 的请求体
     * @return 扫描并入库后的全部游戏列表
     */
    @PostMapping("/games/scan")
    public List<Map<String, Object>> scanGames(@RequestBody ScanRequest req) {
        return perf.scanGames(req.deviceSerial());
    }

    /**
     * 查询全部游戏（性能测试目标列表，游戏选择页数据源）。
     * @return 游戏列表（id/名称/排序等）
     */
    @GetMapping("/games")
    public List<Map<String, Object>> listGames() {
        return repo.listGames();
    }

    /**
     * 删除游戏及其全部圈/采样/判定记录。
     * 级联删除由仓库层在事务内完成，防止孤儿数据残留。
     * @param id 游戏 id
     */
    @DeleteMapping("/games/{id}")
    public void deleteGame(@PathVariable long id) {
        repo.deleteGame(id);
    }

    /**
     * 上移/下移游戏。body: {"dir": "up"|"down"}
     * @param dir 方向："up" 上移一位；其余任意值（如 "down"）下移一位
     */
    public record MoveRequest(String dir) {
    }

    /**
     * 调整游戏在列表中的位置（相邻交换）。
     * 实现：仓库层交换该游戏与其上/下邻居的排序值。
     * @param id  游戏 id
     * @param req 含 dir（up/down）的请求体
     */
    @PostMapping("/games/{id}/move")
    public void moveGame(@PathVariable long id, @RequestBody MoveRequest req) {
        // "up"（忽略大小写）→ 上移；其他值一律按下移处理
        repo.moveGame(id, "up".equalsIgnoreCase(req.dir()));
    }

    /**
     * 批量删除游戏（含全部圈/采样/判定记录）。body: {"ids": [...]}
     * @param ids 要删除的游戏 id 列表
     */
    public record BatchDeleteRequest(List<Long> ids) {
    }

    /**
     * 批量删除游戏（前端多选删除按钮调用，一次请求删多个）。
     * 复用单删逻辑，仓库层逐 id 级联删除。
     * @param req 含 ids 列表的请求体
     */
    @DeleteMapping("/games")
    public void deleteGames(@RequestBody BatchDeleteRequest req) {
        repo.deleteGames(req.ids());
    }

    /**
     * 批量重排：ids 按给定顺序排最前，未传入的按原有顺序跟在后面。body: {"ids": [...]}
     * 用途：前端拖拽排序后把新的完整顺序传给后端落库。
     * @param ids 按新顺序排列的游戏 id 列表
     */
    public record ReorderRequest(List<Long> ids) {
    }

    /**
     * 批量重排游戏顺序（拖拽排序保存接口）。
     * 传入的 id 按顺序排最前，未传入的按原相对顺序接在后面——即"部分重排"语义。
     * @param req 含 ids 的请求体
     */
    @PutMapping("/games/order")
    public void reorderGames(@RequestBody ReorderRequest req) {
        repo.reorderGames(req.ids());
    }

    // ---------- 人机对局（2026-10-06，需求 #68；2026-10-07，#69/#70 扩推送配置） ----------

    /**
     * 一个游戏的推送配置（#68 出身；#70 扩成八项：身份三项 + 名单三项 + 时长 + 模板）。
     *
     * <p>mgId / appId / roomId 是「这个游戏在推送接口里叫什么」——room_id 尤其关键：
     * 变量表里写死的全局房号（联调值「3333」）曾让 8 个人机全进错房、开局报
     * 「房间或者玩家不存在」。三者都跟随游戏，空串 = 清掉（用回变量表全局值）。
     *
     * <p>humanCount / aiCount：名单里真人（is_ai=0）与人机（is_ai=1）的条数，跟随游戏。
     * humanCount 没配时按 1 兜底；两者都空时名单走用例原文（首条强制真人）。
     * aiLevel：人机的 ai_level，配了写进每个人机条目。
     * gameSeconds：这个游戏「开局后多久收尾」（= 对局内采样时长），空 = 用 run 启动时配的值。
     */
    public record GameIdsRequest(String mgId, String appId, String roomId,
                                 Integer humanCount, Integer aiCount, Integer aiLevel,
                                 Integer gameSeconds, String machineUid, String bodyOverride) {
    }

    /** 设置某游戏的推送配置（八项一起存，界面上改完即存）。 */
    @PutMapping("/games/{id}/ids")
    public Map<String, Object> updateGameIds(@PathVariable long id, @RequestBody GameIdsRequest req) {
        // 先确认游戏存在：直接 UPDATE 一个不存在的 id 不会报错，前端却会以为存上了
        if (repo.getGame(id) == null) {
            throw new IllegalStateException("游戏不存在: " + id);
        }
        // 空值按运行时回落规则解析；只要显式配了数量，总人数就必须可执行。
        if (req.humanCount() != null || req.aiCount() != null) {
            int humans = req.humanCount() != null ? req.humanCount() : 1;
            int ais = req.aiCount() != null ? req.aiCount() : savedAiCountOrZero();
            com.sudpanel.perf.AiPlayers.requireValidCounts(humans, ais);
        }
        if (req.aiLevel() != null && (req.aiLevel() < 0 || req.aiLevel() > 9999)) {
            throw new IllegalStateException("人机等级（ai_level）要填 0～9999 的整数，或留空沿用用例里的值");
        }
        // 时长校验（#70）：下限 15 秒（再短采不到有效数据），上限 2 小时（防手滑）。
        // 超过 4 分钟也不拦 —— watchdog 截止会跟着游戏时长延长，长局不会被误判超时
        if (req.gameSeconds() != null && (req.gameSeconds() < 15 || req.gameSeconds() > 7200)) {
            throw new IllegalStateException("对局时长要填 15～7200 秒，或留空用启动时配的值");
        }
        // 模板语法启发式校验：占位符临时替换成 0 后应能按 JSON 解析。
        // 拦的是「语法坏掉」（少个括号、多个逗号）这种保存时就看得见的问题；
        // 模板本身的语义（占位符写什么、字段对不对）不判断 —— 那只有运行时才知道
        if (req.bodyOverride() != null && !req.bodyOverride().isBlank()) {
            String probe = req.bodyOverride().replaceAll("\\{\\{[^}]*}}", "0");
            try {
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(probe);
            } catch (Exception e) {
                throw new IllegalStateException("推送体模板不是合法 JSON（占位符按 0 替换后仍解析失败）："
                        + e.getMessage());
            }
        }
        com.sudpanel.perf.AiPlayers.requireValidBodyCount(req.bodyOverride());
        repo.updateGameIds(id, req.mgId(), req.appId(), req.roomId(),
                req.humanCount(), req.aiCount(), req.aiLevel(), req.gameSeconds(),
                req.machineUid(), req.bodyOverride());
        return Map.of("ok", true);
    }

    /**
     * 人机对局模式的配置读写（需求 #68）。
     *
     * <p>四条用例 id 都是「用例的主键」，页面上按名字选；人机数量留空表示用用例原文的名单。
     * 全部允许为空 —— 空表示「按名字自动找」，启动时会校验并给出缺哪一条的中文提示。
     */
    public record AiRoomConfigRequest(String caseBatch, String caseStart, String caseEnd,
                                      String caseClear, String aiCount, String endKeyword) {
    }

    /** 读人机对局配置（页面加载时用）。 */
    @GetMapping("/ai-room")
    public Map<String, Object> getAiRoomConfig() {
        Map<String, String> saved = repo.getSettings(PerfRepository.aiRoomSettingKeys());
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("caseBatch", saved.get(PerfRepository.SETTING_AI_CASE_BATCH));
        out.put("caseStart", saved.get(PerfRepository.SETTING_AI_CASE_START));
        out.put("caseEnd", saved.get(PerfRepository.SETTING_AI_CASE_END));
        out.put("caseClear", saved.get(PerfRepository.SETTING_AI_CASE_CLEAR));
        out.put("aiCount", saved.get(PerfRepository.SETTING_AI_COUNT));
        // 对局提前结束关键字（v1.9.4 #78）：留空 = 不做提前结束检测
        out.put("endKeyword", saved.get(PerfRepository.SETTING_AI_END_KEYWORD));
        out.put("defaults", Map.of("batch", "批量加入", "start", "游戏开始",
                "end", "游戏结束", "clear", "房间清理"));
        return out;
    }

    private int savedAiCountOrZero() {
        String value = repo.getSetting(PerfRepository.SETTING_AI_COUNT);
        if (value == null || value.isBlank()) return 0;
        try { return Integer.parseInt(value.trim()); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("全局人机数量不是整数，请先修正配置"); }
    }

    /** 写人机对局配置（页面上改完即存）。 */
    @PutMapping("/ai-room")
    public Map<String, Object> saveAiRoomConfig(@RequestBody AiRoomConfigRequest req) {
        // 人机数量在这里就拦一次非法值：留空=用用例原文；填了必须是正整数。
        // 放到启动时才校验的话，用户会以为「页面上填了却没生效」
        if (req.aiCount() != null && !req.aiCount().isBlank()) {
            int n;
            try {
                n = Integer.parseInt(req.aiCount().trim());
            } catch (NumberFormatException e) {
                throw new IllegalStateException("人机数量要填一个整数，或留空用用例里的名单");
            }
            if (n <= 0) throw new IllegalStateException("全局人机数量必须大于 0；单个游戏可另配 0 人机");
            com.sudpanel.perf.AiPlayers.requireValidCounts(1, n);
        }
        repo.putSetting(PerfRepository.SETTING_AI_CASE_BATCH, req.caseBatch());
        repo.putSetting(PerfRepository.SETTING_AI_CASE_START, req.caseStart());
        repo.putSetting(PerfRepository.SETTING_AI_CASE_END, req.caseEnd());
        repo.putSetting(PerfRepository.SETTING_AI_CASE_CLEAR, req.caseClear());
        repo.putSetting(PerfRepository.SETTING_AI_COUNT, req.aiCount());
        // 对局提前结束关键字（v1.9.4 #78）：前后空格一律去掉 —— 那一列是给
        // logcat 做 contains 比对的，多一个空格就永远命中不了，而且现象是「静默不生效」
        repo.putSetting(PerfRepository.SETTING_AI_END_KEYWORD,
                req.endKeyword() == null ? null : req.endKeyword().trim());
        return Map.of("ok", true);
    }

    /**
     * 性能测试的通用开关（2026-10-10，v1.10.0 #84）。
     *
     * <p>与 <code>/ai-room</code> 分开的原因：那些是人机对局专属的四条用例绑定，
     * 而这里放的是**三个模式都适用**的运行选项。混在一起会让「这开关到底管哪些模式」变得含糊。
     */
    public record PerfOptionsRequest(Boolean trimBaseline) {
    }

    /** 读性能测试通用选项。 */
    @GetMapping("/options")
    public Map<String, Object> getOptions() {
        String v = repo.getSetting(PerfRepository.SETTING_PERF_TRIM_BASELINE);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        // 默认勾选（2026-10-10 使用者拍板改为默认开）：只有显式存过 "false" 才算关闭。
        // 这样「没配过」与「配过又打开」都是开，只有用户主动取消勾选才会关掉。
        out.put("trimBaseline", !"false".equalsIgnoreCase(v == null ? "" : v.trim()));
        // CPU 阈值也一并给出（v1.10.1）：面板要在 CPU 趋势图上画阈值参考线，
        // 而阈值在配置文件里 —— 前端拿不到就会自己写一个数，图与判定迟早对不上。
        out.put("cpuThreshold", props.perfCpuThreshold());
        return out;
    }

    /** 写性能测试通用选项（页面上改完即存）。 */
    @PutMapping("/options")
    public Map<String, Object> saveOptions(@RequestBody PerfOptionsRequest req) {
        if (req.trimBaseline() != null) {
            repo.putSetting(PerfRepository.SETTING_PERF_TRIM_BASELINE,
                    req.trimBaseline() ? "true" : "false");
        }
        return Map.of("ok", true);
    }

    // ---------- 按钮坐标分组（v1.2 #4） ----------

    /** 新建分组的请求体：name 为空时后端给「分组N」兜底；deviceSerial 可空（通用分组）。 */
    public record ButtonGroupRequest(String name, String deviceSerial) {
    }

    /** 逐按钮标定的请求体：key 为空表示「扫全屏标定全部」（兼容旧的整屏标定行为）。 */
    public record CalibrateButtonRequest(String deviceSerial, String key) {
    }

    /**
     * 分组列表，每条含「已标定 N / 共 M」与逐按钮明细（**中文名**，前端直接展示）。
     */
    @GetMapping("/button-groups")
    public List<Map<String, Object>> listButtonGroups() {
        return perf.listButtonGroups();
    }

    /**
     * 新建分组。按需求，新分组随后**必须完成标定**才能用于启动压测（启动前由 ensureGroupReady 校验）。
     */
    @PostMapping("/button-groups")
    public Map<String, Object> createButtonGroup(@RequestBody ButtonGroupRequest req) {
        return perf.createButtonGroup(req.name(), req.deviceSerial());
    }

    /** 删除分组及其坐标。 */
    @DeleteMapping("/button-groups/{id}")
    public void deleteButtonGroup(@PathVariable long id) {
        perf.deleteButtonGroup(id);
    }

    /**
     * 标定分组内的按钮：传 key 只标该按钮；不传则扫全屏尝试标定全部。
     * 返回里带「中文名 + 状态 + 最新进度」，前端据此把对应行改成「已标定 ✅」。
     */
    @PostMapping("/button-groups/{id}/calibrate")
    public Map<String, Object> calibrateButton(@PathVariable long id,
                                               @RequestBody CalibrateButtonRequest req) {
        return perf.calibrateGroup(req.deviceSerial(), id, req.key());
    }

    // ---------- 执行 ----------

    /**
     * 性能测试启动请求体。
     * @param deviceSerial    目标设备序列号
     * @param mode            执行模式（语义由 PerfRunService 定义，如固定时长采样）
     * @param gameIds         参与测试的游戏 id 列表
     * @param loopCount       循环（圈）次数
     * @param sampleSeconds   每圈采样时长（秒）
     * @param earlyTapDelayMs 提前点击延迟（毫秒）：采样前预点击的提前量，可空
     */
    public record StartRequest(String deviceSerial, String mode, List<Long> gameIds,
                               Integer loopCount, Integer sampleSeconds, Long earlyTapDelayMs,
                               Long buttonGroupId) {
    }

    /**
     * 启动一次性能测试 run（后台执行，接口立即返回）。
     * @param req 启动参数（设备/模式/游戏/圈数/采样时长等）
     * @return 新建 run 的信息（含 run id）
     */
    @PostMapping("/runs")
    public Map<String, Object> start(@RequestBody StartRequest req) {
        return perf.start(req.deviceSerial(), req.mode(), req.gameIds(),
                req.loopCount(), req.sampleSeconds(), req.earlyTapDelayMs(), req.buttonGroupId());
    }

    /**
     * 停止进行中的性能测试 run。
     * @param id run id
     */
    @PostMapping("/runs/{id}/stop")
    public void stop(@PathVariable long id) {
        perf.stop(id);
    }

    /**
     * 断点续跑：INTERRUPTED/STOPPED/ERROR/ABORTED 的 run 从已完成圈数继续。
     * 适用场景：设备断连/手动停止/异常中断后，不想重跑已完成的圈，
     * 直接从下一个未完成的游戏/圈继续采样。
     * @param id run id
     * @return 续跑后的 run 信息
     */
    @PostMapping("/runs/{id}/resume")
    public Map<String, Object> resume(@PathVariable long id, @RequestParam(defaultValue="FROZEN") String strategy) {
        return perf.resume(id,strategy);
    }

    /** 手动重算某 run 的聚合判定（历史 ABORTED/STOPPED run 也能出结论，2026-09-18）。 */
    @PostMapping("/runs/{id}/rejudge")
    public Map<String, Object> rejudge(@PathVariable long id) {
        return perf.rejudge(id);
    }

    /** 删除运行及其数据；执行服务统一保护活动态和收尾中的执行权。 */
    @DeleteMapping("/runs/{id}")
    public void deleteRun(@PathVariable long id) {
        perf.deleteRun(id);
    }

    /**
     * 进行中 run 的实时进度（含当前游戏/圈/最近采样）。
     * 前端进度页高频轮询此接口刷新显示。
     * @param id run id
     * @return 进度 Map（当前游戏名/当前圈/最新采样数据等）
     */
    @GetMapping("/runs/{id}/progress")
    public Map<String, Object> progress(@PathVariable long id) {
        return perf.progress(id);
    }

    /**
     * 性能测试 run 历史列表（最新在前）。
     * @return run 列表（id/状态/设备/圈数/时间等）
     */
    @GetMapping("/runs")
    public List<Map<String, Object>> listRuns() {
        return repo.listRuns();
    }

    @GetMapping("/runs/page")
    public PerfRepository.HistoryPage history(@RequestParam(required=false) Long gameId,@RequestParam(required=false) String mode,
            @RequestParam(required=false) String status,@RequestParam(required=false) String from,@RequestParam(required=false) String to,
            @RequestParam(required=false) String cleanupStatus,@RequestParam(required=false) String cursor,@RequestParam(defaultValue="30") int limit) {
        return repo.history(new PerfRepository.HistoryFilter(gameId,mode,status,from,to,cleanupStatus),cursor,limit);
    }
    @GetMapping("/runs/active") public List<Map<String,Object>> activeRuns() { return repo.activeRuns(); }

    /**
     * 查询单个 run 的详情。
     * @param id run id
     * @return run 详情 Map
     * @throws IllegalStateException run 不存在时
     */
    @GetMapping("/runs/{id}")
    public Map<String, Object> getRun(@PathVariable long id) {
        Map<String, Object> run = repo.getRun(id);
        if (run == null) {
            throw new IllegalStateException("运行不存在: " + id);
        }
        return run;
    }

    @GetMapping("/runs/{id}/plan")
    public Map<String,Object> plan(@PathVariable long id) {
        if(repo.getRun(id)==null) throw new IllegalStateException("运行不存在");
        return repo.planSummary(id);
    }

    // ---------- 数据 ----------

    /**
     * 某 run 下某游戏的全部圈。
     * 一圈 = 一次完整的"点击→采样"周期；列表按圈序号排列，用于结果详情页。
     * @param runId  run id
     * @param gameId 游戏 id
     * @return 圈列表（每圈含序号/起止时间/判定等）
     */
    @GetMapping("/runs/{runId}/games/{gameId}/loops")
    public List<Map<String, Object>> loops(@PathVariable long runId, @PathVariable long gameId,@RequestParam(required=false) String cleanupStatus) {
        return repo.loopsOf(runId, gameId,cleanupStatus);
    }

    /**
     * 一圈的逐秒采样点。
     * 每个采样点是一个时间戳对应的性能数据快照，用于绘制单圈曲线。
     * @param loopId 圈 id
     * @return 采样点列表（时间/帧率/内存等指标）
     */
    @GetMapping("/loops/{loopId}/samples")
    public List<Map<String, Object>> samples(@PathVariable long loopId) {
        return repo.samplesOf(loopId);
    }

    /**
     * 某 run 下某游戏的聚合判定结果（检测项 1/3/4/5）。
     * 每个判定项对整 run 的采样数据做聚合分析（如均值/抖动/掉帧统计），
     * 结果页"判定结果"区块数据源，可进入人工复核。
     * @param runId  run id
     * @param gameId 游戏 id
     * @return 判定项列表（检测项编号/结论/数值/人工复核字段等）
     */
    @GetMapping("/runs/{runId}/games/{gameId}/checks")
    public List<Map<String, Object>> checks(@PathVariable long runId, @PathVariable long gameId) {
        return repo.checksOf(runId, gameId);
    }

    /**
     * 游戏跨 run 的全部圈（趋势分析页用）。
     * 同一游戏多次性能测试的圈数据横向对比，用于观察性能随版本/时间的趋势。
     * @param gameId 游戏 id
     * @return 跨 run 的圈列表（含所属 run 信息）
     */
    @GetMapping("/games/{gameId}/loops")
    public List<Map<String, Object>> loopsOfGame(@PathVariable long gameId) {
        return repo.loopsOfGame(gameId);
    }

    // ---------- 报告 ----------

    /**
     * per-game 自包含 HTML 报告（内嵌 CSS/SVG，单文件无外部依赖，可直接发给他人或归档）。
     *
     * <p>{@code ?download=1} 时按附件下载，文件名走 {@link PerfReportService#fileName}
     * 的安全字符（去掉 #、空格、括号 —— 这些字符会让收件人的 Safari 打不开本地文件）。
     * 不带参数则在线预览（现有「HTML 报告」链接的行为不变）。
     */
    @GetMapping(value = "/runs/{runId}/games/{gameId}/report", produces = MediaType.TEXT_HTML_VALUE)
    public org.springframework.http.ResponseEntity<String> report(
            @PathVariable long runId, @PathVariable long gameId,
            @RequestParam(value = "download", required = false) Integer download) {
        String html = perfReport.html(runId, gameId);
        if (download == null) {
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML).body(html);
        }
        // 文件名用展示名（短名优先，与报告正文口径一致）
        Map<String, Object> game = repo.getGame(gameId);
        String gameName = game == null ? ("游戏" + gameId)
                : String.valueOf(game.get("shortName") != null ? game.get("shortName") : game.get("name"));
        String fn = PerfReportService.fileName(runId, gameName) + ".html";
        String encoded = java.net.URLEncoder.encode(fn, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        // filename= 必须是纯 ASCII 回退名：不支持 RFC 5987（filename*）的老客户端会直接取它 ——
        // 若那里放百分号编码串，落盘文件名会满是 %，Safari 打开报 -3001（2026-10-08 实锤：
        // curl 与同事的浏览器都复现了此行为）。支持的客户端优先用 filename* 的中文名。
        String asciiFallback = "report-run" + runId + ".html";
        return org.springframework.http.ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Content-Disposition",
                        "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded)
                .body(html);
    }

    /**
     * 报告 PDF 导出（v1.9.0）：服务端调本机 Chrome 无头打印生成，附件下载。
     * 文件名走与 HTML 下载同一套安全规则（ASCII 回退 + UTF-8 中文名双保险）。
     */
    @GetMapping(value = "/runs/{runId}/games/{gameId}/report.pdf",
            produces = MediaType.APPLICATION_PDF_VALUE)
    public org.springframework.http.ResponseEntity<byte[]> reportPdf(
            @PathVariable long runId, @PathVariable long gameId) {
        byte[] pdf = perfReport.pdf(runId, gameId);
        Map<String, Object> game = repo.getGame(gameId);
        String gameName = game == null ? ("游戏" + gameId)
                : String.valueOf(game.get("shortName") != null ? game.get("shortName") : game.get("name"));
        String fn = PerfReportService.fileName(runId, gameName) + ".pdf";
        String encoded = java.net.URLEncoder.encode(fn, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
        String asciiFallback = "report-run" + runId + ".pdf";
        return org.springframework.http.ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition",
                        "attachment; filename=\"" + asciiFallback + "\"; filename*=UTF-8''" + encoded)
                .body(pdf);
    }

    /**
     * 内存增长曲线（2026-10-08，v1.9.1）：各局局内 PSS 曲线抽样叠加（≤8 条，首末必取）。
     * 供面板与报告画「浅→深 = 局序」的对比图 —— 后面的局整体抬高即存在内存增长。
     */
    @GetMapping("/runs/{runId}/games/{gameId}/growth-curves")
    public Map<String, Object> growthCurves(@PathVariable long runId, @PathVariable long gameId) {
        if (repo.getRun(runId) == null) {
            throw new IllegalStateException("运行不存在: " + runId);
        }
        List<Map<String, Object>> ok = repo.loopsOf(runId, gameId).stream()
                .filter(l -> "OK".equals(l.get("status"))).toList();
        // 抽样：不超过 6 条，首末必取、中间均匀（与报告端同一规则；6 条 = 对比够用且图例放得下）
        List<Map<String, Object>> picked;
        if (ok.size() <= 6) {
            picked = ok;
        } else {
            picked = new java.util.ArrayList<>(6);
            for (int i = 0; i < 6; i++) {
                picked.add(ok.get((int) Math.round((double) i * (ok.size() - 1) / 5.0)));
            }
        }
        List<Map<String, Object>> curves = new java.util.ArrayList<>();
        for (Map<String, Object> l : picked) {
            List<Map<String, Object>> points = new java.util.ArrayList<>();
            for (Map<String, Object> smp : repo.samplesOf((Long) l.get("id"))) {
                Object pss = smp.get("pssKb");
                if (pss == null) {
                    continue;
                }
                Map<String, Object> pt = new java.util.LinkedHashMap<>();
                pt.put("t", ((Number) smp.get("seq")).intValue() + 1);
                // MB 保留 1 位小数，减少传输量
                pt.put("pss", Math.round(((Number) pss).doubleValue() / 1024.0 * 10) / 10.0);
                points.add(pt);
            }
            Map<String, Object> c = new java.util.LinkedHashMap<>();
            c.put("loopNo", l.get("loopNo"));
            c.put("points", points);
            curves.add(c);
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("curves", curves);
        out.put("totalOk", ok.size());
        return out;
    }

    /**
     * per-game 圈粒度 CSV 导出。
     * 每圈一行数据（圈序号/耗时/采样统计等），便于 Excel 二次分析。
     * @param runId  run id
     * @param gameId 游戏 id
     * @return CSV 文本（UTF-8，中文不乱码）
     */
    @GetMapping(value = "/runs/{runId}/games/{gameId}/export", produces = "text/csv;charset=UTF-8")
    public String export(@PathVariable long runId, @PathVariable long gameId) {
        return perfReport.csv(runId, gameId);
    }

    // ---------- 人工复核 ----------

    /**
     * 人工复核请求体。
     * @param humanVerdict 人工结论（如 PASS/FAIL/IGNORE，语义由复核页定义）
     * @param humanNote    人工备注（复核依据/说明）
     */
    public record ReviewRequest(String humanVerdict, String humanNote) {
    }

    /**
     * 对某条自动判定结果（check）追加人工复核。
     * 自动判定可能有误报，复核页人工确认后覆盖/补充结论。
     * @param id  判定记录 id
     * @param req 人工结论 + 备注
     */
    @PostMapping("/checks/{id}/review")
    public void reviewCheck(@PathVariable long id, @RequestBody ReviewRequest req) {
        repo.reviewCheck(id, req.humanVerdict(), req.humanNote());
    }

    /**
     * 对某圈（loop）追加人工复核（圈级结论，如整圈是否有效）。
     * @param id  圈 id
     * @param req 人工结论 + 备注
     */
    @PostMapping("/loops/{id}/review")
    public void reviewLoop(@PathVariable long id, @RequestBody ReviewRequest req) {
        repo.reviewLoop(id, req.humanVerdict(), req.humanNote());
    }
}
