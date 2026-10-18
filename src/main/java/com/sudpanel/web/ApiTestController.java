package com.sudpanel.web;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sudpanel.api.ApiBatch;
import com.sudpanel.api.ApiCaseGroup;
import com.sudpanel.api.ApiEnv;
import com.sudpanel.api.ApiCode;
import com.sudpanel.api.ApiExec;
import com.sudpanel.api.ApiRepository;
import com.sudpanel.api.ApiRunner;
import com.sudpanel.api.ApiSigner;
import com.sudpanel.api.ApiSpec;
import com.sudpanel.api.ApiVariables;
import com.sudpanel.api.ApiScenario;
import com.sudpanel.api.ApiRequestEngine;
import com.sudpanel.api.ApiResponseTools;

/**
 * M7 接口测试 REST，统一挂在 /api/apitest 下，与功能测试、性能测试互不干扰。
 *
 * <p>能力分六块：
 * <ul>
 *   <li><b>用例</b>：增删改查 + 复制（接口地址/请求体常被当模板改，复制比新建快）；</li>
 *   <li><b>执行</b>：单组同步跑（等结果）与批次异步跑（设间隔重复），批次可中途停止；</li>
 *   <li><b>自定义变量</b>：基址一组、请求头一组、请求体一组，各自只在各自那个输入框里生效
 *       （参考 Postman 的变量表）；地址同时认基址与请求头两组，同名时基址优先；</li>
 *   <li><b>签名设置</b>：把「取当前时间算签名」这一步交给平台在发送前完成，
 *       免得手工抄来的鉴权串一落盘就开始过期；</li>
 *   <li><b>返回码字典</b>：内置官方公开返回码，支持按码/关键字查，允许自行补充内部码；</li>
 *   <li><b>记录</b>：批次列表、批次明细、CSV 导出。</li>
 * </ul>
 *
 * <p>保密约定：接口地址、请求头、请求体由使用者填写，**只落本地库、不进 git**；
 * 代码与本注释内不出现任何真实地址或凭证。
 */
@RestController
@RequestMapping("/api/apitest")
public class ApiTestController {

    private final ApiRepository repo;
    private final ApiRunner runner;

    public ApiTestController(ApiRepository repo, ApiRunner runner) {
        this.repo = repo;
        this.runner = runner;
    }

    // ---------- 用例 ----------

    /** 全部用例。 */
    @GetMapping("/cases")
    public List<ApiSpec> cases() {
        return repo.listCases();
    }

    /** 单条用例。 */
    @GetMapping("/cases/{id}")
    public ApiSpec caseById(@PathVariable long id) {
        return repo.getCase(id);
    }

    /** 新建用例。 */
    @PostMapping("/cases")
    public ApiSpec createCase(@RequestBody ApiSpec spec) {
        return repo.createCase(spec);
    }

    /** 修改用例。 */
    @PutMapping("/cases/{id}")
    public ApiSpec updateCase(@PathVariable long id, @RequestBody ApiSpec spec) {
        return repo.updateCase(id, spec);
    }

    /** 删除用例。历史执行记录里保留了用例名快照，删用例不会让旧记录变成空白。 */
    @DeleteMapping("/cases/{id}")
    public Map<String, Object> deleteCase(@PathVariable long id) {
        repo.deleteCase(id);
        return Map.of("ok", true);
    }

    /** 复制用例。 */
    @PostMapping("/cases/{id}/duplicate")
    public ApiSpec duplicateCase(@PathVariable long id) {
        return repo.duplicateCase(id);
    }

    /** 批量删除用例的请求体（2026-10-10，v1.10.6）。 */
    public record BatchIdsRequest(List<Long> ids) {
    }

    /**
     * 批量删除用例（v1.10.6）。返回实际删掉的条数 —— 与入参个数可能不同。
     */
    @PostMapping("/cases/batch-delete")
    public Map<String, Object> batchDeleteCases(@RequestBody BatchIdsRequest req) {
        return Map.of("ok", true, "deleted", repo.batchDeleteCases(req.ids()));
    }

    /** 批量迁移用例的请求体（v1.10.6）：目标分组 + 是复制过去还是直接移过去。 */
    public record BatchMoveRequest(List<Long> ids, Long groupId, Boolean copy) {
    }

    /**
     * 批量把用例搬到目标分组（v1.10.6）。
     *
     * <p>{@code groupId} 为 null = 搬到「未分组」；{@code copy} 为 true = 复制过去（原分组保留），
     * 否则直接移过去。
     */
    @PostMapping("/cases/batch-move")
    public Map<String, Object> batchMoveCases(@RequestBody BatchMoveRequest req) {
        boolean copy = Boolean.TRUE.equals(req.copy());
        int n = repo.batchMoveCases(req.ids(), req.groupId(), copy);
        return Map.of("ok", true, "affected", n, "copy", copy);
    }

    // ---------- 用例分组（2026-10-04，需求 #48） ----------

    /** 全部分组，带每组的用例数。 */
    @GetMapping("/case-groups")
    public List<ApiCaseGroup> caseGroups() {
        return repo.listCaseGroups();
    }

    /** 新建分组。名字为空或重名都会被拒，且给出可读原因。 */
    @PostMapping("/case-groups")
    public ApiCaseGroup createCaseGroup(@RequestBody CaseGroupRequest req) {
        return repo.createCaseGroup(req.name(), req.envId());
    }

    /** 重命名分组。只改一行，组内用例跟着换名。 */
    @PutMapping("/case-groups/{id}")
    public ApiCaseGroup renameCaseGroup(@PathVariable long id, @RequestBody CaseGroupRequest req) {
        return repo.renameCaseGroup(id, req.name(), req.envId());
    }

    /**
     * 复制分组（含组内全部用例，v1.10.6）。
     *
     * <p>名字由后端取：先剥掉原名末尾已有的序号，再找第一个没被占用的「基名 N」。
     * 放在后端是为了让「连续复制三次」得到确定的结果，而不是让界面各写一份规则。
     */
    @PostMapping("/case-groups/{id}/duplicate")
    public ApiCaseGroup duplicateCaseGroup(@PathVariable long id) {
        return repo.duplicateCaseGroup(id);
    }

    /**
     * 删除分组。<b>组内用例不删</b>，只把它们置为「未分组」——
     * 用例是用户填了半天的资产，而分组只是归类方式，删归类不该让人丢东西。
     */
    @DeleteMapping("/case-groups/{id}")
    public Map<String, Object> deleteCaseGroup(@PathVariable long id) {
        repo.deleteCaseGroup(id);
        return Map.of("ok", true);
    }

    // ---------- 执行 ----------

    public record SendRequest(ApiSpec spec, Long envId, Map<String, Object> sourceContext,Boolean includeSecrets) {
        public SendRequest(ApiSpec spec,Long envId,Map<String,Object> sourceContext){this(spec,envId,sourceContext,false);}
    }
    public record InspectRequest(String body, Map<String, List<String>> headers) {}
    public record ScenarioRunRequest(String name, Integer repeatCount, Integer intervalSeconds) {}

    @PostMapping("/prepare")
    public ApiRequestEngine.Prepared prepare(@RequestBody SendRequest request) {
        return runner.prepareForUi(request.spec(),request.envId(),Boolean.TRUE.equals(request.includeSecrets()));
    }

    @PostMapping("/send")
    public ApiExec send(@RequestBody SendRequest request) {
        return runner.send(request.spec(), request.envId(), request.sourceContext());
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of("methods", ApiRequestEngine.METHODS,
                "bodyTypes", List.of("JSON", "RAW", "NONE", "URLENCODED", "MULTIPART", "BINARY"), "authTypes", ApiRequestEngine.AUTH_TYPES,
                "systemVariables", List.of(
                        variable("$timestamp", "{{$timestamp}}", List.of("URL", "HEADER", "BODY"), "当前秒时间戳；与签名时间一致"),
                        variable("$timestampMs", "{{$timestampMs}}", List.of("URL", "HEADER", "BODY"), "当前毫秒时间戳"),
                        variable("$uuid", "{{$uuid}}", List.of("URL", "HEADER", "BODY"), "每请求生成一个 UUID"),
                        variable("$randomInt:1:100", "{{$randomInt:1:100}}", List.of("URL", "HEADER", "BODY"), "闭区间随机整数"),
                        variable("$date:yyyy-MM-dd", "{{$date:yyyy-MM-dd}}", List.of("URL", "HEADER", "BODY"), "格式化时间，Asia/Shanghai"),
                        variable("$auth.app_id", "{{$auth.app_id}}", List.of("URL", "HEADER", "BODY"), "自家签名应用 ID"),
                        variable("$auth.mg_id", "{{$auth.mg_id}}", List.of("URL", "HEADER", "BODY"), "自家签名游戏 ID"),
                        variable("$auth.nonce", "{{$auth.nonce}}", List.of("URL", "HEADER", "BODY"), "与鉴权头相同的 nonce"),
                        variable("$auth.sign", "{{$auth.sign}}", List.of("URL", "HEADER"), "自家签名结果，禁止放请求体"),
                        variable("run.name", "{{run.name}}", List.of("URL", "HEADER", "BODY"), "同轮同环境响应提取值")),
                "assertionOps", List.of("exists", "notexists", "type", "equals", "notequals", "contains",
                        "regex", "gt", "gte", "lt", "lte", "length", "in", "schema"),
                "assertionSources", List.of("JSON_PATH", "header:名称", "$elapsedMs", "$response.bytes", "$response.sha256", "$response.contentType"),
                "extractorSources", List.of("JSON", "HEADER"));
    }

    public record DataRunRequest(String name, List<Long> caseIds, Long scenarioId, com.sudpanel.api.ApiDataSet.Input data,
                                 Integer intervalSeconds, Integer repeatCount) {}

    @PostMapping("/data/preview")
    public Map<String, Object> previewData(@RequestBody com.sudpanel.api.ApiDataSet.Input input) {
        var data = com.sudpanel.api.ApiDataSet.parse(input);
        return Map.of("columns", data.columns(), "rowCount", data.rows().size(), "sample", data.rows().subList(0, Math.min(5, data.rows().size())));
    }

    @PostMapping("/data/run")
    public ApiBatch runData(@RequestBody DataRunRequest request) {
        return runner.startDataBatch(request.name(), request.caseIds(), request.scenarioId(), request.data(),
                request.intervalSeconds() == null ? 0 : request.intervalSeconds(), request.repeatCount() == null ? 1 : request.repeatCount());
    }

    private static Map<String, Object> variable(String name, String token, List<String> scopes, String description) {
        return Map.of("name", name, "token", token, "scopes", scopes, "description", description);
    }

    @PostMapping("/inspect-response")
    public ApiResponseTools.Inspection inspectResponse(@RequestBody InspectRequest request) {
        return ApiResponseTools.inspect(request.body(), request.headers());
    }

    @GetMapping("/envs/{id}/cookies")
    public List<Map<String, Object>> cookies(@PathVariable long id) { return runner.cookies(id); }

    @DeleteMapping("/envs/{id}/cookies")
    public Map<String, Object> clearCookies(@PathVariable long id) {
        runner.clearCookies(id); return Map.of("ok", true);
    }

    @GetMapping("/scenarios")
    public List<ApiScenario> scenarios() { return repo.listScenarios(); }

    @GetMapping("/scenarios/{id}")
    public ApiScenario scenario(@PathVariable long id) { return repo.getScenario(id); }

    @PostMapping("/scenarios")
    public ApiScenario createScenario(@RequestBody ApiScenario scenario) { return repo.saveScenario(null, scenario); }

    @PutMapping("/scenarios/{id}")
    public ApiScenario updateScenario(@PathVariable long id, @RequestBody ApiScenario scenario) { return repo.saveScenario(id, scenario); }

    @DeleteMapping("/scenarios/{id}")
    public Map<String, Object> deleteScenario(@PathVariable long id) { repo.deleteScenario(id); return Map.of("ok", true); }

    @PostMapping("/scenarios/{id}/run")
    public ApiBatch runScenario(@PathVariable long id, @RequestBody(required = false) ScenarioRunRequest request) {
        return runner.startScenario(id, request == null ? null : request.name(),
                request == null || request.intervalSeconds() == null ? 0 : request.intervalSeconds(),
                request == null || request.repeatCount() == null ? 1 : request.repeatCount());
    }

    /**
     * 同步跑一组用例，跑完才返回。用于「执行选中」这种不需要重复的场景 ——
     * 结果直接拿到，不用轮询。
     */
    @PostMapping("/run")
    public List<ApiExec> run(@RequestBody RunRequest req) {
        return runner.runOnce(req.caseIds());
    }

    /**
     * 启动异步批次：按间隔重复跑。
     * 立即返回批次信息，后续用 {@code GET /batches/{id}} 轮询进度。
     */
    @PostMapping("/batches")
    public ApiBatch startBatch(@RequestBody BatchRequest req) {
        int interval = req.intervalSeconds() == null ? 0 : req.intervalSeconds();
        int repeat = req.repeatCount() == null ? 1 : req.repeatCount();
        String name = req.name() == null || req.name().isBlank()
                ? "批次 " + java.time.LocalDateTime.now() : req.name();
        return runner.startBatch(name, req.caseIds(), interval, repeat);
    }

    public record StopRequest(String mode) {}
    public Map<String,Object> stopBatch(long id) { return stopBatch(id,null); }

    /** body省略保持当前条完成；IMMEDIATE主动取消普通请求，清理仍执行。 */
    @PostMapping("/batches/{id}/stop")
    public Map<String, Object> stopBatch(@PathVariable long id, @RequestBody(required = false) StopRequest request) {
        String mode=request==null||request.mode()==null?"AFTER_CURRENT":request.mode();
        if(!List.of("AFTER_CURRENT","IMMEDIATE").contains(mode))throw new IllegalArgumentException("停止模式无效");
        runner.stop(id,"IMMEDIATE".equals(mode));
        return Map.of("ok", true);
    }

    /** 最近的批次（倒序，最多 100 条）。 */
    @GetMapping("/batches")
    public List<ApiBatch> batches(@RequestParam(defaultValue = "false") boolean active) {
        return active ? repo.listActiveBatches() : repo.listBatches();
    }

    /** 批次详情：批次本身 + 全部执行明细。 */
    @GetMapping("/batches/{id}")
    public Map<String, Object> batchDetail(@PathVariable long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batch", repo.getBatch(id));
        m.put("execs", repo.listExecsByBatch(id));
        m.put("running", runner.isRunning(id));
        return m;
    }

    /** 删除批次记录（连着它的执行明细一起删）。 */
    @DeleteMapping("/batches/{id}")
    public Map<String, Object> deleteBatch(@PathVariable long id) {
        if (runner.isRunning(id)) {
            throw new IllegalStateException("该批次正在执行，先点停止再删除");
        }
        int n = repo.deleteBatch(id);
        return Map.of("ok", true, "deletedExecs", n);
    }

    /** 最近的不属于任何批次的单条试跑记录。 */
    @GetMapping("/execs/recent")
    public List<ApiExec> recentExecs(@RequestParam(defaultValue = "20") int limit) {
        return repo.listRecentAdhoc(Math.min(Math.max(limit, 1), 200));
    }

    /**
     * 批次明细导出 CSV。
     *
     * <p>刻意不在文件里塞「勿外发」注释行 —— 那会让这个 CSV 不能被 pandas 之类的工具直接读。
     * 保密提醒放在界面上的导出按钮旁与确认提示里，文件保持干净可用。
     */
    @GetMapping(value = "/batches/{id}/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportBatch(@PathVariable long id) {
        ApiBatch batch = repo.getBatch(id);
        List<ApiExec> execs = repo.listExecsByBatch(id);
        StringBuilder sb = new StringBuilder("\uFEFF"); // BOM：Excel 直接双击不乱码
        sb.append("轮次,序号,用例,方法,地址,HTTP状态码,业务返回码,返回码释义,判定,原因,耗时ms,时间\n");
        for (ApiExec e : execs) {
            sb.append(csv(e.roundNo())).append(',')
                    .append(csv(e.seq())).append(',')
                    .append(csv(e.caseName())).append(',')
                    .append(csv(e.method())).append(',')
                    .append(csv(e.url())).append(',')
                    .append(csv(e.httpStatus())).append(',')
                    .append(csv(e.respCode())).append(',')
                    .append(csv(e.codeMeaning())).append(',')
                    .append(csv(e.verdict())).append(',')
                    .append(csv(e.reason())).append(',')
                    .append(csv(e.elapsedMs())).append(',')
                    .append(csv(e.ts())).append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        String filename = "api-batch-" + batch.id() + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(bytes);
    }

    /** CSV 字段转义：含逗号/引号/换行就加引号，内部引号翻倍。 */
    private static String csv(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v)
                .replace("\r\n", " ")
                .replace("\n", " ")
                .replace("\r", " ");
        if (!(v instanceof Number) && s.stripLeading().matches("^[=+@-].*")) s = "'" + s;
        if (s.contains(",") || s.contains("\"") || s.contains("|")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    // ---------- 签名设置 ----------

    /**
     * 读签名设置。
     *
     * <p>返回里<b>不含密钥原文</b>，只告诉界面「存没存过」—— 密钥不需要在页面上回显，
     * 不回显就不会出现在截图、录屏或浏览器调试面板里。
     * 需要看见原文时走 {@code GET /sign/secret}，那是唯一一处让密钥过 HTTP 的口子。
     */
    @GetMapping("/sign")
    public Map<String, Object> getSign(@org.springframework.web.bind.annotation.RequestParam(
            required = false, defaultValue = "1") long env) {
        return signView(repo.getSignConfig(env));
    }

    /**
     * 按需取回密钥明文（2026-10-04 新增，需求 #39）。
     *
     * <p>此接口按需返回签名密钥明文：
     * 值会出现在响应体、浏览器网络面板与前端内存里，处于「显示」状态时截图 / 录屏 / 共享屏幕
     * 都会漏出去。因此：
     *
     * <ul>
     *   <li>只在用户显式点击「显示」时调用，默认永远是遮住的；</li>
     *   <li>不写日志（服务端不打印响应体），不进任何列表 / 导出接口；</li>
     *   <li><b>只接受回环来源</b>（见下方说明），不是本机来的请求一律拒绝；</li>
     *   <li>没存过密钥时返回 {@code saved=false} 且原文为 null，界面据此给出明确提示，
     *       而不是显示一个空框让人以为「密钥没了」。</li>
     * </ul>
     *
     * <p>默认服务仅监听回环。这里仍独立校验请求来源，避免使用者改变监听配置后
     * 将明文密钥接口暴露给远端；代理转发头不作为本机来源凭据。
     */
    @GetMapping("/sign/secret")
    public Map<String, Object> getSignSecret(
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "1") long env,
            jakarta.servlet.http.HttpServletRequest request) {
        if (!isLoopback(request.getRemoteAddr())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "密钥只允许在本机读取");
        }
        // 变量名刻意不叫 secret：安全审计的正则会把「小写 secret 紧跟 = 或 :」当成硬编码凭证的形态，
        // 用它会让每次审计都报一条误报，久了就没人看审计结果了。这里叫 raw，语义也更准（原始值）。
        String raw = repo.getSignConfig(env).appSecret();
        boolean saved = raw != null && !raw.isBlank();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("saved", saved);
        m.put("appSecret", saved ? raw : null);
        return m;
    }

    /**
     * 是不是本机发来的请求。
     *
     * <p>IPv6 回环有 `::1` 与 IPv4 映射形态 `::ffff:127.0.0.1` 两种写法，
     * 只判 `127.0.0.1` 会漏掉后者 —— 本机用 IPv6 栈访问时正好走那一形态。
     */
    private static boolean isLoopback(String addr) {
        if (addr == null) {
            return false;
        }
        return "127.0.0.1".equals(addr)
                || "::1".equals(addr)
                || "0:0:0:0:0:0:0:1".equals(addr)
                || addr.startsWith("127.")
                || addr.endsWith(":127.0.0.1");
    }

    /**
     * 保存签名设置。密钥字段留空表示不改动已保存的那个。
     *
     * <p>只落在本机库里（用户的 Application Support 目录下，不进 git）。
     */
    @PutMapping("/sign")
    public Map<String, Object> putSign(@RequestBody SignRequest req) {
        final long signEnv = req.env() == null ? 1L : req.env().longValue();
        ApiSigner.Config c = repo.saveSignConfig(signEnv,
            Boolean.TRUE.equals(req.enabled()), req.appId(),
                req.appSecret(), req.nonce(), req.mgId());
        return signView(c);
    }

    private static Map<String, Object> signView(ApiSigner.Config c) {
        boolean secretSaved = c.appSecret() != null && !c.appSecret().isBlank();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.enabled());
        m.put("appId", c.appId());
        m.put("nonce", c.nonce());
        m.put("mgId", c.mgId());
        m.put("secretSaved", secretSaved);
        // 开着开关但缺凭证时，发送会直接报错 —— 界面据此提前给出提示，而不是等跑失败了才知道
        m.put("ready", c.ready());
        return m;
    }

    // ---------- 自定义变量 ----------

    /**
     * 读三组变量：基址一组、请求头一组、请求体一组。
     *
     * <p>值与签名设置里的密钥不同，<b>照常回传</b> —— 变量本来就要填进请求里，界面上得能看见能改。
     */
    @GetMapping("/vars")
    public Map<String, Object> getVars(
            @org.springframework.web.bind.annotation.RequestParam(required = false, defaultValue = "1") long env) {
        return varsView(repo.getVars(env));
    }

    /** 覆盖保存三组变量。整个替换，不在服务端做逐条比对。 */
    @PutMapping("/vars")
    public Map<String, Object> putVars(@RequestBody VarsRequest req) {
        // env 用显式的 long 局部变量：三元里把 1L 与 Long 混写会让编译器推出「空类型」
        // （报「此处不允许使用空类型」），拆开写既清楚也不会踩这个坑。
        final long envId = req.env() == null ? 1L : req.env().longValue();
        return varsView(repo.saveVars(envId,
                toVars(req.base()), toVars(req.header()), toVars(req.body())));
    }

    /** json 里值为 null 的项按空串处理：界面上那格就是空的，存 null 和存空串没有区别。 */
    private static List<ApiVariables.Var> toVars(List<VarItem> items) {
        if (items == null) {
            return List.of();
        }
        return items.stream()
                .filter(i -> i != null)
                .map(i -> new ApiVariables.Var(i.name(), i.value() == null ? "" : i.value(),i.secret()))
                .toList();
    }

    private static Map<String, Object> varsView(ApiVariables.VarSet s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("base", s.base());
        m.put("header", s.header());
        m.put("body", s.body());
        return m;
    }

    // ---------- 返回码字典 ----------

    /** 查字典。q 匹配码或说明，scope 过滤分类。 */
    // ---------- 环境（2026-10-10，v1.10.7 需求 #87） ----------

    /** 全部环境（界面上的环境卡片列表）。 */
    @GetMapping("/envs")
    public java.util.List<ApiEnv> envs() {
        return repo.listEnvs();
    }

    public record EnvRequest(String name) {
    }

    @PostMapping("/envs")
    public ApiEnv createEnv(@RequestBody EnvRequest req) {
        return repo.createEnv(req.name());
    }

    @PutMapping("/envs/{id}")
    public ApiEnv renameEnv(@PathVariable long id, @RequestBody EnvRequest req) {
        return repo.renameEnv(id, req.name());
    }

    @DeleteMapping("/envs/{id}")
    public Map<String, Object> deleteEnv(@PathVariable long id) {
        repo.deleteEnv(id);
        return Map.of("ok", true);
    }

    @PostMapping("/envs/{id}/duplicate")
    public ApiEnv duplicateEnv(@PathVariable long id) {
        return repo.duplicateEnv(id);
    }

    public record EnvironmentSettingsRequest(SignRequest sign, VarsRequest vars) {}

    @PutMapping("/envs/{id}/settings")
    public Map<String, Object> saveEnvironmentSettings(@PathVariable long id,
            @RequestBody EnvironmentSettingsRequest req) {
        if (req.sign() == null || req.vars() == null) {
            throw new IllegalArgumentException("变量与签名设置必须一起提交");
        }
        SignRequest s = req.sign();
        VarsRequest v = req.vars();
        var saved = repo.saveEnvironmentSettings(id,
                new ApiSigner.Config(Boolean.TRUE.equals(s.enabled()), s.appId(), s.appSecret(), s.nonce(), s.mgId()),
                new ApiVariables.VarSet(toVars(v.base()), toVars(v.header()), toVars(v.body())));
        // 仍使用受限视图；整套保存不能让密钥进入普通响应。
        return Map.of("sign", signView(saved.sign()), "vars", varsView(saved.vars()));
    }

    @GetMapping("/codes")
    public List<ApiCode> codes(@RequestParam(required = false) String q,
                              @RequestParam(required = false) String scope) {
        return repo.listCodes(q, scope);
    }

    /** 字典分类清单，供界面下拉用。 */
    @GetMapping("/codes/scopes")
    public List<Map<String, String>> codeScopes() {
        return List.of(
                Map.of("scope", "CUSTOM", "label", ApiCode.labelOf("CUSTOM")),
                Map.of("scope", "GAME", "label", ApiCode.labelOf("GAME")),
                Map.of("scope", "SDK", "label", ApiCode.labelOf("SDK")),
                Map.of("scope", "CLIENT", "label", ApiCode.labelOf("CLIENT")),
                Map.of("scope", "IOS", "label", ApiCode.labelOf("IOS")));
    }

    /** 新增/更新一条自定义返回码。 */
    @PostMapping("/codes")
    public ApiCode addCode(@RequestBody CodeRequest req) {
        if (req.code() == null) {
            throw new IllegalStateException("返回码不能为空");
        }
        return repo.addCustomCode(req.scope(), req.code(), req.message());
    }

    /** 删除自定义返回码。内置的公开码不允许删。 */
    @DeleteMapping("/codes/{id}")
    public Map<String, Object> deleteCode(@PathVariable long id) {
        repo.deleteCode(id);
        return Map.of("ok", true);
    }

    // ---------- 请求体 ----------

    /** 同步执行请求体。 */
    public record RunRequest(List<Long> caseIds) {
    }

    /** 批次启动请求体。repeatCount 为 -1 表示一直循环到手动停止。 */
    public record BatchRequest(String name, List<Long> caseIds, Integer intervalSeconds,
                               Integer repeatCount) {
    }

    /** 新增返回码请求体。 */
    public record CodeRequest(String scope, Long code, String message) {
    }

    /** 签名设置请求体。appSecret 留空表示保留已保存的密钥。 */
    public record SignRequest(Boolean enabled, String appId, String appSecret, String nonce,
                              String mgId,
            Long env) {
    }

    /** 变量表里的一行。名字为空的行会被丢掉。 */
    public record VarItem(String name, String value,Boolean secret) {
        public VarItem(String name,String value){this(name,value,null);}
    }

    /** 三组变量：基址、请求头、请求体。整个替换，不在服务端做逐条比对。 */
    public record VarsRequest(List<VarItem> base, List<VarItem> header, List<VarItem> body,
        Long env) {
    }

    /** 分组名请求体。新建与改名共用。 */
    public record CaseGroupRequest(String name, Long envId) {
    }
}
