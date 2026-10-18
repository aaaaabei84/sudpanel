package com.sudpanel.perf;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudpanel.adb.AdbClient;
import com.sudpanel.api.ApiExec;
import com.sudpanel.api.ApiRepository;
import com.sudpanel.api.ApiRunner;
import com.sudpanel.api.ApiSpec;
import com.sudpanel.config.AppProperties;
import com.sudpanel.locator.UiLocator;

/**
 * M6 性能测试执行引擎。
 * - scanGames：滚动 dump 收集 (Custom) 后缀游戏，连续两次无新条目停止
 * - 正常模式（检测项 1/3/4/5）：每游戏 N 圈「找游戏→进大厅→加入→加AI→准备→开始→采10秒→关闭→采基线」
 * - 异常时序模式（检测项 2）：点开始→延迟→尽快点「游戏结束」→抓 logcat 崩溃关键字→查能否回大厅
 * 机器初判（PerfJudge）+ 无效圈标记 + 连续无效跳过游戏。多游戏按勾选顺序排队，per-game 独立记录。
 */
@Service
public class PerfRunService {

    private static final Logger log = LoggerFactory.getLogger(PerfRunService.class);

    /** 运行模式：NORMAL=正常压测（检测项 1/3/4/5），EARLY_SETTLE=异常时序压测（检测项 2：开局即结算）。 */
    public enum Mode { NORMAL, EARLY_SETTLE,
        /**
         * 人机对局（2026-10-06，需求 #68）：房间与对局由**接口推送**驱动。
         *
         * <p>与另外两个模式的根本区别：那两者靠点 UI 按钮把对局跑起来，这个模式只让真机做
         * 「点进游戏 → 加入房间 → 准备」，之后推 8 个人机进房、推开局、对局时长到了再推结束与清房 ——
         * 全部走接口。之所以要这样：人机数量与开局时机在 UI 上没法精确控制（点得到不代表生效），
         * 而接口推送的成功与否是可以逐条校验的。
         */
        AI_ROOM }

    /**
     * 单圈结局（2026-10-09，v1.9.4 #73）。
     *
     * <p>把「这一圈没跑成」拆成两种原因，是这一版的核心改动：
     * <ul>
     *   <li>{@link #OK} —— 本圈有效。</li>
     *   <li>{@link #INVALID} —— **业务无效**：坐标失效、按钮没出现、接口答复了但结果不对。
     *       这类失败会累加「连续无效」计数，攒够就跳过该游戏（原语义保留）。</li>
     *   <li>{@link #ENV_FAILURE} —— **环境故障**：连不上、超时、解析不了。
     *       这类失败**不计入**连续无效，而是进入等待，恢复后重跑同一个圈号。
     *       避免环境问题消耗业务失败配额。</li>
     * </ul>
     */
    private enum LoopOutcome { OK, INVALID, ENV_FAILURE }

    /** 崩溃关键字（检测项 2 logcat 判定）。 */
    private static final List<Pattern> CRASH_PATTERNS = List.of(
            Pattern.compile("FATAL EXCEPTION"),
            Pattern.compile("FATAL"),
            Pattern.compile("ANR in "),
            Pattern.compile("Force finishing"),
            Pattern.compile("has died"),
            Pattern.compile("BEGIN TO CRASH", Pattern.CASE_INSENSITIVE));

    /** 加载失败特征文本。 */
    private static final String LOAD_FAIL_TEXT = "加载失败";

    /** 单圈 watchdog 上限：超时强制标无效并恢复，防单圈卡死。 */
    private static final long LOOP_WATCHDOG_MS = 240_000;

    /**
     * 游戏级对局时长启用时，watchdog 截止 = 时长 + 这段缓冲（进房/推送/开局/收尾/回列表的余量）。
     * #70：没有它，超过 4 分钟的局会在采样中途被 watchdog 误判成「单圈超时」而报废。
     */
    private static final long WATCHDOG_EXTRA_SEC = 120;

    /** 设备掉线等待重连上限（秒）：超过则中止 run（可续跑）。 */
    private static final int DEVICE_WAIT_MAX_SEC = 1800;

    // ---------- 接口环境故障的兜底参数（2026-10-09，v1.9.4 #73~#79）----------
    //
    // 将网络故障与业务用例失败分开处理，避免环境不可达时耗尽游戏的圈数配额。

    /** 环境探活单次连接超时（毫秒）。纯 TCP 连接，不发业务请求。 */
    private static final int ENV_PROBE_TIMEOUT_MS = 3_000;
    /** 环境等待时的探活间隔（毫秒）。 */
    private static final long ENV_PROBE_INTERVAL_MS = 5_000;
    /**
     * 环境等待上限（秒）：超过则整个 run 判 ABORTED（可续跑）。
     * 与设备掉线的 DEVICE_WAIT_MAX_SEC 对齐同一量级，但短得多 ——
     * 接口环境挂 5 分钟通常不是「等一等就好」，继续干等只是浪费真机时间。
     */
    private static final int ENV_WAIT_MAX_SEC = 300;
    /** 推人机失败后的重试次数（不含首次）。 */
    private static final int PUSH_RETRY_TIMES = 2;
    /** 推人机重试的退避间隔（毫秒），长度 = PUSH_RETRY_TIMES。 */
    private static final long[] PUSH_RETRY_BACKOFF_MS = {3_000L, 6_000L};
    /** 对局提前结束检测（#78）：每采多少个点查一次 logcat。 */
    private static final int EARLY_END_CHECK_EVERY = 10;
    /**
     * 日志裁剪行数（#78 / #86 共用的 {@code logcatTail}）。
     *
     * <p><b>0 = 不裁剪，读整个缓冲区。</b>对局标识或结束事件可能出现在较早的日志中，
     * 仅保留尾部固定行数会遗漏这些信息。
     *
     * <p>{@code AdbClient.logcatTail} 先读取设备缓冲区，再在本地裁剪；
     * 裁剪只减少返回文本，不减少设备侧读取范围。日志归属仍需由本圈边界判定。
     */
    private static final int LOG_TAIL_LINES = 0;

    /**
     * 对局 roundId 的提取式（2026-10-10，v1.10.4 需求 #86）。
     *
     * <p>兼容以下常见分隔形式：
     * {@code gameRoundId=xxx}、{@code gameRoundId: xxx}、{@code "gameRoundId":"xxx"}。
     * 所以要求「键名 → 可选的引号 → 必须有一个 : 或 = → 可选引号 → 值」。
     *
     * <p><b>那个必须的 : 或 = 是有意的</b>：若放宽成「键名 + 最多几个非字母数字字符 + 值」，
     * 那么 {@code gameRoundId is 123} 这种自然语言的句子会被匹配成 {@code is} —— 宁可漏取也不误取。
     */
    private static final java.util.regex.Pattern GAME_ROUND_ID_PATTERN =
            java.util.regex.Pattern.compile("gameRoundId[\"']?\\s*[:=]\\s*[\"']?([A-Za-z0-9_.\\-]+)");

    // ---------- 依赖与配置 ----------
    // adb：设备操作客户端（点按、shell 命令、UI dump、logcat 读写）
    private final AdbClient adb;
    // locator：UI 布局解析器（按 resource-id / 文本找按钮中心坐标）
    private final UiLocator locator;
    // collector：性能采样器（PSS / CPU / 线程数等指标的采集与解析）
    private final PerfCollector collector;
    // repo：SQLite 数据访问层（run / loop / sample / check 结果的读写）
    private final PerfRepository repo;
    // judge：机器初判引擎（对采集数据做规则判定，产出检测项结论）
    private final PerfJudge judge;
    // props：全局应用配置（默认圈数、采样时长、采样间隔等）
    private final AppProperties props;
    // json：Jackson 对象映射器（把判定证据等对象序列化成 JSON 字符串落库）
    private final ObjectMapper json = new ObjectMapper();

    // calibration：按钮坐标标定 / resource-id 解析（坐标缓存 + 分组化坐标方案 v1.2 #4）
    private final CalibrationService calibration;
    private final PerfEvidenceRepository evidence;

    /** 人机对局模式的接口推送（需求 #68）；其余模式用不到，构造器允许为 null。 */
    private final ApiRunner apiRunner;
    /** 按 id 取用例（人机对局模式的四条绑定）。 */
    private final ApiRepository apiRepo;

    // runs：runId → 内存运行态；run 结束后仍保留在内存供进度查询；进程重启后由数据库兜底
    private final ConcurrentHashMap<Long, PerfRunState> runs = new ConcurrentHashMap<>();
    // executor：单线程执行器——所有 run 串行执行，避免多个 run 并发操作同一台设备互相干扰
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    // runningRunId：全局执行权（互斥）的**持有者标记** —— 记录当前占用执行权的 runId，null 表示空闲。
    //
    // 2026-09-28 修复 #1：此处原为 AtomicBoolean anyRunning，且 stop() 会提前 set(false)。
    // 两个缺陷叠加的后果：
    //   1) stop() 提前释放 → 旧 run 线程还在收尾时就允许 start 新 run，任务被塞进单线程 executor 队列排队；
    //   2) runAll 的 finally 又无条件 set(false) → 会把「新 run 刚占用的标记」清掉，于是可以再 start 第三个。
    //   结果：用户以为已停止的 run 仍在跑，且队列里堆积多个「幽灵任务」。
    // 现改为持有者模式：**释放时必须校验持有者**，且 stop() 不再释放（统一由 runAll 的 finally 释放）。
    private Long runningRunId = null;

    /** 尝试占用执行权（start/resume 前调用）。已被占用则返回 false。 */
    private synchronized boolean tryAcquireEngine(long runId) {
        if (runningRunId != null) {
            return false;
        }
        runningRunId = runId;
        return true;
    }

    /** 释放执行权：**仅当持有者就是该 runId 时**才释放，防止旧 run 收尾误清新 run 的标记。 */
    private synchronized void releaseEngine(long runId) {
        if (runningRunId != null && runningRunId == runId) {
            runningRunId = null;
        }
    }

    /** 当前是否已有 run 占用执行权（含等待设备等挂起态）。 */
    public synchronized boolean hasExecutionOwner() { return isEngineBusy(); }

    private synchronized boolean isEngineBusy() {
        return runningRunId != null;
    }

    public PerfRunService(AdbClient adb, UiLocator locator, PerfCollector collector,
                          PerfRepository repo, PerfJudge judge, AppProperties props,
                          CalibrationService calibration) {
        this(adb, locator, collector, repo, judge, props, calibration, null, null);
    }

    /**
     * 带接口推送能力的构造（2026-10-06，需求 #68）。
     *
     * <p>{@code apiRunner} 与 {@code apiRepo} 只服务于「人机对局」模式：那一个模式要按当前游戏的
     * mg_id / app_id 调接口推送指令。另外两个模式一行都碰不到它们，所以保留了上面那个
     * 少两个参数的构造器 —— 老的测试与调用点不必为此多传两个用不上的依赖。
     */
    public PerfRunService(AdbClient adb, UiLocator locator, PerfCollector collector,
                          PerfRepository repo, PerfJudge judge, AppProperties props,
                          CalibrationService calibration, ApiRunner apiRunner, ApiRepository apiRepo) {
        this(adb, locator, collector, repo, judge, props, calibration, apiRunner, apiRepo, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PerfRunService(AdbClient adb, UiLocator locator, PerfCollector collector,
                          PerfRepository repo, PerfJudge judge, AppProperties props,
                          CalibrationService calibration, ApiRunner apiRunner, ApiRepository apiRepo,
                          PerfEvidenceRepository evidence) {
        this.adb = adb; this.locator = locator; this.collector = collector;
        this.repo = repo; this.judge = judge; this.props = props;
        this.calibration = calibration; this.apiRunner = apiRunner; this.apiRepo = apiRepo;
        this.evidence = evidence;
    }

    // ---------- 标定 / 分组 / resource-id 解析（已拆到 CalibrationService） ----------

    /** 列出全部分组（含标定进度与逐按钮中文名明细）。 */
    public List<Map<String, Object>> listButtonGroups() {
        return calibration.listButtonGroups();
    }

    /** 新建分组并返回详情（含空进度）。 */
    public Map<String, Object> createButtonGroup(String name, String deviceSerial) {
        return calibration.createButtonGroup(name, deviceSerial);
    }

    /** 删除分组（含其坐标）。 */
    public void deleteButtonGroup(long groupId) {
        calibration.deleteButtonGroup(groupId);
    }

    /** 标定分组内的按钮（指定 key 单标 / 不指定 key 全标）。 */
    public Map<String, Object> calibrateGroup(String deviceSerial, long groupId, String key) {
        return calibration.calibrateGroup(deviceSerial, groupId, key);
    }

    // ---------- 游戏列表扫描 ----------

    /** 滚动采集游戏列表（仅 (Custom) 后缀右列），upsert 后返回全量游戏。 */
    public List<Map<String, Object>> scanGames(String deviceSerial) {
        // 游戏列表项容器的 resource-id
        String itemRid = calibration.rid("perf_game_list_item");
        // 先滚到顶（起点可能在任意位置）：首条目文本稳定即认为到顶
        String lastFirst = null;
        for (int i = 0; i < 12; i++) {
            swipeUp(deviceSerial);
            sleep(600);
            String xml = adb.uiDump(deviceSerial);
            List<UiLocator.TextNode> items = locator.findByResourceId(xml, itemRid);
            // 取当前屏第一个列表项文本作为"是否已到顶"的判据
            String first = items.isEmpty() ? "" : items.get(0).text();
            // 连续两次第一个条目相同 → 列表已无法再向上滚动，即到达顶部
            if (first != null && first.equals(lastFirst)) {
                break;
            }
            lastFirst = first;
        }
        // 已见游戏去重集合：key=游戏文本，value=该条目坐标；LinkedHashMap 保持首次发现的顺序
        Map<String, int[]> seen = new LinkedHashMap<>();
        // 连续"无新条目"的轮数：连续 2 轮没有新游戏即认为扫完了整个列表
        int noNew = 0;
        for (int i = 0; i < 15 && noNew < 2; i++) {
            String xml = adb.uiDump(deviceSerial);
            boolean foundNew = false;
            // 遍历当前屏所有列表项，只收集以 (Custom) 结尾的自定义游戏（平台自建游戏）
            for (UiLocator.TextNode n : locator.findByResourceId(xml, itemRid)) {
                String t = n.text();
                if (t != null && t.endsWith("(Custom)") && !seen.containsKey(t)) {
                    // 新游戏：记录文本与坐标，标记本轮有新发现
                    seen.put(t, new int[]{n.x(), n.y()});
                    foundNew = true;
                }
            }
            // 本轮有新发现则重置连续计数，否则 +1（达到 2 就停止滚动）
            noNew = foundNew ? 0 : noNew + 1;
            // 向下滚动一屏继续扫
            swipeDown(deviceSerial);
            // 等滚动动画结束、列表稳定后再 dump，避免截到滚动中间态
            sleep(1000);
        }
        // 全部扫完：逐个 upsert 入库（已存在则更新；短名 = 去掉 "(Custom)" 后缀）
        for (String name : seen.keySet()) {
            repo.upsertGame(name, name.replace("(Custom)", ""));
        }
        // 返回数据库中的完整游戏列表（含之前入库的历史游戏）
        return repo.listGames();
    }

    // ---------- 执行 ----------

    /** 运行中的内存态（供进度查询）。 */
    public static class PerfRunState {
        // id：run 主键（对应数据库 runs 表），也是前端查询进度的凭据
        public final long id;
        // device：设备序列号（本次 run 绑定的手机）
        public final String device;
        // mode：运行模式（NORMAL 正常压测 / EARLY_SETTLE 异常时序压测）
        public final Mode mode;
        // gameIds：本次排队测试的游戏 id 列表，顺序即执行顺序
        public final List<Long> gameIds;
        // currentGameIdx：当前执行到第几个游戏（从 0 开始）
        public volatile int currentGameIdx = 0;
        // currentLoop：当前执行到第几圈（从 1 开始）
        public volatile int currentLoop = 0;
        // status：运行状态（RUNNING / STOPPED / WAIT_DEVICE / COMPLETED / ERROR / ABORTED / INTERRUPTED）
        public volatile String status = "RUNNING";
        // message：给前端展示的最新进度文案（游戏名、圈数、设备状态等）
        public volatile String message = "";
        // lastSample：最近一次采样点，前端实时曲线直接读取
        public volatile PerfCollector.Sample lastSample;
        /** 当前圈的 watchdog 截止时间（runAll 每圈刷新）。 */
        public volatile long loopDeadline = Long.MAX_VALUE;
        public volatile long loopDeadlineNanos = Long.MAX_VALUE;
        // startedAt：run 启动时刻（内存态创建即记录）
        public final Instant startedAt = Instant.now();
        public final long startedNanos=System.nanoTime();
        // 一次 run 只捕获一次，重连不会把临时常亮值覆盖成原值。
        public PerfExecutionPlan plan;
        public Integer originalStayOnSetting;
        public volatile Long evidenceLoopId;
        public volatile boolean loopEvidenceMissing;
        public volatile int evidenceFailureCount;
        // groupId：本次 run 使用的按钮坐标分组（v1.2 #4）；null = 未指定，回退旧的按设备缓存/固定坐标
        public final Long groupId;
        // groupCoords：该分组的坐标快照（启动时一次性载入，每圈查内存不查库）；groupId 为 null 时为空表
        public final Map<String, int[]> groupCoords;
        // aiCases：人机对局模式的四条用例绑定（需求 #68）；其余模式为 null
        public final AiCaseBinding aiCases;
        // aiCount：本轮推几个人机；null = 用用例原文的名单（与「推 0 个」不是一回事）
        public final Integer aiCount;

        // 包内构造：仅 start / resume 两个入口创建，字段一经赋值不可变
        PerfRunState(long id, String device, Mode mode, List<Long> gameIds) {
            this(id, device, mode, gameIds, null, Map.of(), null, null);
        }

        // 带按钮坐标分组的构造（v1.2 #4）
        PerfRunState(long id, String device, Mode mode, List<Long> gameIds,
                     Long groupId, Map<String, int[]> groupCoords) {
            this(id, device, mode, gameIds, groupId, groupCoords, null, null);
        }

        // 带人机对局配置的构造（需求 #68）
        PerfRunState(long id, String device, Mode mode, List<Long> gameIds,
                     Long groupId, Map<String, int[]> groupCoords,
                     AiCaseBinding aiCases, Integer aiCount) {
            this.id = id;
            this.device = device;
            this.mode = mode;
            this.gameIds = gameIds;
            this.groupId = groupId;
            this.groupCoords = groupCoords == null ? Map.of() : groupCoords;
            this.aiCases = aiCases;
            this.aiCount = aiCount;
        }
    }

    /**
     * 人机对局模式的四条用例绑定（2026-10-06，需求 #68）。
     *
     * <p>四个环节分别是：推人机、开局、结束、清房。这里是**已校验过存在**的用例 id ——
     * 校验放在启动时一次做完，而不是每圈推送时再查一次库：那样每圈要多四次查询，
     * 而且配置错了要等跑到那一圈才知道。
     */
    public record AiCaseBinding(long batch, long gameStart, long gameEnd, long roomClear) {
    }

    /**
     * 解析一条用例引用（2026-10-06，需求 #68）。纯函数，便于直接压断言。
     *
     * <p>先按**配好的 id** 找；配的 id 已经不存在（例：那条用例被删了）就退回按**名字**找。
     * 名字匹配先精确、再「唯一包含」—— 只认唯一命中，两条都包含同一个词时宁可判找不到：
     * 那种情况下猜错的代价是「推了不该推的用例」，而压测里推错指令的症状（房间里人不对）
     * 从结果上看不出是配置错了。
     *
     * @param configured  页面上选的用例 id（文本形式的数字）；null/空表示没配
     * @param defaultName 按名字兜底时用的名字
     * @return 命中的用例 id；没找到返回 null
     */
    static Long resolveCaseRef(String configured, String defaultName, List<ApiSpec> all) {
        if (all == null || all.isEmpty()) {
            return null;
        }
        if (configured != null && !configured.isBlank()) {
            try {
                long want = Long.parseLong(configured.trim());
                for (ApiSpec s : all) {
                    if (s.id() != null && s.id() == want) {
                        return want;
                    }
                }
            } catch (NumberFormatException ignore) {
                // 不是数字：落到按名字找
            }
        }
        if (defaultName == null || defaultName.isBlank()) {
            return null;
        }
        String want = defaultName.trim();
        for (ApiSpec s : all) {
            if (want.equals(s.name() == null ? "" : s.name().trim())) {
                return s.id();
            }
        }
        Long hit = null;
        for (ApiSpec s : all) {
            String n = s.name() == null ? "" : s.name();
            if (n.contains(want)) {
                if (hit != null) {
                    return null; // 命中不止一条：不猜
                }
                hit = s.id();
            }
        }
        return hit;
    }

    /**
     * 组装并校验人机对局模式的四条用例绑定（需求 #68）。
     *
     * <p>校验放在**启动时**一次做完：配置错了当场用中文说清缺哪一条，
     * 而不是等跑到那一圈才失败 —— 那时的现场是一个已经在跑的压测，用户只会以为功能坏了。
     */
    private AiCaseBinding resolveAiCases() {
        if (apiRepo == null) {
            throw new IllegalStateException("人机对局模式需要接口测试模块，但接口模块未装配");
        }
        List<ApiSpec> all = apiRepo.listCases();
        Map<String, String> saved = repo.getSettings(PerfRepository.aiRoomSettingKeys());
        Long batch = resolveCaseRef(saved.get(PerfRepository.SETTING_AI_CASE_BATCH), "批量加入", all);
        Long start = resolveCaseRef(saved.get(PerfRepository.SETTING_AI_CASE_START), "游戏开始", all);
        Long end = resolveCaseRef(saved.get(PerfRepository.SETTING_AI_CASE_END), "游戏结束", all);
        Long clear = resolveCaseRef(saved.get(PerfRepository.SETTING_AI_CASE_CLEAR), "房间清理", all);
        List<String> missing = new ArrayList<>();
        if (batch == null) {
            missing.add("推人机（默认找「批量加入」）");
        }
        if (start == null) {
            missing.add("开局（默认找「游戏开始」）");
        }
        if (end == null) {
            missing.add("结束游戏（默认找「游戏结束」）");
        }
        if (clear == null) {
            missing.add("清空房间（默认找「房间清理」）");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "该模式缺接口用例绑定（人机对局与重复开关游戏都靠它驱动）："
                            + String.join("、", missing)
                            + "。请先在接口测试里建好这几条用例，或在运行控制里手工指定。");
        }
        return new AiCaseBinding(batch, start, end, clear);
    }

    /** 读页面上配的人机数量；没配或不是正数返回 null（= 用用例原文的名单）。 */
    private Integer resolveAiCount() {
        String raw = repo.getSetting(PerfRepository.SETTING_AI_COUNT);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int n = Integer.parseInt(raw.trim());
            return n > 0 ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 启动一次性能测试 run：校验前置条件 → 参数回落默认值 → 落库 → 建内存态 → 异步提交执行。
     *  synchronized：与 resume 互斥，保证"创建 run"的复合操作原子性。
     * @param deviceSerial 设备序列号
     * @param mode 运行模式（NORMAL / EARLY_SETTLE / AI_ROOM）
     * @param gameIds 勾选的游戏 id 列表（列表顺序即执行顺序）
     * @param loopCount 每个游戏的圈数；null 或 <=0 时使用配置默认值
     * @param sampleSeconds 每圈的对局内采样秒数；null 或 <=0 时使用配置默认值
     *        （人机对局模式下它同时就是「开局多久后收尾」，需求里的 3 分钟＝180）
     * @param earlyTapDelayMs 异常时序模式"点开始 → 点结束"的延迟毫秒数；null 或 <=0 时使用配置默认值
     * @return 响应 Map：runId / 模式 / 游戏数量 / 圈数 */
    public synchronized Map<String, Object> start(String deviceSerial, String mode, List<Long> gameIds,
                                                  Integer loopCount, Integer sampleSeconds, Long earlyTapDelayMs,
                                                  Long buttonGroupId) {
        // 全局执行权检查：已有 run 在跑（含等待设备等挂起态）则拒绝新 run
        if (isEngineBusy()) {
            throw new IllegalStateException("已有性能测试在跑，先停止或等其结束");
        }
        // 至少要勾选一个游戏
        if (gameIds == null || gameIds.isEmpty()) {
            throw new IllegalStateException("未勾选游戏");
        }
        // 字符串模式转枚举：非法值会抛 IllegalArgumentException
        Mode m = Mode.valueOf(mode);
        int loops = loopCount != null && loopCount > 0 ? loopCount : props.perfLoopCount();
        int sampleSec = sampleSeconds != null && sampleSeconds > 0 ? sampleSeconds : props.perfSampleSeconds();
        long earlyDelay = earlyTapDelayMs != null && earlyTapDelayMs > 0 ? earlyTapDelayMs : props.perfEarlyTapDelayMs();
        PerfExecutionPlan plan=capturePlan(deviceSerial,m,gameIds,loops,sampleSec,earlyDelay,buttonGroupId,"START");
        var preflight=new PerfPreflightService(adb,repo,apiRepo,calibration).check(
                new PerfPreflightService.Request(deviceSerial,m.name(),gameIds,buttonGroupId),plan);
        if(!preflight.ready()) throw new IllegalStateException("启动预检未通过："+preflight.checks().stream().filter(c -> "BLOCK".equals(c.status())).map(PerfPreflightService.Check::message).collect(java.util.stream.Collectors.joining("；")));
        boolean interfaceDriven=m==Mode.NORMAL||m==Mode.AI_ROOM;
        AiCaseBinding aiCases=bindingsOf(plan);
        Integer aiCount=plan.aiCount();
        long runId=repo.insertPlannedRun(deviceSerial,plan);
        // 创建内存运行态（进度查询对象），并注册到 runs 表
        // 坐标分组（v1.2 #4）：指定分组时把该组坐标一次性载入内存，每圈点击直接查内存、不再查库
        Map<String, int[]> groupCoords = coordinatesOf(plan);
        PerfRunState st = new PerfRunState(runId, deviceSerial, m, new ArrayList<>(gameIds),
                buttonGroupId, groupCoords, aiCases, aiCount);
        st.plan=plan;
        recordEvent(st,PerfRunFacts.Type.START,"RUN",PerfRunFacts.Reason.STARTED,null);
        runs.put(runId, st);
        // 占用全局执行权：阻止并发 start/resume（start 方法本身 synchronized，检查与占用构成原子操作）
        tryAcquireEngine(runId);
        // 捕获最终值供 lambda 使用（局部变量进 lambda 必须是 effectively final）
        final int fLoops = loops, fSampleSec = sampleSec;
        final long fEarlyDelay = earlyDelay;
        // 提交到单线程执行器异步执行，本方法立刻返回，前端拿到 runId 后轮询进度
        if(apiRunner!=null) apiRunner.resetCookieScope("perf-run-"+runId);
        executor.submit(() -> runAll(st, fLoops, fSampleSec, fEarlyDelay));

        // 组装响应：返回给前端的关键信息
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runId", runId);
        resp.put("mode", m.name());
        resp.put("games", gameIds.size());
        resp.put("loopCount", loops);
        resp.put("preflight",preflight); resp.put("planState","FROZEN"); resp.put("planCapturedAt",plan.capturedAt()); resp.put("planHash",plan.hash());
        return resp;
    }

    /** 请求停止一次 run：标记内存态并落库，执行权由后台收尾释放。
     *  执行线程会在每圈循环的检查点（stopped()/alive()）读到标记后尽快退出。
     * @param runId 要停止的 run */
    public synchronized void stop(long runId) {
        // 取内存运行态；run 已自然结束时可能不在内存
        PerfRunState st = runs.get(runId);
        if (st != null) {
            // 终态不可回退（2026-09-28 修复 #1）：COMPLETED（正常跑完）/ ABORTED（设备掉线中止）
            // 都不应被一次迟到的 stop 覆写成 STOPPED —— 原实现是 check-then-set，存在竞态窗口。
            // 这里与 runAll 的「置 COMPLETED」共用 st 对象作为锁，把两处状态迁移串行化。
            synchronized (st) {
                if ("COMPLETED".equals(st.status) || "ABORTED".equals(st.status)) {
                    return;
                }
                st.status = "STOPPED";
                st.message = "已请求停止";
            }
        } else {
            // 内存无该 run（面板进程重启过，内存态已丢）：查库判断，已是终态则同样不回退
            Map<String, Object> rec = repo.getRun(runId);
            if (rec == null) {
                throw new IllegalStateException("运行不存在: " + runId);
            }
            String dbStatus = String.valueOf(rec.get("status"));
            if ("COMPLETED".equals(dbStatus) || "ABORTED".equals(dbStatus) || "STOPPED".equals(dbStatus)) {
                return;
            }
        }
        // 同步落库终态，保证进程崩溃时状态也不丢失
        repo.finishRun(runId, "STOPPED");
        if(st!=null) recordEvent(st,PerfRunFacts.Type.STOP_REQUESTED,"USER",PerfRunFacts.Reason.USER_REQUEST,null);
        else try{repo.facts().event(runId,PerfRunFacts.Type.STOP_REQUESTED,"USER",null,null,PerfRunFacts.Reason.USER_REQUEST,null);}catch(Exception missing){log.warn("run#{} 停止时间线保存失败",runId);}
        // 2026-09-28 修复 #1：**不再在此提前释放执行权**。
        // 原实现的 anyRunning.set(false) 会在「旧 run 线程仍在收尾」时就接受新 run，
        // 新任务被塞进单线程 executor 队列排队 —— 用户以为已停止的 run 实际还在跑，队列里堆积幽灵任务。
        // 现改为：执行权统一由 runAll 的 finally 通过 releaseEngine(runId) 释放（且校验持有者）。
        // 代价：stop 后需等旧线程在下一个检查点退出才能启动新 run，换取状态的确定性。
    }

    /** 断点续跑：INTERRUPTED/STOPPED/ERROR/ABORTED 的 run 从已完成圈数继续。
     *  同一 runId 续写，per-game 分析/报告保持连续；中断圈（INTERRUPTED）删除重做。 */
    public synchronized Map<String, Object> resume(long runId) { return resume(runId,"FROZEN"); }

    public synchronized Map<String,Object> resume(long runId, String strategy) {
        // 与 start 相同的全局执行权检查
        if (isEngineBusy()) {
            throw new IllegalStateException("已有性能测试在跑，先停止或等其结束");
        }
        // 从数据库取出历史 run 记录（面板重启后内存态已丢，一切以库为准）
        Map<String, Object> run = repo.getRun(runId);
        if (run == null) {
            throw new IllegalStateException("运行不存在: " + runId);
        }
        // 只有终态（中断/停止/异常/中止）才允许续跑；RUNNING 等进行中状态拒绝
        String status = String.valueOf(run.get("status"));
        if (!Set.of("INTERRUPTED", "STOPPED", "ERROR", "ABORTED").contains(status)) {
            throw new IllegalStateException("当前状态不可续跑: " + status);
        }
        // 还原游戏队列：把库里的逗号分隔串解析回 id 列表
        String queue = (String) run.get("gameQueue");
        List<Long> gameIds = new ArrayList<>();
        if (queue != null && !queue.isBlank()) {
            for (String s : queue.split(",")) {
                try {
                    gameIds.add(Long.parseLong(s.trim()));
                } catch (NumberFormatException ignore) {
                    // 跳过坏 id
                }
            }
        }
        // 队列解析后为空（全是脏数据或本就没有）：无法续跑
        if (gameIds.isEmpty()) {
            throw new IllegalStateException("运行无游戏队列，无法续跑");
        }
        // 还原其余运行参数：模式 / 圈数 / 采样秒数 / early 延迟 / 设备号
        Mode m = Mode.valueOf((String) run.get("mode"));
        int loops = ((Number) run.get("loopCount")).intValue();
        int sampleSec = ((Number) run.get("sampleSeconds")).intValue();
        long earlyDelay = ((Number) run.get("earlyTapDelayMs")).longValue();
        String device = (String) run.get("device");

        PerfExecutionPlan plan=repo.plan(runId);
        boolean newSnapshot=plan==null;
        if(plan==null) {
            if(!"CAPTURE_CURRENT".equals(strategy)) throw new IllegalStateException("旧运行未记录冻结计划；请明确选择按当前配置续跑，新的来源将单独标记");
            plan=capturePlan(device,m,gameIds,loops,sampleSec,earlyDelay,null,"RESUME_CURRENT");
            var check=new PerfPreflightService(adb,repo,apiRepo,calibration).check(new PerfPreflightService.Request(device,m.name(),gameIds,null),plan);
            if(!check.ready()) throw new IllegalStateException("当前配置续跑预检未通过");

        } else if(!"FROZEN".equals(strategy)) throw new IllegalArgumentException("已有冻结计划须沿用；需要新配置请新建运行");
        if(m==Mode.EARLY_SETTLE)try{
            String layout=plan.settings().get("coordinate.layout");if(layout==null||!new ObjectMapper().readValue(layout,PerfDeviceLayout.class).matches(PerfDeviceLayout.read(adb,device)))throw new IllegalStateException("冻结坐标来源未知或设备布局已变化，请重新标定并新建运行");
        }catch(Exception stale){throw new IllegalStateException("冻结坐标来源未知或设备布局已变化，请重新标定并新建运行",stale);}
        AiCaseBinding aiCases=bindingsOf(plan);
        Integer aiCount=plan.aiCount();
        // 条件标记必须先可靠落库；失败时不得删除中断圈、改状态或接管执行权。
        PerfRunFacts.Reason conditionReason=null;
        try { var facts=repo.facts();if(facts!=null&&facts.changedDevice(runId,PerfRunFacts.device(adb,device,plan.resources().get("perf_package"))))conditionReason="UNKNOWN".equals(facts.summary(runId).get("state"))?PerfRunFacts.Reason.CONDITIONS_UNVERIFIED:PerfRunFacts.Reason.CONDITIONS_DIFFER; }
        catch(Exception failure){throw new IllegalStateException("可比条件核验失败，未开始续跑；请检查本机数据状态后重试",failure);}
        // 先读并校验冻结计划，再修改既有历史；旧圈的planHash不补写。
        int removed = repo.prepareResume(runId,newSnapshot?plan:null);
        PerfRunState st = new PerfRunState(runId, device, m, gameIds, plan.coordinateGroupId(), coordinatesOf(plan), aiCases, aiCount);
        st.plan=plan;
        if(conditionReason!=null)recordEvent(st,PerfRunFacts.Type.CONDITIONS_CHANGED,"RUN",conditionReason,null);
        recordEvent(st,PerfRunFacts.Type.RESUME,"RUN",PerfRunFacts.Reason.USER_REQUEST,null);
        runs.put(runId, st);
        tryAcquireEngine(runId);
        log.info("run#{} 断点续跑：清理 {} 个中断圈，设备 {}", runId, removed, device);
        if(apiRunner!=null) apiRunner.resetCookieScope("perf-run-"+runId);
        executor.submit(() -> runAll(st, st.plan.loopCount(), st.plan.sampleSeconds(), st.plan.earlyTapDelayMs()));

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runId", runId);
        resp.put("mode", m.name());
        resp.put("games", gameIds.size());
        resp.put("loopCount", loops);
        // 额外返回清理的中断圈数量，前端可展示"续跑起点"
        resp.put("removedInterrupted", removed);
        return resp;
    }

    private PerfExecutionPlan capturePlan(String device,Mode mode,List<Long> gameIds,int loops,int seconds,long delay,Long groupId,String origin) {
        java.util.function.Supplier<PerfExecutionPlan> capture=() -> {
            boolean driven=mode==Mode.NORMAL||mode==Mode.AI_ROOM;
            if(!driven) { calibration.ensureGroupReady(groupId); calibration.ensureLayoutReady(device,groupId); }
            var bindings=driven?resolveAiCases():null;
            var requests=new LinkedHashMap<String,PerfExecutionPlan.Request>();
            if(driven) {
                long[] ids={bindings.batch(),bindings.gameStart(),bindings.gameEnd(),bindings.roomClear()};
                String[] roles={"推人机","开局","结束游戏","清空房间"};
                for(int i=0;i<ids.length;i++) { var spec=apiRepo.getCase(ids[i]); requests.put(roles[i],new PerfExecutionPlan.Request(roles[i],spec,apiRunner.snapshotEnvironment(spec,null))); }
            }
            var games=new LinkedHashMap<Long,Map<String,Object>>();
            for(Long id:gameIds) { var game=repo.getGame(id); if(game==null) throw new IllegalStateException("目标游戏不存在"); games.put(id,game); }
            var keys=new ArrayList<>(PerfRepository.aiRoomSettingKeys());keys.add(PerfRepository.SETTING_PERF_TRIM_BASELINE);keys.add(PerfDeviceHealth.TEMP_WARN);keys.add(PerfDeviceHealth.THERMAL_WARN);
            var settings=new LinkedHashMap<>(repo.getSettings(keys));
            settings.put("judge.cpuThreshold",String.valueOf(props.perfCpuThreshold()));settings.put("judge.memGrowthPercent",String.valueOf(props.perfMemGrowthPercent()));
            settings.put("judge.memGrowthAbsMb",String.valueOf(props.perfMemGrowthAbsMb()));settings.put("judge.warmupLoops",String.valueOf(props.perfWarmupLoops()));
            settings.put("judge.attrSharePercent",String.valueOf(props.perfAttrSharePercent()));settings.put("judge.attrMinR2",String.valueOf(props.perfAttrMinR2()));settings.put("judge.reclaimableCacheMb",String.valueOf(props.perfReclaimableCacheMb()));
            var resources=calibration.snapshotResources();
            PerfRunFacts.device(adb,device,resources.get("perf_package")).forEach((key,value)->settings.put("condition."+key,value));
            if(!driven)try{settings.put("coordinate.layout",new ObjectMapper().writeValueAsString(calibration.recordedLayout(device,groupId)));}catch(Exception failure){throw new IllegalStateException("坐标布局冻结失败",failure);}
            var coords=new LinkedHashMap<String,List<Integer>>();
            // 保留原冻结输入；接口模式仍优先实时控件树，未绑定的旧缓存不能用于直点。
            calibration.snapshotCoords(device).forEach((key,value)->coords.put(key,List.of(value[0],value[1])));
            if(!driven) calibration.groupCoordsOf(groupId).forEach((key,value)->coords.put(key,List.of(value[0],value[1])));
            return new PerfExecutionPlan(1,origin,Instant.now().toString(),mode.name(),loops,seconds,delay,driven?resolveAiCount():null,groupId,
                    gameIds,games,requests,settings,resources,coords,props.perfSampleIntervalMs(),props.perfTapSettleMs(),props.perfPlayingWaitMs(),props.perfMaxConsecutiveInvalid());
        };
        return apiRepo==null?capture.get():apiRepo.readSnapshot(capture);
    }
    private AiCaseBinding bindingsOf(PerfExecutionPlan plan) {
        if(plan.requests().isEmpty()) return null;
        return new AiCaseBinding(plan.requests().get("推人机").spec().id(),plan.requests().get("开局").spec().id(),plan.requests().get("结束游戏").spec().id(),plan.requests().get("清空房间").spec().id());
    }
    private Map<String,int[]> coordinatesOf(PerfExecutionPlan plan) { var map=new LinkedHashMap<String,int[]>();plan.coordinates().forEach((key,value)->map.put(key,new int[]{value.get(0),value.get(1)}));return map; }
    private Map<String,Object> gameOf(PerfRunState st,long id) { return st.plan==null?repo.getGame(id):st.plan.games().get(id); }
    private String settingOf(PerfRunState st,String key) { return st.plan==null?repo.getSetting(key):st.plan.settings().get(key); }
    private String resource(PerfRunState st,String key) { if(st.plan==null) return calibration.rid(key);String value=st.plan.resources().get(key);if(value==null||value.isBlank()) throw new IllegalStateException("冻结计划缺少资源配置："+key);return value.trim(); }
    private int[] fixedCoordinate(PerfRunState st,String key) { if(st.plan==null) return calibration.fixedCoord(key);String value=st.plan.resources().get(key);if(value==null) return null;try {String[] xy=value.split(",");return xy.length==2?new int[]{Integer.parseInt(xy[0].trim()),Integer.parseInt(xy[1].trim())}:null;}catch(Exception e){return null;} }

    /** 与启动、续跑及执行权释放串行，禁止删除仍在执行或收尾的运行。 */
    public synchronized void deleteRun(long runId) {
        Map<String, Object> run = repo.getRun(runId);
        if (run == null) {
            throw new IllegalStateException("运行不存在: " + runId);
        }
        // stop 可先持久化 STOPPED；真正恢复设备设置并停止写库后才释放执行权。
        if (runningRunId != null && runningRunId == runId) {
            throw new IllegalStateException("运行仍在执行或收尾，请先停止并等待收尾完成再删除");
        }
        if (Set.of("RUNNING", "WAIT_DEVICE", "WAIT_ENV").contains(String.valueOf(run.get("status")))) {
            throw new IllegalStateException("运行进行中，请先停止再删除");
        }
        // 同一把锁覆盖查状态和级联删除，避免 resume 在两者之间重新取得执行权。
        repo.deleteRun(runId);
        runs.remove(runId);
    }

    /** 查询 run 的实时进度（前端轮询接口）。
     *  优先读内存态（运行中/刚结束）；内存态丢失（面板重启）时退回数据库，只返回 status。
     * @param runId run id
     * @return 进度 Map：status / 模式 / 游戏进度 / 当前圈号 / 文案 / 最新采样等 */
    public Map<String, Object> progress(long runId) {
        // 优先读内存态（运行中或刚结束都在这里）
        PerfRunState st = runs.get(runId);
        Map<String, Object> m = new LinkedHashMap<>();
        if (st == null) {
            // 内存态不存在：说明面板重启过，从数据库兜底查状态
            Map<String, Object> dbRun = repo.getRun(runId);
            if (dbRun == null) {
                throw new IllegalStateException("运行不存在: " + runId);
            }
            m.put("status", dbRun.get("status"));
            return m;
        }
        m.put("runId", st.id);
        m.put("status", st.status);
        m.put("mode", st.mode.name());
        m.put("totalGames", st.gameIds.size());
        m.put("currentGameIdx", st.currentGameIdx);
        m.put("currentLoop", st.currentLoop);
        m.put("message", st.message);
        // 补充当前正在跑的游戏短名（索引有效时才查库）
        if (st.currentGameIdx < st.gameIds.size()) {
            Map<String, Object> g = gameOf(st,st.gameIds.get(st.currentGameIdx));
            m.put("currentGame", g != null ? g.get("shortName") : null);
        }
        // 最新采样点：前端实时曲线直接用（未采第一点时为 null）
        m.put("lastSample", st.lastSample);
        m.put("evidenceFailureCount", st.evidenceFailureCount);
        m.put("loopEvidenceMissing", st.loopEvidenceMissing);
        return m;
    }

    /** 是否处于"应停止"状态（被停止 / 被中止 / 异常）。所有执行循环用它做退出检查。 */
    private boolean stopped(PerfRunState st) {
        String s = st.status;
        return "STOPPED".equals(s) || "ABORTED".equals(s) || "ERROR".equals(s);
    }

    /** 圈内轮询用：未停止且未超单圈 watchdog。recoverToList 等恢复路径不用它（超时时仍需恢复）。 */
    private boolean alive(PerfRunState st) {
        return !stopped(st) && !watchdogExpired(st);
    }

    // ---------- 启动对账 ----------

    /** 面板进程重启后，残留的 RUNNING run/loop 标记为 INTERRUPTED（可续跑）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        try {
            for(var run:repo.activeRuns()) { try{long id=((Number)run.get("id")).longValue();repo.facts().event(id,PerfRunFacts.Type.INTERRUPTED,"RUN",null,null,PerfRunFacts.Reason.PROCESS_RESTART,null);}catch(Exception missing){log.warn("启动中断时间线保存失败，仍继续对账");} }
            // 面板上次异常退出时仍为 RUNNING 的 run → 标记 INTERRUPTED（下次可续跑）
            int r = repo.markInterruptedRuns();
            // 同理：未完成的圈也标记为 INTERRUPTED（续跑时会删除重做）
            int l = repo.markInterruptedLoops();
            if (r > 0 || l > 0) {
                log.info("启动对账：{} 个运行 / {} 个圈标记为 INTERRUPTED", r, l);
            }
        } catch (Exception e) {
            // 对账失败不阻断面板启动，最多损失"可续跑"能力
            log.warn("启动对账失败", e);
        }
    }

    // ---------- 引擎主流程 ----------

    /** 引擎主流程：按游戏队列逐游戏执行 N 圈。
     *  每圈前过设备健康门与屏幕守卫；圈内按模式分发到 runNormalLoop / runEarlySettleLoop；
     *  正常模式在游戏结束后对该游戏所有圈做聚合判定。全程 try-finally 兜底，保证终态落库。 */
    private void runAll(PerfRunState st, int loopCount, int sampleSec, long earlyDelay) {
        try {
            // 防锁屏：USB 插着常亮 + 确保当前亮屏（run 结束 finally 恢复）
            try {
                // 打开"充电时保持屏幕常亮"（依赖 USB 连接，压测期间屏幕不灭）
                st.originalStayOnSetting = adb.stayOnSetting(st.device);
                if (st.originalStayOnSetting != null) adb.setStayOn(st.device, true);
                else log.warn("run#{} 无法读取原常亮设置，保留设备当前设置", st.id);
                ensureScreenOn(st);
            } catch (Exception e) {
                // 防锁屏初始化失败不致命：屏幕自己灭了的话，每圈前的 ensureScreenOn 会再唤醒
                log.warn("run#{} 防锁屏初始化失败（不影响执行）: {}", st.id, e.getMessage());
            }
            // 同一 run 内被测进程不重启，前序游戏结束后的内存状态可能影响后续基线。
            // 保存前一游戏结束水位，供判定层解释跨游戏比较条件。
            Double priorGameEndBaselineKb = null;
            // 外层循环：按队列顺序逐个游戏处理；任一时刻被 stop 就整体退出
            for (int gi = 0; gi < st.gameIds.size() && !stopped(st); gi++) {
                st.currentGameIdx = gi;
                long gameId = st.gameIds.get(gi);
                // 取游戏信息；数据库里已删掉的游戏直接跳过
                Map<String, Object> game = gameOf(st,gameId);
                if (game == null) {
                    continue;
                }
                String gameName = (String) game.get("name");
                String shortName = String.valueOf(game.get("shortName"));
                // 断点续跑：跳过已完成的圈
                // 查该游戏已完成的圈数，只跑剩余部分
                int done = repo.countLoops(st.id, gameId);
                if (done >= loopCount) {
                    // 已完成全部圈数：跳过该游戏
                    st.message = "游戏 " + shortName + " 已完成 " + done + " 圈，跳过";
                    log.info("run#{} 游戏 {} 已完成 {} 圈，跳过", st.id, shortName, done);
                } else {
                    if (done > 0) {
                        // 部分完成：从 done+1 圈续跑
                        st.message = "游戏 " + shortName + " 从第 " + (done + 1) + " 圈续跑";
                        log.info("run#{} 游戏 {} 已完成 {} 圈，从第 {} 圈续跑", st.id, shortName, done, done + 1);
                    } else {
                        // 全新游戏：正常开始
                        st.message = "游戏 " + shortName + " 开始";
                    }
                    // 连续无效圈计数：达到配置上限就跳过该游戏，防止坐标彻底失效时无限重试
                    int consecutiveInvalid = 0;
                    // 内层循环：把该游戏剩余圈数跑完
                    for (int loop = done + 1; loop <= loopCount && !stopped(st); loop++) {
                        st.currentLoop=loop;
                        // 设备健康门：掉线则等待重连，不占连续无效配额
                        if (!ensureDevice(st)) {
                            // 返回 false 且没被 stop → 说明等待重连超时：标记 ABORTED 并终止整个 run
                            if (!stopped(st)) {
                                st.status = "ABORTED";
                                st.message = "设备掉线超过 " + DEVICE_WAIT_MAX_SEC / 60 + " 分钟未恢复，中止（可续跑）";
                                log.error("run#{} {}", st.id, st.message);
                                notify("SudPanel 压测中断：run#" + st.id + " " + st.message);
                            }
                            break;
                        }
                        // 每圈开始前确保屏幕亮着（压测期间可能被系统熄屏）
                        ensureScreenOn(st);
                        // 更新进度：当前圈号
                        st.currentLoop = loop;
                        // 刷新本圈 watchdog 截止时间：超过 LOOP_WATCHDOG_MS 未完成即视为卡死（无效圈兜底）
                        setWatchdog(st,LOOP_WATCHDOG_MS);
                        st.message = "游戏 " + shortName + " 第 " + loop + "/" + loopCount + " 圈";
                        // 按模式分发单圈执行：NORMAL 走标准采样圈，EARLY_SETTLE 走异常时序圈，
                        // AI_ROOM 走「接口推人机 → 接口开局 → 采样 → 接口收尾」的人机对局圈
                        LoopOutcome outcome = switch (st.mode) {
                            case NORMAL -> runNormalLoop(st, gameId, gameName, loop, sampleSec);
                            case EARLY_SETTLE -> runEarlySettleLoop(st, gameId, gameName, loop, earlyDelay);
                            case AI_ROOM -> runAiRoomLoop(st, gameId, gameName, loop, sampleSec);
                        };
                        // 环境故障（#73/#74）：这一圈根本没跑起来 —— 不留痕、不计无效。
                        // 等环境恢复后重跑同一个圈号；等不到就把整个 run 判 ABORTED（可续跑）。
                        // 敢原地重跑，是因为环境故障的圈记录已被 runAiRoomLoop 删掉，圈数不会虚高。
                        if (outcome == LoopOutcome.ENV_FAILURE) {
                            if (!waitForEnvironment(st)) {
                                break; // 环境等待超限或用户已停止：waitForEnvironment 内部已置终态
                            }
                            loop--; // 与 for 的 loop++ 抵消：下一轮仍是这个圈号
                            continue;
                        }
                        // 有效圈清零连续计数，业务无效累加
                        consecutiveInvalid = outcome == LoopOutcome.OK ? 0 : consecutiveInvalid + 1;
                        if (consecutiveInvalid >= (st.plan==null?props.perfMaxConsecutiveInvalid():st.plan.maxConsecutiveInvalid())) {
                            // 跳过游戏前先确认环境（#77）：连续失败也可能整个是环境造成的。
                            // 环境不通 → 不跳过，等恢复后重跑本圈；环境通 → 才认定是坐标/用例真坏了。
                            // 只在 AI_ROOM 做这道判断：另外两个模式不用接口，aiCases 为 null，
                            // 探活必然失败，不加这个模式判断会让它们白白干等 5 分钟。
                            if (st.mode == Mode.AI_ROOM && !probeEnvironment(st)) {
                                log.warn("run#{} 游戏 {} 连续 {} 圈无效，但环境探活不通 —— 不跳过，先等环境",
                                        st.id, shortName, consecutiveInvalid);
                                if (waitForEnvironment(st)) {
                                    loop--;
                                    consecutiveInvalid = 0;
                                    continue;
                                }
                                break; // 环境等待超限
                            }
                            // 连续无效达上限：跳过该游戏，避免把整个 run 的时间耗在一个坏游戏上
                            st.message = "游戏 " + shortName + " 连续 "
                                    + consecutiveInvalid + " 圈无效，跳过该游戏";
                            log.warn("run#{} 游戏 {} 连续 {} 圈无效，跳过", st.id, shortName, consecutiveInvalid);
                            break;
                        }
                    }
                }
                // 正常模式与人机对局：聚合判定（先删旧结果再算，兼容续跑重算）
                // 仅这两种模式做机器初判：对该游戏全部有效圈聚合判定，写检测结果表。
                // 条件排除 STOPPED（用户主动停）/INTERRUPTED（启动对账残留无实际执行）；
                // ABORTED（设备掉线中止）/ERROR 也执行——数据在就有判定参考价值（2026-09-18 修复）
                // 异常时序模式不在此列：它那一路的结论是「有没有崩溃」，不在 PerfJudge 的检测项里。
                if ((st.mode == Mode.NORMAL || st.mode == Mode.AI_ROOM)
                        && !"STOPPED".equals(st.status) && !"INTERRUPTED".equals(st.status)
                        && repo.countLoops(st.id, gameId) > 0) {
                    // 先删旧结果再算：兼容断点续跑后的重新计算，避免重复/陈旧结果
                    repo.deleteChecks(st.id, gameId);
                    List<Map<String, Object>> loops = repo.loopsOf(st.id, gameId);
                    // judge 对每圈数据产出各检测项结论（判定 + 证据），逐条落库。
                    // 第二个参数是「前序游戏结束水位」（v1.2 #16）：判定层会把跨游戏水位标注写进证据串。
                    // 第三个参数是三阶段净增（v1.10.0 #82）：只进证据，不参与判定 —— 它回答的是
                    // 「增量发生在进房 / 局内 / 收尾哪一步」，那是判定回答不了的问题。
                    Map<Long, double[]> stageDeltas = repo.stageDeltas(st.id, gameId);
                    for (PerfJudge.CheckOutcome c : (st.plan==null?judge:new PerfJudge(st.plan.judgeProperties(props))).judgeNormal(loops, priorGameEndBaselineKb, stageDeltas)) {
                        repo.insertCheckResult(st.id, gameId, c.checkNo(), c.checkName(),
                                c.verdict().name(), c.evidence());
                    }
                    // 「不合格就提示跑对照」（2026-10-12 需求 #88）：
                    // 游戏内性能检测出现**疑似持续增长**时，提示再跑一次「重复开关游戏」。
                    // 为什么这件事值得弹一次通知：单轮数据回答不了「是不是游戏的问题」，
                    // 而重复开关游戏那条链路与人机对局完全相同（推人机/开局/结束/清房全走接口），
                    // 只是**局内只待 5 秒** —— 两轮一对照就能分开「对局运行期间漏」与「进出游戏流程漏」。
                    // 漏掉这一步，使用者会拿着一个 SUSPECT 去猜责任方。
                    if (st.mode == Mode.AI_ROOM) {
                        boolean suspect = repo.checksOf(st.id, gameId).stream()
                                .anyMatch(c -> "SUSPECT".equals(strOrNull(c.get("verdict"))));
                        if (suspect) {
                            notify("SudPanel 性能判定：run#" + st.id + " " + gameName
                                    + " 疑似内存持续增长。建议再跑一次「重复开关游戏」做对照 —— "
                                    + "它链路相同但局内只待几秒：若照样每圈稳定增长，说明与游戏运行时长无关。");
                        }
                    }
                    // 本游戏判定完毕后，用它最后一个有效圈的关后基线更新「前序水位」，传给下一个游戏
                    priorGameEndBaselineKb = lastOkBaselineKb(loops);
                }
            }
            // 全部游戏处理完且期间未被停止：标记整体完成
            // 与 stop() 的置位共用 st 对象作为锁：原实现是 check-then-set，
            // 在「检查 !stopped」与「置 COMPLETED」之间可能被一次 stop 请求插入，
            // 导致已完成的 run 被覆写成 STOPPED（终态可回退）。加锁后两处状态迁移串行化。
            synchronized (st) {
                if (!stopped(st)) {
                    st.status = "COMPLETED";
                    st.message = "全部游戏完成";
                }
            }
        } catch (Exception e) {
            // 任何未捕获异常：收敛为 ERROR 状态（可续跑），交给 finally 收尾
            log.error("run#{} 引擎异常", st.id, e);
            // 同样加锁并判终态：已 COMPLETED/ABORTED 的 run 不应被异常路径回退为 ERROR
            synchronized (st) {
                if (!"COMPLETED".equals(st.status) && !"ABORTED".equals(st.status)) {
                    st.status = "ERROR";
                    st.message = "引擎异常: " + e.getMessage();
                }
            }
            notify("SudPanel 压测异常：run#" + st.id + " " + st.message);
        } finally {
            // 收尾四件事：清 watchdog → 落库终态 → 恢复设备常亮设置 → 释放执行权（校验持有者）
            st.loopDeadline = Long.MAX_VALUE;st.loopDeadlineNanos=Long.MAX_VALUE;
            try {
                recordEvent(st,PerfRunFacts.Type.FINALIZED,"FINALIZE","STOPPED".equals(st.status)?PerfRunFacts.Reason.STOPPED:"COMPLETED".equals(st.status)?PerfRunFacts.Reason.COMPLETED:PerfRunFacts.Reason.ERROR,elapsed(st.startedNanos));
                repo.finishRun(st.id, st.status);
            } finally {
                try {
                    if (st.originalStayOnSetting != null
                            && !adb.restoreStayOnSetting(st.device, st.originalStayOnSetting)) {
                        log.warn("run#{} 设备原常亮设置恢复失败", st.id);
                    }
                } catch (Exception restoreFailure) {
                    log.warn("run#{} 设备原常亮设置恢复异常", st.id);
                } finally {
                    // 先恢复设备设置再释放执行权，避免旧 run 覆盖新 run 的常亮设置。
                    releaseEngine(st.id);
                }
            }
        }
    }

    // ---------- 设备/屏幕守卫 ----------

    /** 设备在线检查；掉线则 10s 轮询等待重连（上限 DEVICE_WAIT_MAX_SEC）。
     *  @return true=设备在线可继续；false=停止或等待超限（调用方区分 stopped 与 ABORTED）。 */
    private boolean ensureDevice(PerfRunState st) {
        long started=System.nanoTime();int waited=0;boolean wasDown=false;
        while(!stopped(st)) {
            String state=adb.getState(st.device);
            synchronized(st) {
                if(stopped(st))return false;
                if("device".equals(state)) {
                    if(wasDown){st.status="RUNNING";st.message="设备已重连，继续执行";recordEvent(st,PerfRunFacts.Type.WAIT_FINISHED,"DEVICE_WAIT",PerfRunFacts.Reason.DEVICE_RECONNECTED,elapsed(started));}
                } else if(!wasDown){wasDown=true;st.status="WAIT_DEVICE";recordEvent(st,PerfRunFacts.Type.WAIT_STARTED,"DEVICE_WAIT",PerfRunFacts.Reason.DEVICE_DISCONNECTED,null);}
            }
            if("device".equals(state)) {
                if(wasDown) {if(st.originalStayOnSetting!=null&&!stopped(st))try{adb.setStayOn(st.device,true);}catch(Exception ignored){}sleep(3000);}
                return !stopped(st);
            }
            recordEvent(st,PerfRunFacts.Type.WAIT_PROBE,"DEVICE_WAIT",PerfRunFacts.Reason.DEVICE_DISCONNECTED,elapsed(started));
            st.message="设备离线，等待重连 "+waited+"s / "+DEVICE_WAIT_MAX_SEC+"s";
            sleep(10000);waited+=10;
            if(waited>=DEVICE_WAIT_MAX_SEC){recordEvent(st,PerfRunFacts.Type.WAIT_FINISHED,"DEVICE_WAIT",PerfRunFacts.Reason.WAIT_TIMEOUT,elapsed(started));return false;}
        }
        if(wasDown)recordEvent(st,PerfRunFacts.Type.WAIT_FINISHED,"DEVICE_WAIT",PerfRunFacts.Reason.STOPPED,elapsed(started));
        return false;
    }
    private static long elapsed(long started){return Math.max(0,(System.nanoTime()-started)/1000000);}
    private void recordEvent(PerfRunState st,PerfRunFacts.Type type,String stage,PerfRunFacts.Reason reason,Long duration) {
        try{repo.facts().event(st.id,type,stage,st.currentLoop>0?st.currentGameIdx+1:null,st.currentLoop>0?st.currentLoop:null,reason,duration);}
        catch(Exception failure){st.evidenceFailureCount++;log.warn("run#{} 恢复时间线保存失败，事件类型 {}",st.id,type);}
    }

    /** 屏幕熄灭则唤醒（无锁屏密码，WAKEUP + dismiss-keyguard 即可恢复）。 */
    private void ensureScreenOn(PerfRunState st) {
        try {
            // 屏幕亮着就不用处理（最频繁的路径，先短路）
            if (adb.isScreenOn(st.device)) {
                return;
            }
            log.info("run#{} 屏幕熄灭，唤醒", st.id);
            st.message = "屏幕熄灭，唤醒中…";
            // 电源键唤醒 + 滑掉锁屏（测试机无锁屏密码，一次即可）
            adb.wakeScreen(st.device);
            sleep(1000); // 给系统反应时间
            if (!adb.isScreenOn(st.device)) {
                // 一次没醒：再试一次
                adb.wakeScreen(st.device);
                sleep(1000);
            }
        } catch (Exception e) {
            // 唤醒失败只警告：后续操作失败会走无效圈逻辑自愈
            log.warn("run#{} 屏幕检查/唤醒失败: {}", st.id, e.getMessage());
        }
    }

    // ---------- 正常模式单圈 ----------

    /** @return {@link LoopOutcome#OK} 有效圈；{@link LoopOutcome#INVALID} 无效圈。
     *  （本模式不走接口，不会返回 {@code ENV_FAILURE} —— 设备问题由 ensureDevice 处理） */
    /** 重复开关游戏的局内时长（秒）。只是给加载留的冗余，不代表真跑了一局。 */
    private static final int NORMAL_IN_GAME_SECONDS = 5;

    /**
     * 重复开关游戏（2026-10-12 改造，需求 #88）：**全链路改走接口**。
     *
     * <p>改造原因：这个模式原来靠模拟点击（点黄色按钮加人机 / 点开始 / 点结束 / 点清房），
     * 依赖每台设备、每个游戏都标定一遍坐标 —— 换个手机就可能整个失效，重新抓坐标也抓不到。
     * 而人机对局那条链路早就全是接口、**不需要任何坐标**。
     *
     * <p>现在与人机对局的链路完全一致（前置清房 → 点进游戏 → 推人机 → 推开局 → 待几秒 →
     * 推结束 → 推清房），唯一区别是**局内只待几秒**：那几秒只是给加载留的冗余，不算真跑局。
     * 于是它天然成为「进房与开局」这一段的放大器 —— 一圈几十秒，同样时间能跑几十倍圈数，
     * 判据是干净的：**若它照样稳定每圈涨，说明泄漏与游戏运行时长无关**。
     *
     * <p>风险边界：这条路径不再使用任何坐标，因此 {@code buttonGroupId} 传什么都不影响它
     * （启动时的标定校验也已为它跳过）。
     */
    private LoopOutcome runNormalLoop(PerfRunState st, long gameId, String gameName, int loopNo, int sampleSec) {
        return runAiRoomLoop(st, gameId, gameName, loopNo, NORMAL_IN_GAME_SECONDS, NORMAL_IN_GAME_SECONDS);
    }

    // ---------- 人机对局模式单圈（2026-10-06，需求 #68） ----------

    /**
     * 人机对局的一圈：房间与对局由接口推送驱动，真机只负责进房。
     *
     * <p><b>一圈的六步</b>：真机点进游戏 → 加入房间并准备 →（接口）推人机 →（接口）开局 →
     * 采样对局时长 →（接口）结束游戏并清空房间 → 回列表。
     *
     * <p><b>链路极简（#71 使用者点名）</b>：点进游戏后不做任何界面验证，直接
     * 推人机 → 推开局 → 待够时长 → 推结束 → 推清房。房间成员（含本机账号首位）
     * 全部来自接口名单；等大厅/点加入/等对局那些 UI 判定在 Unity 自绘游戏上全盲
     * （uiautomator 抓不到控件，30 秒必超时），已全部删除。
     *
     * <p><b>推人机 / 开局失败判无效圈</b>（走外层既有的连续无效跳过机制）：房间里人不对时采到的数据是
     * 「一个人单机跑」的性能，与「八个人机陪打」根本不是一回事，留在趋势里会把人带偏。
     * <b>收尾失败不废圈</b>：数据已经采完，圈本身有效，只是房间可能没清干净 ——
     * 那种情况记下来，下一圈的进房步骤自己去应付。
     *
     * <p><b>与 {@link #runNormalLoop} 的关系</b>：采样、统计与落库三段与它同构，但**刻意没有合并**。
     * 抽公共方法要动到已经在真机上跑通的老模式，而这几段（对局内采样、watchdog 兜底、全空采样兜底）
     * 恰好是机器单测覆盖不到、只有真机才能发现回归的地方。宁可留这一处重复：
     * 改这里时请对照 runNormalLoop 的同名步骤一起看。
     *
     * @return 三态见 {@link LoopOutcome}。**接口不可达时返回 {@code ENV_FAILURE} 并撤销本圈**，
     *         由 {@code runAll} 等环境恢复后重跑同一个圈号。
     */
    private LoopOutcome runAiRoomLoop(PerfRunState st, long gameId, String gameName, int loopNo, int sampleSec) {
        return runAiRoomLoop(st, gameId, gameName, loopNo, sampleSec, null);
    }

    /**
     * @param forcedSeconds 强制局内时长（秒）。非 null 时**不再被游戏配置的对局时长覆盖** ——
     *                      重复开关游戏靠它把局内压到几秒（那几秒只是加载冗余，不算真跑局）。
     */
    private LoopOutcome runAiRoomLoop(PerfRunState st, long gameId, String gameName, int loopNo,
            int sampleSec, Integer forcedSeconds) {
        long loopId = repo.insertLoop(st.id, gameId, loopNo);
        st.evidenceLoopId = loopId;
        if(st.plan!=null) repo.markLoopPlan(loopId,st.plan.hash());
        st.loopEvidenceMissing = false;
        PerfDeviceHealth.Snapshot startHealth=PerfDeviceHealth.read(adb,st.device);
        repo.updateBattery(loopId,startHealth.batteryLevel());
        var diagnostics=new PerfSamplingDiagnostics(startHealth,PerfDeviceHealth.Policy.of(st.plan==null?repo.getSettings(List.of(PerfDeviceHealth.TEMP_WARN,PerfDeviceHealth.THERMAL_WARN)):st.plan.settings()));
        PerfLoopLogWindow loopLogs=null;
        try {
            // 本游戏的推送身份（需求 #68）：注入用例里的 {{mg_id}} / {{app_id}}。
            // 没填的字段会被 overrideBody 跳过（null=不覆盖），那一项就用接口测试变量里的全局值。
            Map<String, Object> game = gameOf(st,gameId);
            if (game == null) {
                throw new LoopInvalid("游戏记录不存在");
            }
            Map<String, String> identity = new LinkedHashMap<>();
            identity.put("mg_id", strOrNull(game.get("mgId")));
            identity.put("app_id", strOrNull(game.get("appId")));
            // 房间号也按游戏走（#70）：变量表里那种写死的全局 room_id（联调值「3333」）就是
            // 「人机全进错房、开局 100500 房间或者玩家不存在」的元凶 —— 游戏配了，
            // 推送请求里的 {{room_id}} 就换成它的；没配维持全局值（向后兼容）
            identity.put("room_id", strOrNull(game.get("roomId")));

            // 推人机的按游戏配置（#70）：真人数 / 人机数 / 人机等级，人机数回退全局；
            // 模板配了就整段替换用例请求体。什么都没配时名单走原文（pushCase 里保证第 1 条真人）
            Integer gameHumans = game.get("humanCount") instanceof Number h ? h.intValue() : null;
            Integer gameAis = game.get("aiCount") instanceof Number n ? n.intValue() : null;
            Integer gameLevel = game.get("aiLevel") instanceof Number lv ? lv.intValue() : null;
            GamePushPlan plan = new GamePushPlan(gameHumans, gameAis != null ? gameAis : st.aiCount,
                    gameLevel, strOrNull(game.get("machineUid")), strOrNull(game.get("bodyOverride")));

            // 对局时长（#70）：游戏配了就以它为准 —— 本圈对局内采样与「多久后收尾」都跟着变。
            // watchdog 截止必须同步延长：它默认只有 4 分钟，5 分钟的局会被误判成「单圈超时」
            Integer gameSeconds = game.get("gameSeconds") instanceof Number gs ? gs.intValue() : null;
            if (forcedSeconds != null) {
                // 重复开关游戏：局内固定几秒，不被游戏配置的 400 秒拉长（2026-10-12 改造）
                sampleSec = forcedSeconds;
                setWatchdog(st,(forcedSeconds+WATCHDOG_EXTRA_SEC)*1000L);
                log.info("run#{} 重复开关游戏：局内固定 {}s（忽略游戏配置的对局时长 {}s）",
                        st.id, forcedSeconds, gameSeconds);
            } else if (gameSeconds != null && gameSeconds > 0) {
                sampleSec = gameSeconds;
                setWatchdog(st,(gameSeconds+WATCHDOG_EXTRA_SEC)*1000L);
                log.info("run#{} 游戏 {} 的对局时长按游戏配置 = {}s（watchdog 同步延长到 {}s）",
                        st.id, gameName, gameSeconds, gameSeconds + WATCHDOG_EXTRA_SEC);
            }

            // 0) 前置自愈（#75）：**无条件先清一次房**，消掉上一圈可能残留的房间状态。
            //    上一圈的收尾是「尽力而为」（失败不废圈，这是有意的），所以残留是常态而非意外：
            //    房间里若还留着上局的人，这一圈的「推人机」就是在错位状态上推 —— 即使网络正常也会失败。
            //    幂等、只多一次请求；失败只记日志不判无效，真正的问题会在推人机那一步自己暴露。
            ApiExec preClear = pushCase(st, st.aiCases.roomClear(), identity, "前置清房", null);
            if (!ApiExec.PASS.equals(preClear.verdict())) {
                log.warn("run#{} loop{} 前置清房未成功（继续往下走）：{}", st.id, loopNo, shortReason(preClear));
            }

            // 链路极简（使用者点名）：列表页点进游戏 → 推人机 → 推开局 → 待够时长 → 推结束 → 推清房。
            // 不等大厅、不点加入/准备、不等对局画面 —— 那些 UI 智能判断在 Unity 自绘游戏上
            // 全是盲的（uiautomator 抓不到控件，等 30 秒必超时），而房间成员本来就全部由接口推送。
            ensureOnListAndTapGame(st, gameName);
            sleep(2000); // 给游戏内页面一个加载时间，到此为止，不做任何界面验证

            Integer pid = collector.pidOf(st.device, resource(st,"perf_package"));
            if (pid == null) {
                throw new LoopInvalid("进程不存在");
            }

            // ① 接口：推人机（名单含本机账号首位——房间成员全部来自这份接口名单）
            //    带重试（#76）：单次网络抖动先自己吸收掉，别让它变成无效圈
            ApiExec pushAi = pushAiWithRetry(st, identity, plan);
            if (!ApiExec.PASS.equals(pushAi.verdict())) {
                // 网络类失败（#73）：本圈从没真正开始过 —— 撤销圈记录，交给外层等环境后重跑。
                // 不计连续无效，是这次改动最关键的一条：否则一次网络抖动就会吃掉整个游戏的配额。
                if (ApiExec.isNetworkFailure(pushAi)) {
                    repo.deleteLoop(loopId);
                    log.warn("run#{} loop{} 接口环境不可达，本圈撤销、待环境恢复后重跑：{}",
                            st.id, loopNo, shortReason(pushAi));
                    return LoopOutcome.ENV_FAILURE;
                }
                throw new LoopInvalid("推人机失败：" + shortReason(pushAi));
            }

            // 用只读锚点隔离本圈，避免清空其他功能正在查看的整机日志。
            loopLogs = beginLoopLogs(st);

            // ② 接口：开局
            ApiExec pushStart = pushCase(st, st.aiCases.gameStart(), identity, "开局", null);
            if (!ApiExec.PASS.equals(pushStart.verdict())) {
                if (ApiExec.isNetworkFailure(pushStart)) {
                    repo.deleteLoop(loopId);
                    log.warn("run#{} loop{} 接口环境不可达（开局），本圈撤销、待环境恢复后重跑：{}",
                            st.id, loopNo, shortReason(pushStart));
                    return LoopOutcome.ENV_FAILURE;
                }
                throw new LoopInvalid("开局失败：" + shortReason(pushStart));
            }

            // 仅新增来源取证，不影响对局状态、采样和推送指令。
            if (pushStart.metadata() != null) {
                try {
                    var source = json.readTree(pushStart.metadata());
                    if (source.hasNonNull("envId")) repo.markLoopApiEnvironment(loopId, source.get("envId").asLong(),
                            source.path("envName").asText(null), source.path("capturedAt").asText(null));
                } catch (Exception evidenceError) {
                    log.warn("run#{} loop{} 开局环境来源记录失败：{}", st.id, loopNo, evidenceError.getMessage());
                }
            }

            // 游戏内等待以实际经过时间为准；采样调用本身也有开销，不能用点数代替时长。
            // 点数只作为上限，到达预设时间后收尾。
            List<PerfCollector.Sample> samples = new ArrayList<>();
            int points = samplePoints(st,sampleSec);
            long intervalMs=Math.max(1,st.plan==null?props.perfSampleIntervalMs():st.plan.sampleIntervalMs());
            var schedule=new PerfSamplingSchedule(sampleSec*1000L,intervalMs);
            Long previousOffset=null;
            // 对局提前结束检测（#78）：关键字由使用者在面板上配，留空 = 不检测（与本版之前行为一致）。
            // 为什么需要它：整段采样是「真实时间墙」，不看游戏里发生了什么 —— 人机若提前打完，
            // 后面几百秒采到的是结算页的内存，会把整圈 PSS 均值稀释。
            String endKeyword = settingOf(st,PerfRepository.SETTING_AI_END_KEYWORD);
            boolean detectEarlyEnd = endKeyword != null && !endKeyword.isBlank();
            boolean earlyEnd = false;
            // 本圈对局的 roundId（v1.10.4 #86）：与提前结束检测**共用同一次 logcat 拉取**，不额外花时间
            String roundId = null;
            for (int i = 0; alive(st) && schedule.hasTime(); i++) {
                long delayMs=schedule.delayMillis();if(delayMs<0)break;if(delayMs>0)sleep(delayMs);
                if(!alive(st)||!schedule.hasTime())break;
                long offset=schedule.offsetMillis();
                PerfCollector.Sample s=continuousSample(st,pid,diagnostics,schedule.remainingMillis()).timed(offset,previousOffset==null?null:offset-previousOffset);
                previousOffset=offset;st.lastSample=s;samples.add(s);diagnostics.sample(s);
                if(diagnostics.continuity().changed()){diagnostics.event("PROCESS_CHANGED",offset);break;}
                // 每 EARLY_END_CHECK_EVERY 个点拉一段 logcat，一份日志两用：
                //   ① 命中结束关键字 → 本局已结束，提前收尾并标注（#78）
                //   ② 提取本局 roundId（#86）
                // 合成一次拉取是为了不把开销翻倍 —— 单次约 330ms，一圈几十次就够呛。
                if (PerfLoopLogWindow.shouldReadAt(i, EARLY_END_CHECK_EVERY)) {
                    String tail = readLoopLogs(st, loopLogs);
                    if (detectEarlyEnd && tail != null && tail.contains(endKeyword)) {
                        earlyEnd = true;
                        log.info("run#{} loop{} 检测到对局提前结束（关键字命中，已采 {}/{} 点），提前收尾",
                                st.id, loopNo, samples.size(), points);
                        st.message = "游戏 " + gameName + " 第 " + loopNo + " 圈（对局提前结束，已提前收尾）";
                    }
                    // 取循环里最后一次读到的值：尾部可能混着上一局的残留，最后出现的最可能是本局
                    String found = extractGameRoundId(tail);
                    if (found != null) {
                        roundId = found;
                    }
                    if (earlyEnd) {
                        break;
                    }
                }
                schedule.complete();
            }
            // 短圈、最后一个周期之后才到达的 ID 都有一次收尾前机会。
            String finalRound = extractGameRoundId(readLoopLogs(st, loopLogs));
            if (finalRound != null) roundId = finalRound;
            diagnostics.completed(schedule.offsetMillis(),intervalMs,schedule.skippedSlots());
            repo.insertSamples(loopId, samples);
            if(diagnostics.continuity().changed()){closeRoom(st,identity);throw new LoopInvalid("采样进程已变化，本圈数据不连续，未合并不同进程均值");}

            // watchdog 超时兜底（判据与正常模式一致：采样点数不足 + 已过截止 + 不是用户主动停）
            if (samples.size() < points && !stopped(st)
                    && watchdogExpired(st)) {
                log.warn("run#{} loop{} 单圈超时（watchdog {}s，采样 {}/{} 点），标 INVALID",
                        st.id, loopNo, LOOP_WATCHDOG_MS / 1000, samples.size(), points);
                // 超时退出前把对局收掉：开局已推成功的话，不收尾 = 人机继续在手机上打
                closeRoom(st, identity);
                captureInvalid(st, gameId, loopNo);
                repo.finishLoop(loopId, "INVALID",
                        "单圈超时（watchdog " + LOOP_WATCHDOG_MS / 1000 + "s）",
                        null, null, null, null, null, null, null, null, null);
                recoverToList(st);
                return LoopOutcome.INVALID;
            }
            // 全空采样圈：对局内所有采样点的核心指标都缺失 = 采样整体失败（设备掉线/进程消失）
            boolean allSamplesEmpty = samples.isEmpty() || samples.stream()
                    .allMatch(s -> s.pssKb() == null && s.cpuPercent() == null && s.threadCount() == null);
            if (allSamplesEmpty) {
                log.warn("run#{} loop{} 对局内采样全失败，标 INVALID", st.id, loopNo);
                // 停止/异常导致的提前退出：开局已推成功就补收尾（结束+清房），
                // 否则人机会在手机上继续打到自然结束 —— 表现成「停止了手机还在跑」
                if (ApiExec.PASS.equals(pushStart.verdict())) {
                    closeRoom(st, identity);
                }
                captureInvalid(st, gameId, loopNo);
                repo.finishLoop(loopId, "INVALID", "采样全失败（设备异常/进程消失）", null, null, null,
                        null, null, null, null, null, null);
                recoverToList(st);
                return LoopOutcome.INVALID;
            }

            // ⑤ 接口收尾：先结束游戏、再清空房间（顺序由使用者确认）。失败只记日志，不废掉本圈
            String closeNote = closeRoom(st, identity);
            String closeRound = extractGameRoundId(readLoopLogs(st, loopLogs));
            if (closeRound != null) roundId = closeRound;
            if (!closeNote.isEmpty()) {
                log.warn("run#{} loop{} 收尾未全部成功：{}", st.id, loopNo, closeNote);
                st.message = "游戏 " + gameName + " 第 " + loopNo + " 圈（收尾未全成功：" + closeNote + "）";
            }

            // 清房后返回游戏列表，避免下一圈从房间或结算页开始。
            // 等待结算界面过渡后退出；坐标必须来自用户配置或此前标定。
            for (int i = 0; i < 10 && !stopped(st); i++) {
                sleep(1000); // 等结算弹窗走完，回到房间页
            }
            // 按钮用 perf_exit_game（右上角那个退出）。最初误用 perf_stop_game —— 它的标定位置
            // 与「加入游戏」按钮重合，点下去等于把本机账号又加进房间。
            // （具体坐标不写进代码：那是设备相关配置，写在注释里等于把它提交进版本库。）
            int[] exit = inGameCoordOrNull(st, "perf_exit_game", "perf_exit_game_xy");
            if (exit != null && !stopped(st)) {
                adb.tap(st.device, exit[0], exit[1]);
                log.info("run#{} 已点右上角退出游戏（{},{}），回 app 游戏列表", st.id, exit[0], exit[1]);
            }
            sleep(3000); // 等回到列表页

            // ⑥ 回列表。已主动点过退出，这里只剩兜底职责（认得大厅 / 对局中 / app 不在前台）。
            recoverToList(st);
            sleep(3000); // 等 GC 沉降再采基线

            // ⑦ 退出后的「静止基线」：对比游戏内 vs 空闲时的资源开销
            PerfCollector.Sample baseline = null;
            try {
                baseline = continuousSample(st,pid,diagnostics,20000);
            } catch (Exception e) {
                log.warn("run#{} loop{} 基线采样失败（圈仍记 OK，基线置空）: {}", st.id, loopNo, e.getMessage());
            }
            if (baseline != null) {
                try {
                    repo.updateLoopBaselineDetail(loopId, baseline);
                } catch (Exception e) {
                    log.warn("run#{} loop{} 基线分项回填失败（不影响本圈有效性）: {}", st.id, loopNo, e.getMessage());
                }
                // 回收后基线（v1.10.0 #84）：开关打开时再采一个点，让判定能区分缓存与真泄漏。
                // 放在分项回填之后：两个基线口径一一对应，报告里可以逐分项对比。
                collectTrimmedBaseline(st, pid, loopId,diagnostics);
            }

            if(diagnostics.continuity().changed())throw new LoopInvalid("基线阶段进程已变化，本圈不参与连续趋势");
            // ⑧ 汇总统计并落库（与正常模式同构）：PSS 均值/峰值、CPU 单核与整机双口径、局内 ws 线程峰值
            long pssSum = 0;int pssPeak = 0, pssN = 0;Integer inGameWs = null;
            double cpuSum = 0, cpuPeak = 0;
            int cpuN = 0;
            double cpuSysSum = 0, cpuSysPeak = 0;
            int cpuSysN = 0;
            Integer loopCores = null;
            for (PerfCollector.Sample s : samples) {
                if (s.pssKb() != null) {
                    pssSum += s.pssKb();
                    pssPeak = Math.max(pssPeak, s.pssKb());
                    pssN++;
                }
                if (s.cpuPercent() != null) {
                    cpuSum += s.cpuPercent();
                    cpuPeak = Math.max(cpuPeak, s.cpuPercent());
                    cpuN++;
                    // 整机口径：核数每点单独读（热插拔分母跟随）；读不到就跳过该点，不猜分母
                    if (s.cpuCores() != null && s.cpuCores() > 0) {
                        double sys = s.cpuPercent() / s.cpuCores();
                        cpuSysSum += sys;
                        cpuSysPeak = Math.max(cpuSysPeak, sys);
                        cpuSysN++;
                        if (loopCores == null) {
                            loopCores = s.cpuCores();
                        }
                    }
                }
                if (s.wsThreadCount() != null) {
                    inGameWs = inGameWs==null?s.wsThreadCount():Math.max(inGameWs, s.wsThreadCount());
                }
            }
            repo.finishLoop(loopId, "OK", null,
                    baseline != null ? baseline.pssKb() : null,
                    baseline != null ? baseline.wsThreadCount() : null,
                    inGameWs,
                    pssN > 0 ? (int)(pssSum / pssN) : null, pssN > 0 ? pssPeak : null,
                    cpuN > 0 ? cpuSum / cpuN : null, cpuN > 0 ? cpuPeak : null,
                    null, null);
            if (cpuSysN > 0) {
                repo.updateLoopCpuSys(loopId, loopCores, cpuSysSum / cpuSysN, cpuSysPeak);
            }
            // 对局提前结束标记（#78）：数据照常记 OK（这一段采样是真实的），只是标注出来，
            // 让复核者能区分「满时长圈」与「提前结束圈」—— 后者的均值天然偏低，混在一起看会误导趋势
            if (earlyEnd) {
                repo.markLoopEarlyEnd(loopId);
            }
            // 本圈对局的 roundId（v1.10.4 #86）：取不到就不写，这一列为空不影响本圈有效性
            repo.markLoopRoundEvidence(loopId,loopLogs);
            return LoopOutcome.OK;
        } catch (LoopInvalid e) {
            // 自愈：无效圈可能因坐标缓存过期（app 更新/布局变），清缓存下圈重新学习
            calibration.clearCoords();
            captureInvalid(st, gameId, loopNo);
            repo.finishLoop(loopId, "INVALID", invalidReason(st, e), null, null, null,
                    null, null, null, null, null, null);
            recoverToList(st);
            return LoopOutcome.INVALID;
        } catch (Exception e) {
            log.warn("run#{} loop 异常", st.id, e);
            calibration.clearCoords();
            captureInvalid(st, gameId, loopNo);
            repo.finishLoop(loopId, "INVALID", invalidReason(st, new LoopInvalid("异常: " + e.getMessage())),
                    null, null, null, null, null, null, null, null, null);
            recoverToList(st);
            return LoopOutcome.INVALID;
        } finally {
            try{repo.saveLoopQuality(loopId,diagnostics.summary(PerfDeviceHealth.read(adb,st.device)));}catch(Exception storageFailure){markEvidenceMissing(st,"采样条件");}
            try {if(loopLogs!=null)repo.markLoopRoundEvidence(loopId,loopLogs);else repo.markLoopRoundMissing(loopId,"本圈在建立日志边界前已中断，未采集ID来源");}
            catch(Exception storageFailure) { markEvidenceMissing(st,"对局ID来源"); }
        }
    }

    // ---------- 接口环境故障的兜底（2026-10-09，v1.9.4 #73~#77） ----------

    /**
     * 推人机（需求 #76）：失败先自己重试 {@link #PUSH_RETRY_TIMES} 次，间隔按退避表递增。
     *
     * <p>为什么不把重试也加在开局与收尾上：开局和推人机打的是同一个地址，前者通了后者多半也通；
     * 收尾本身就是尽力而为、失败不废圈。重试只加在**决定本圈生死的那一步**上。
     *
     * @return 最后一次的执行结果（调用方按 verdict 决定走哪条路）
     */
    private ApiExec pushAiWithRetry(PerfRunState st, Map<String, String> identity, GamePushPlan plan)
            throws LoopInvalid {
        ApiExec exec = pushCase(st, st.aiCases.batch(), identity, "推人机", plan);
        for (int i = 0; i < PUSH_RETRY_TIMES && !ApiExec.PASS.equals(exec.verdict()) && !stopped(st); i++) {
            long wait = PUSH_RETRY_BACKOFF_MS[Math.min(i, PUSH_RETRY_BACKOFF_MS.length - 1)];
            log.warn("run#{} 推人机未通过（{}），{}ms 后重试第 {}/{} 次",
                    st.id, shortReason(exec), wait, i + 1, PUSH_RETRY_TIMES);
            recordEvent(st,PerfRunFacts.Type.RECOVERY_ATTEMPT,"RECOVERY",ApiExec.isNetworkFailure(exec)?PerfRunFacts.Reason.ENV_UNREACHABLE:PerfRunFacts.Reason.REQUEST_FAILED,wait);
            st.message = "推人机失败，重试第 " + (i + 1) + "/" + PUSH_RETRY_TIMES + " 次";
            sleep(wait);
            exec = pushCase(st, st.aiCases.batch(), identity, "推人机", plan);
        }
        return exec;
    }

    private PerfLoopLogWindow beginLoopLogs(PerfRunState st) {
        AdbClient.ExecResult snapshot = adb.logcatSnapshot(st.device);
        if (!snapshot.ok()) log.warn("run#{} 无法建立本圈日志边界，首个恢复快照仅用来重建边界", st.id);
        return new PerfLoopLogWindow(snapshot.ok() ? snapshot.out() : null);
    }

    private String readLoopLogs(PerfRunState st, PerfLoopLogWindow window) {
        try {
            AdbClient.ExecResult snapshot = adb.logcatSnapshot(st.device);
            if (!snapshot.ok()) { window.failedRead(); return ""; }
            boolean wasIncomplete = window.incomplete();
            String fresh = window.accept(snapshot.out());
            if (!wasIncomplete && window.incomplete()) {
                log.warn("run#{} 本圈日志锚点失效，本次整段已丢弃，仅接受重建边界后的新增日志", st.id);
            }
            return fresh;
        } catch (Exception e) {
            window.failedRead();
            log.debug("run#{} 读取本圈日志失败: {}", st.id, e.getMessage());
            return "";
        }
    }

    /**
     * 从 logcat 片段里取对局 roundId（2026-10-10，v1.10.4 需求 #86）。
     *
     * <p>为什么要它：圈号是我们自己数的，只在报告内部有意义；roundId 才是跟服务端对局记录
     * 对得上的那个标识。有了它，报告里任何一圈的异常都能直接拿去查服务端的对局数据。
     *
     * <p>兼容的文本提取器返回最后一次命中；真实圈持久化必须使用 PerfRoundIdEvidence 的唯一性与边界判据，不能把最后一条当归属证据。
     *
     * @return 取到的 roundId；没命中返回 null（宁可缺，不要猜）
     */
    static String extractGameRoundId(String log) {
        if (log == null || log.isEmpty()) {
            return null;
        }
        java.util.regex.Matcher m = GAME_ROUND_ID_PATTERN.matcher(log);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    /**
     * 环境探活（需求 #74）：拿「推人机」那条用例的地址做一次 **TCP 连接探测**。
     *
     * <p>不发业务请求、不落库 —— 每 5 秒往被压测环境打一条业务指令去探活是副作用，不是探测。
     */
    private boolean probeEnvironment(PerfRunState st) {
        if (apiRunner == null || apiRepo == null || st.aiCases == null) {
            return false;
        }
        try {
            if(st.plan==null) return apiRunner.probeReachability(apiRepo.getCase(st.aiCases.batch()), ENV_PROBE_TIMEOUT_MS);
            var request=st.plan.requests().values().stream().filter(r -> r.spec().id()==st.aiCases.batch()).findFirst().orElseThrow();
            var uri=java.net.URI.create(com.sudpanel.api.ApiRequestEngine.build(request.spec(),request.environment(),null,Map.of()).url());
            try(var socket=new java.net.Socket()) { socket.connect(new java.net.InetSocketAddress(uri.getHost(),uri.getPort()>0?uri.getPort():"https".equals(uri.getScheme())?443:80),ENV_PROBE_TIMEOUT_MS); return true; }
        } catch (Exception e) {
            log.debug("run#{} 环境探活异常: {}", st.id, e.getMessage());
            return false;
        }
    }

    /**
     * 采「触发系统内存回收之后的基线」（2026-10-10，v1.10.0 #84）。
     *
     * <p>在常规关后基线之外，再采集一次系统回收后的内存，观察可回收部分。
     * 两组基线用于辅助解释增长，不能单凭差值确认或排除泄漏。
     *
     * <p><b>默认勾选</b>：
     * 只有显式存过 "false" 才跳过 —— 这样「没配过」与「配过又打开」都是开，
     * 只有用户主动取消勾选才会关掉。回收会改变被测 App 的状态，取消勾选即回到旧口径。
     *
     * <p>失败只记日志：第二套基线是加分项，采不到就退回原来的单基线口径，绝不因此废掉本圈。
     */
    private PerfCollector.Sample continuousSample(PerfRunState st,int pid,PerfSamplingDiagnostics diagnostics,long budgetMs){
        long begin=System.nanoTime();var probe=collector.probeProcess(st.device,resource(st,"perf_package"),pid,Math.max(1,(budgetMs+999)/1000));
        boolean same=diagnostics.continuity().observe(probe,null);long spent=(System.nanoTime()-begin)/1000000;
        if(!same||probe==null||!"PRESENT".equals(probe.state())||spent>=budgetMs)return collector.missing(pid,!same?"进程已变化":probe!=null&&"ABSENT".equals(probe.state())?"进程暂时消失":"进程读取失败或截止时间已到",spent);
        var sample=collector.sample(st.device,pid,Math.max(1,(budgetMs-spent+999)/1000));
        if(sample==null)sample=collector.missing(pid,"采样器未返回数据",(System.nanoTime()-begin)/1000000);
        diagnostics.continuity().observe(probe,sample);
        var q=sample.quality();if(q!=null)sample=sample.withQuality(new PerfCollector.Quality(q.version(),q.pid(),(System.nanoTime()-begin)/1000000,q.offsetMs(),q.intervalMs(),q.processStartTicks(),q.sources()));
        return sample;
    }
    private void collectTrimmedBaseline(PerfRunState st,Integer pid,long loopId){collectTrimmedBaseline(st,pid,loopId,null);}
    private void collectTrimmedBaseline(PerfRunState st, Integer pid, long loopId,PerfSamplingDiagnostics diagnostics) {
        String flag = settingOf(st,PerfRepository.SETTING_PERF_TRIM_BASELINE);
        if ("false".equalsIgnoreCase(flag == null ? "" : flag.trim()) || pid == null) {
            return;
        }
        try {
            // COMPLETE = 最高级别的回收请求（等价于「进程即将被切到后台且内存吃紧」）。
            // 用最高级别是为了把「可回收但没回收」的部分尽量逼出来，让两个基线之差更有意义。
            if(diagnostics!=null){if(diagnostics.continuity().changed())return;var probe=collector.probeProcess(st.device,resource(st,"perf_package"),pid);boolean same=diagnostics.continuity().observe(probe,null);if(!same||probe==null||!"PRESENT".equals(probe.state()))return;}
            adb.shell(st.device, "am send-trim-memory " + pid + " COMPLETE", 10);
            sleep(3000); // 等 App 走完 onTrimMemory 的资源释放
            PerfCollector.Sample s = diagnostics==null?collector.sample(st.device,pid):continuousSample(st,pid,diagnostics,20000);
            if (s != null) {
                repo.markLoopTrimmedBaseline(loopId, s);
            }
        } catch (Exception e) {
            log.warn("run#{} loop{} 回收后基线采集失败（退回单基线口径）: {}", st.id, loopId, e.getMessage());
        }
    }

    /**
     * 环境等待（需求 #74）：接口不可达时进入，每 {@link #ENV_PROBE_INTERVAL_MS} 探活一次。
     *
     * <p>与设备掉线的 {@link #ensureDevice} 同构，唯一的区别是探活方式不碰业务接口。
     *
     * @return true = 环境已恢复（调用方应重跑本圈）；false = 等待超限或用户已停止
     *         （超限时本方法已把 run 置为 ABORTED，可续跑）
     */
    private boolean waitForEnvironment(PerfRunState st) {
        long started=System.nanoTime();int waited=0;
        synchronized(st){if(stopped(st))return false;st.status="WAIT_ENV";}
        recordEvent(st,PerfRunFacts.Type.WAIT_STARTED,"ENV_WAIT",PerfRunFacts.Reason.ENV_UNREACHABLE,null);
        while(!stopped(st)&&waited<ENV_WAIT_MAX_SEC){
            st.message="接口环境不可达，等待恢复 "+waited+"s / "+ENV_WAIT_MAX_SEC+"s";
            sleep(ENV_PROBE_INTERVAL_MS);waited+=(int)(ENV_PROBE_INTERVAL_MS/1000);
            if(stopped(st))break;
            boolean ready=probeEnvironment(st);
            recordEvent(st,PerfRunFacts.Type.WAIT_PROBE,"ENV_WAIT",ready?PerfRunFacts.Reason.ENV_REACHABLE:PerfRunFacts.Reason.ENV_UNREACHABLE,elapsed(started));
            synchronized(st){if(stopped(st))break;if(ready){st.status="RUNNING";st.message="接口环境已恢复，重跑当前圈";recordEvent(st,PerfRunFacts.Type.WAIT_FINISHED,"ENV_WAIT",PerfRunFacts.Reason.ENV_REACHABLE,elapsed(started));return true;}}
        }
        synchronized(st){if(!stopped(st)){st.status="ABORTED";st.message="接口环境等待超限，中止（可续跑）";}}
        recordEvent(st,PerfRunFacts.Type.WAIT_FINISHED,"ENV_WAIT","STOPPED".equals(st.status)?PerfRunFacts.Reason.STOPPED:PerfRunFacts.Reason.WAIT_TIMEOUT,elapsed(started));
        return false;
    }

    /**
     * 推人机这一步的按游戏配置（#69 起，#70 扩成四项）。字段都可为 null：
     * {@code humans}=没配（按 1 兜底，#69 口径）；{@code ais}=游戏和全局都没配
     * （名单走用例原文 + 首条真人）；{@code level}=没配（人机的 ai_level 沿用样板原值）；
     * {@code bodyOverride}=没配（用用例原文的请求体）。
     */
    private record GamePushPlan(Integer humans, Integer ais, Integer level,
                                String machineUid, String bodyOverride) {
    }

    /**
     * 推一条接口指令（需求 #68）。用例不存在算无效圈 —— 那是配置问题，不是网络问题。
     *
     * @param plan 仅推人机那一步用；其余步骤传 null。名单决策两段式：
     *             配了任一数量（游戏或全局）→ 按「真人+人机+等级」生成；
     *             什么都没配 → 用例原文，且第 1 条强制真人（is_ai=0，#69 口径）
     * @return 执行结果；**不抛失败**，由调用方按 verdict 决定怎么处理
     */
    private ApiExec pushCase(PerfRunState st, long caseId, Map<String, String> identity,
                             String stepName, GamePushPlan plan) throws LoopInvalid {
        try {
            var frozen = st.plan == null ? null : st.plan.requests().values().stream().filter(r -> r.spec().id()==caseId).findFirst().orElseThrow();
            ApiSpec spec = frozen == null ? apiRepo.getCase(caseId) : frozen.spec();
            if (spec == null) {
                throw new LoopInvalid(stepName + "的用例 #" + caseId + " 不存在（可能已被删除）");
            }
            String finalBody = spec.body();
            if (plan != null) {
                if (plan.bodyOverride() != null) {
                    // ① 自己填了推送体 → **完全按填的推**（2026-10-12 使用者要求）。
                    //
                    //    关键在「不再往下走名单生成」：原实现填完这份 JSON 之后，第二步还会按
                    //    「真人 + 人机 + 等级」把名单重写一遍，等于把使用者填的名单又覆盖掉 ——
                    //    现象就是「我明明填了，推出去的却不是我填的那份」。其余字段（人机数 / 等级 /
                    //    本机玩家 id）同理不再参与：填了 JSON 就意味着这份 JSON 说了算。
                    //
                    //    占位符（{{mg_id}} / {{room_id}} 等）不在这一步处理，照常由后面的变量注入完成 ——
                    //    使用者的 JSON 里通常就写着占位符，禁掉它反而会把 {{...}} 原样推出去。
                    spec = withBody(spec, plan.bodyOverride());
                    finalBody = plan.bodyOverride();
                    log.info("run#{} {}：使用自定义推送体（{} 字符），不再生成名单",
                            st.id, stepName, plan.bodyOverride().length());
                } else {
                    // ② 名单决策（只在没填推送体时走）。改写失败也要说一句：使用者明明配了，
                    //    实际却按原文推 —— 事后只看结果分不出这两种情况
                    AiPlayers.Applied applied;
                    if (plan.ais() != null || plan.humans() != null) {
                        try {
                            AiPlayers.requireValidCounts(plan.humans() != null ? plan.humans() : 1,
                                    plan.ais() != null ? plan.ais() : 0);
                        } catch (IllegalStateException invalidCounts) {
                            throw new LoopInvalid(invalidCounts.getMessage());
                        }
                        applied = AiPlayers.withRoster(spec.body(), new AiPlayers.RosterSpec(
                                plan.humans() != null ? plan.humans() : 1,
                                plan.ais() != null ? plan.ais() : 0,
                                plan.level(), plan.machineUid()));
                    } else {
                        applied = AiPlayers.forceFirstHuman(spec.body());
                    }
                    if (applied.applied()) {
                        spec = withBody(spec, applied.body());
                    }
                    finalBody = applied.body();
                    String roster = AiPlayers.summarize(finalBody);
                    log.info("run#{} {}：{}，{}", st.id, stepName, applied.detail(),
                            roster != null ? "推送名单=" + roster : "名单摘要取不到");
                }
            }
            if (plan != null) {
                try { AiPlayers.requireValidBodyCount(finalBody); }
                catch (IllegalStateException invalidCounts) { throw new LoopInvalid(invalidCounts.getMessage()); }
            }
            ApiExec exec = frozen == null ? apiRunner.execute(spec, identity, null, 0, 0) : apiRunner.executeWithEnvironment(spec,frozen.environment().withSessionScope("perf-run-"+st.id),identity,null,0,0);
            recordStepEvidence(st, caseId, stepName, exec);
            log.info("run#{} {}：用例#{}「{}」判定={} {}",
                    st.id, stepName, caseId, spec.displayName(), exec.verdict(),
                    ApiExec.PASS.equals(exec.verdict()) ? "" : shortReason(exec));
            return exec;
        } catch (LoopInvalid | RuntimeException failure) {
            ApiExec failed = new ApiExec(null, null, caseId, stepName, st.currentLoop, 0,
                    null, null, null, null, null, ApiExec.ERROR, failure.getMessage(), 0L,
                    null, null, null, null, failure.getClass().getSimpleName(), Instant.now().toString(), null, null);
            recordStepEvidence(st, caseId, stepName, failed);
            throw failure;
        }
    }


    /**
     * 一局的收尾：先结束游戏、再清空房间（顺序是使用者确认过的）。
     *
     * <p>两条都**尽力而为**，返回发生了哪些失败（空串＝都成功）。
     * 不抛异常是有意的：此刻对局数据已经采完，圈本身有效；
     * 因为清房没成功就把这一圈丢掉，等于把已经测到的数据扔了。
     */
    private String closeRoom(PerfRunState st, Map<String, String> identity) {
        StringBuilder note = new StringBuilder();
        try {
            ApiExec end = pushCase(st, st.aiCases.gameEnd(), identity, "结束游戏", null);
            if (!ApiExec.PASS.equals(end.verdict())) {
                note.append("结束游戏：").append(shortReason(end));
            }
        } catch (Exception e) {
            note.append("结束游戏：").append(e.getMessage());
        }
        try {
            ApiExec clear = pushCase(st, st.aiCases.roomClear(), identity, "清空房间", null);
            if (!ApiExec.PASS.equals(clear.verdict())) {
                if (note.length() > 0) {
                    note.append("；");
                }
                note.append("清空房间：").append(shortReason(clear));
            }
        } catch (Exception e) {
            if (note.length() > 0) {
                note.append("；");
            }
            note.append("清空房间：").append(e.getMessage());
        }
        if (evidence != null && st.evidenceLoopId != null) {
            try {
                evidence.cleanup(st.id, st.evidenceLoopId, evidenceGameId(st),
                        note.isEmpty() ? "SUCCESS" : "FAILED", note.toString(), st.loopEvidenceMissing);
            } catch (Exception storageFailure) { markEvidenceMissing(st, "清房结果"); }
        }
        return note.toString();
    }

    private Long evidenceGameId(PerfRunState st) {
        return st.currentGameIdx >= 0 && st.currentGameIdx < st.gameIds.size()
                ? st.gameIds.get(st.currentGameIdx) : null;
    }

    private void recordStepEvidence(PerfRunState st, long caseId, String step, ApiExec exec) {
        if (evidence == null) return; // 旧构造仅用于不带数据库取证的纯单元测试。
        try { evidence.record(st.id, st.evidenceLoopId, evidenceGameId(st), st.currentLoop, step, exec); }
        catch (Exception storageFailure) { markEvidenceMissing(st, step); }
    }

    private void markEvidenceMissing(PerfRunState st, String step) {
        st.loopEvidenceMissing = true;
        st.evidenceFailureCount++;
        log.warn("run#{} loop{} {}证据写入失败，已执行的业务结果保持不变", st.id, st.currentLoop, step);
    }

    /** 把执行结果压成一句可读的失败原因（优先业务原因，其次异常信息）。 */
    private static String shortReason(ApiExec exec) {
        String r = exec.reason();
        if (r == null || r.isBlank()) {
            r = exec.error();
        }
        if (r == null || r.isBlank()) {
            r = "判定为 " + exec.verdict() + "，但没给出原因";
        }
        return r.length() > 160 ? r.substring(0, 160) + "…" : r;
    }

    /** 换掉用例的请求体（按数量重排人机名单后用）。ApiSpec 是 record，只能新建一个。 */
    private static ApiSpec withBody(ApiSpec s, String body) {
        return new ApiSpec(s.id(), s.name(), s.method(), s.url(), s.headers(), body, s.note(),
                s.expectStatus(), s.expectCode(), s.timeoutMs(), s.insecure(), s.enabled(),
                s.groupId(), s.asserts(), s.options());
    }

    /** Object → String，null/空白都归一成 null（推送身份的「没填」语义）。 */
    private static String strOrNull(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o);
        return s.isBlank() ? null : s;
    }

    /** 手动重算聚合判定：对某 run 的全部游戏重新跑机器初判（覆盖旧结果）。
     *  历史 run（含 ABORTED/STOPPED/COMPLETED）都能用——数据在 DB，判定是纯函数。
     *  @return 各游戏判定结果数 */
    public Map<String, Object> rejudge(long runId) {
        // 2026-09-28 修复 #3：原实现直接写 repo.getRun(runId).get(...)，且**调了两次**。
        // run 不存在时抛 NPE（→ Spring 默认 500，前端读不到任何原因）；存在时也多查一次库。
        // 这里取一次并显式判空，抛带语义的异常（由 GlobalExceptionHandler 统一转成 JSON message）。
        Map<String, Object> runMeta = repo.getRun(runId);
        if (runMeta == null) {
            throw new IllegalStateException("运行不存在: " + runId);
        }
        List<Long> gameIds = new ArrayList<>();
        String queue = runMeta.get("gameQueue") == null ? "" : (String) runMeta.get("gameQueue");
        for (String s : queue.split(",")) {
            try {
                gameIds.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignore) {
            }
        }
        if (gameIds.isEmpty()) {
            return Map.of("ok", false, "message", "该 run 无游戏队列");
        }
        var originalPlan=repo.plan(runId);
        var frozenJudge=originalPlan==null?judge:new PerfJudge(originalPlan.judgeProperties(props));
        int total = 0;
        // 与 runAll 一致地维护「前序游戏结束水位」（v1.2 #16），保证 rejudge 与首次判定的证据串同构
        Double priorGameEndBaselineKb = null;
        for (Long gameId : gameIds) {
            if (repo.countLoops(runId, gameId) <= 0) {
                continue;
            }
            repo.deleteChecks(runId, gameId);
            List<Map<String, Object>> loops = repo.loopsOf(runId, gameId);
            for (PerfJudge.CheckOutcome c : frozenJudge.judgeNormal(loops, priorGameEndBaselineKb)) {
                repo.insertCheckResult(runId, gameId, c.checkNo(), c.checkName(),
                        c.verdict().name(), c.evidence());
                total++;
            }
            priorGameEndBaselineKb = lastOkBaselineKb(loops);
        }
        log.info("run#{} 手动重算聚合判定完成（{} 个游戏 {} 条结论）", runId, gameIds.size(), total);
        return Map.of("ok", true, "games", gameIds.size(), "checks", total);
    }

    /**
     * 取一组圈中**最后一个有效圈的关后基线**（kB）；没有有效圈时返回 null。
     *
     * <p>用于 v1.2 #16 的跨游戏水位传递：把它作为下一个游戏的「前序游戏结束水位」传给判定层，
     * 判定层据此在证据串里标注「本游戏是在什么水位上起跑的」。
     */
    private static Double lastOkBaselineKb(List<Map<String, Object>> loops) {
        for (int i = loops.size() - 1; i >= 0; i--) {
            Map<String, Object> l = loops.get(i);
            if ("OK".equals(l.get("status")) && l.get("baselinePssKb") instanceof Number n) {
                return n.doubleValue();
            }
        }
        return null;
    }

    /**
     * 无效圈截图留证：INVALID 圈把设备当前屏幕存成 PNG（data/screenshots/），供事后复盘现场。
     *
     * <p>文件名同时包含 run、gameId 和圈号，避免同一 run 中不同游戏的相同圈号互相覆盖。
     */
    // 截图失败不影响流程（只记日志）：现场证据是加分项，不能因截图阻塞压测主循环
    private void captureInvalid(PerfRunState st, long gameId, int loopNo) {
        try {
            Path db = Path.of(props.dbPath());
            Path dir = db.getParent() != null
                    ? db.getParent().resolve("screenshots")
                    : Path.of("data", "screenshots");
            String path = dir.resolve("run" + st.id + "-game" + gameId + "-loop" + loopNo + ".png").toString();
            boolean ok = adb.screencap(st.device, path);
            var logSnapshot=adb.logcatSnapshot(st.device);
            if(logSnapshot!=null&&logSnapshot.ok()) {
                var lines=logSnapshot.out().lines().toList();String bounded=String.join("\n",lines.subList(Math.max(0,lines.size()-200),lines.size()));
                if(bounded.length()>32000)bounded=bounded.substring(bounded.length()-32000);
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Files.writeString(dir.resolve("run"+st.id+"-game"+gameId+"-loop"+loopNo+".log"),"本机设备尾部有限日志；不能据此证明完整圈归属。\n"+bounded);
            }
            log.info("run#{} 圈{} 无效留证：{}（{}）", st.id, loopNo, path, ok ? "已保存" : "截图失败");
        } catch (Exception e) {
            log.warn("run#{} 圈{} 截图失败: {}", st.id, loopNo, e.getMessage());
        }
    }

    /** 中断推送：run 中止（设备掉线超时 ABORTED / 引擎异常 ERROR）时向 webhook 推一条文本。
     *  未配置 notifyWebhook 时静默跳过（本地工具可不配）；失败只记日志，绝不阻塞收尾流程。 */
    private void notify(String text) {
        String url = props.notifyWebhook();
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            // 飞书自定义机器人 text 消息格式：{"msg_type":"text","content":{"text":"..."}}
            String escaped = text.replace("\\", "\\\\").replace("\"", "\\\"");
            String body = "{\"msg_type\":\"text\",\"content\":{\"text\":\"" + escaped + "\"}}";
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .timeout(java.time.Duration.ofSeconds(5))
                    .build();
            java.net.http.HttpClient.newHttpClient()
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            log.info("中断推送已发送：{}", text);
        } catch (Exception e) {
            log.warn("中断推送失败: {}", e.getMessage());
        }
    }

    /** 电量监控：读设备当前电量（%）。dumpsys battery 的 level 行解析；失败返回 null 不影响主流程。 */
    private Integer batteryLevel(String serial) {
        try {
            String out = adb.shell(serial, "dumpsys battery | grep 'level:'", 10).out();
            Matcher m = Pattern.compile("level:\\s*(\\d+)").matcher(out);
            return m.find() ? Integer.parseInt(m.group(1)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 无效原因：超 watchdog 时标注，便于事后区分卡死和普通按钮未出现。 */
    private static void setWatchdog(PerfRunState st,long durationMs){st.loopDeadline=System.currentTimeMillis()+durationMs;st.loopDeadlineNanos=System.nanoTime()+durationMs*1000000;}
    private static boolean watchdogExpired(PerfRunState st){return st.loopDeadlineNanos==Long.MAX_VALUE?System.currentTimeMillis()>st.loopDeadline:System.nanoTime()>=st.loopDeadlineNanos;}

    private String invalidReason(PerfRunState st, LoopInvalid e) {
        // 若当前时刻已过本圈 watchdog 截止时间，说明是卡死超时而非普通按钮未出现：标注出来便于事后区分
        return watchdogExpired(st)
                ? "单圈超时（watchdog " + LOOP_WATCHDOG_MS / 1000 + "s）: " + e.getMessage()
                : e.getMessage();
    }

    /** 采样秒数 → 采样点数（按 perfSampleIntervalMs 换算，向上取整保证至少采到满时长）。 */
    private int samplePoints(PerfRunState st, int seconds) {
        // 采样间隔至少 1ms：防止配置异常导致除零
        long interval = Math.max(1, (st.plan==null?props.perfSampleIntervalMs():st.plan.sampleIntervalMs()));
        // 向上取整：保证采样总时长 >= 指定秒数（宁可多采一点，不漏采）
        int points = (int) Math.ceil(seconds * 1000.0 / interval);
        return Math.max(1, points);
    }

    // ---------- 异常时序模式单圈（检测项 2）----------

    /** 异常时序模式单圈（检测项 2：极早期结算稳定性）。
     *  流程：进大厅 → 加AI → 准备 → 点开始 → 延迟 earlyDelay 后尽快点「结束游戏」→
     *  抓 logcat 崩溃关键字 → 查能否回大厅。模拟"开局瞬间玩家就退出"的最恶劣时序。
     * @return {@link LoopOutcome#OK} 有效圈；{@link LoopOutcome#INVALID} 无效圈
     *  （本模式不走接口，不会返回 {@code ENV_FAILURE}） */
    private LoopOutcome runEarlySettleLoop(PerfRunState st, long gameId, String gameName, int loopNo, long earlyDelay) {
        // 先落库圈记录，与正常模式一致
        long loopId = repo.insertLoop(st.id, gameId, loopNo);
        st.evidenceLoopId = loopId;
        if(st.plan!=null) repo.markLoopPlan(loopId,st.plan.hash());
        st.loopEvidenceMissing = false;
        // 电量监控：记录本圈开始时的设备电量（失败静默，不影响流程）
        PerfDeviceHealth.Snapshot startHealth=PerfDeviceHealth.read(adb,st.device);
        repo.updateBattery(loopId,startHealth.batteryLevel());
        var diagnostics=new PerfSamplingDiagnostics(startHealth,PerfDeviceHealth.Policy.of(st.plan==null?repo.getSettings(List.of(PerfDeviceHealth.TEMP_WARN,PerfDeviceHealth.THERMAL_WARN)):st.plan.settings()));
        PerfLoopLogWindow loopLogs=null;
        try {
            // 与正常模式相同的前置：回列表 → 点游戏 → 等大厅
            ensureOnListAndTapGame(st, gameName);
            waitLobby(st);
            tapLobbyFlow(st, true); // 加入→加AI→准备，不点开始

            // 只读本圈日志边界，历史崩溃记录不参与本轮判定。
            loopLogs = beginLoopLogs(st);
            tapByRid(st, "app_common_self_playing"); // 开始游戏
            // 记录点开始时刻，用于计算实际延迟
            long t0 = System.currentTimeMillis();
            // 按配置等待（模拟"开局后马上想退"的最恶劣时序）
            sleep(earlyDelay);

            // 加载中尽快点「结束游戏」：对局内 dump 必失败，坐标走「分组优先、回落固定坐标」
            int[] stopBtn = inGameCoord(st, "perf_stop_game", "perf_stop_game_xy");
            adb.tap(st.device, stopBtn[0], stopBtn[1]);
            log.info("run#{} early-settle 点结束游戏（加载中）", st.id);
            // 实际延迟 = 点开始到点结束的真实间隔（与配置值对比可验证时序是否达标）
            long actualDelay = System.currentTimeMillis() - t0;
            // 给崩溃检测/界面恢复留出时间
            sleep(2000);

            // 崩溃关键字
            // 拉取本次对局的 logcat 全文
            String log = readLoopLogs(st, loopLogs);
            List<String> crashHits = new ArrayList<>();
            // 逐行扫描 logcat，匹配崩溃特征
            for (String line : log.split("\n")) {
                for (Pattern p : CRASH_PATTERNS) {
                    if (p.matcher(line).find()) {
                        // 命中：收集该行（超过 200 字符截断，防止刷屏日志占内存）
                        crashHits.add(line.length() > 200 ? line.substring(0, 200) : line);
                        break; // 一行只算一次命中
                    }
                }
                if (crashHits.size() >= 10) {
                    break; // 最多收集 10 条，足够判定
                }
            }
            // 能否回大厅
            // 轮询 10s 查「加入游戏」按钮是否重新出现：出现 = 能回大厅
            boolean recovered = pollForRid(st, "app_common_self_in", 10);

            // 判定：有崩溃 或 回不到大厅 → FAIL；两者都正常 → PASS
            PerfJudge.Verdict verdict = (!crashHits.isEmpty() || !recovered)
                    ? PerfJudge.Verdict.FAIL : (loopLogs.incomplete() ? PerfJudge.Verdict.N_A : PerfJudge.Verdict.PASS);
            // 组装证据 JSON：实际延迟、崩溃命中数、崩溃行列表、能否回大厅
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("actualDelayMs", actualDelay);
            evidence.put("crashHitCount", crashHits.size());
            evidence.put("crashHits", crashHits);
            evidence.put("recoveredToLobby", recovered);
            evidence.put("logEvidenceComplete", !loopLogs.incomplete());
            // 落库圈结果：状态 OK（流程走通），带判定结论与证据 JSON
            repo.finishLoop(loopId, "OK", null, null, null, null, null, null, null, null,
                    verdict.name(), json.writeValueAsString(evidence));
            recoverToList(st);
            return LoopOutcome.OK;
        } catch (LoopInvalid e) {
            // 无效圈：清坐标缓存（按钮可能因界面状态没出现），落库 INVALID
            calibration.clearCoords();
            captureInvalid(st, gameId, loopNo);
            repo.finishLoop(loopId, "INVALID", invalidReason(st, e), null, null, null,
                    null, null, null, null, null, null);
            recoverToList(st);
            return LoopOutcome.INVALID;
        } catch (Exception e) {
            // 兜底异常：同样按无效圈处理
            log.warn("run#{} early-settle loop 异常", st.id, e);
            calibration.clearCoords();
            captureInvalid(st, gameId, loopNo);
            repo.finishLoop(loopId, "INVALID", invalidReason(st, new LoopInvalid("异常: " + e.getMessage())),
                    null, null, null, null, null, null, null, null, null);
            recoverToList(st);
            return LoopOutcome.INVALID;
        } finally {
            try{repo.saveLoopQuality(loopId,diagnostics.summary(PerfDeviceHealth.read(adb,st.device)));}catch(Exception storageFailure){markEvidenceMissing(st,"采样条件");}
            try {if(loopLogs!=null)repo.markLoopRoundEvidence(loopId,loopLogs);else repo.markLoopRoundMissing(loopId,"本圈在建立日志边界前已中断，未采集ID来源");}
            catch(Exception storageFailure){markEvidenceMissing(st,"对局ID来源");}
        }
    }

    // ---------- 设备动作原语 ----------

    // 圈级业务异常：抛出即代表"本圈无效"（按钮未出现/加载失败/超时/进程不在等），由外层转成 INVALID 圈落库
    private static class LoopInvalid extends Exception {
        LoopInvalid(String msg) {
            super(msg);
        }
    }

    /** 向下滚动列表：按当前设备的有效尺寸从下向上滑动。 */
    private void swipeDown(String serial) {
        swipe(serial, true);
    }

    /** 向上滚动列表：按当前设备的有效尺寸从上向下滑动。 */
    private void swipeUp(String serial) {
        swipe(serial, false);
    }

    /** 每次读取布局，避免旋转或分辨率变化后继续使用旧坐标；未知时不执行滑动。 */
    private void swipe(String serial, boolean down) {
        PerfDeviceLayout layout = PerfDeviceLayout.read(adb, serial);
        if (!layout.known()) {
            throw new IllegalStateException("无法确认设备屏幕尺寸或方向，已取消滑动");
        }
        // wm size 给出自然方向的尺寸；90/270度旋转后交换有效宽高。
        boolean rotated = layout.rotation() == 1 || layout.rotation() == 3;
        int width = rotated ? layout.height() : layout.width();
        int height = rotated ? layout.width() : layout.height();
        if (width < 2 || height < 2) {
            throw new IllegalStateException("设备屏幕尺寸无效，已取消滑动");
        }
        int x = width / 2;
        int top = height / 5;
        int bottom = (int) ((long) height * 4 / 5);
        int startY = down ? bottom : top;
        int endY = down ? top : bottom;
        adb.shell(serial, "input swipe " + x + " " + startY + " " + x + " " + endY + " 300", 10);
    }

    /** 线程休眠；被中断时恢复中断标志位（不吞掉中断信号）。 */
    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 按按钮 key 点击：优先用已学习/已标定的坐标直点（快路径）；没有缓存则 dump 轮询定位（慢路径）并学习坐标。
     * @throws LoopInvalid 8 秒内按钮未出现时抛（本圈判无效） */
    private void tapByRid(PerfRunState st, String key) throws LoopInvalid {
        // 快路径：坐标已标定/已学习 → 直点，跳过 dump 定位（~150ms vs ~2s）
        // 查找优先级（v1.2 #4）：① 本次 run 指定的按钮坐标分组（换机即换分组，可复用可校验）
        //                       ② 旧的「按设备序列号」缓存（未指定分组时的兼容路径）
        int[] live=locator.locateCenter(adb.uiDump(st.device),resource(st,key));
        if(live!=null){adb.tap(st.device,live[0],live[1]);calibration.learnCoord(st.device,key,live);sleep(st.plan==null?props.perfTapSettleMs():st.plan.tapSettleMs());return;}
        int[] cached = coordinateLayoutValid(st) ? st.groupCoords.get(key) : null;
        if (cached == null) {
            cached = st.plan == null ? calibration.getCachedCoord(st.device, key) : coordinateLayoutValid(st)?st.plan.coordinate(key):null;
        }
        if (cached != null) {
            adb.tap(st.device, cached[0], cached[1]);
            log.info("run#{} 坐标直点 {} ({},{})", st.id, key, cached[0], cached[1]);
            sleep((st.plan==null?props.perfTapSettleMs():st.plan.tapSettleMs()));
            return;
        }
        // 慢路径：轮询 dump 定位，找到后学习坐标供后续直点
        String r = resource(st,key);
        int[] c = null;
        long deadline = System.currentTimeMillis() + 8000; // 最多轮询 8 秒
        while (System.currentTimeMillis() < deadline && alive(st)) {
            // 每次 dump 当前界面，查按钮中心坐标
            c = locator.locateCenter(adb.uiDump(st.device), r);
            if (c != null) {
                break;
            }
            sleep(300); // 按钮可能还在加载，间隔 300ms 重试
        }
        if (c == null) {
            // 8 秒内没出现：说明界面不在预期状态，判无效圈
            log.warn("run#{} 按钮 8s 内未出现: {}", st.id, key);
            throw new LoopInvalid("按钮不在当前界面: " + key);
        }
        adb.tap(st.device, c[0], c[1]);
        log.info("run#{} 定位点击 {} ({},{})，坐标已学习", st.id, key, c[0], c[1]);
        // 学习坐标：下次同设备同按钮直接走快路径
        calibration.learnCoord(st.device, key, c);
        sleep((st.plan==null?props.perfTapSettleMs():st.plan.tapSettleMs()));
    }

    /** 对局内按钮固定坐标直点（写死，不依赖缓存/clear/dump）。 */
    private int[] requireFixedCoord(PerfRunState st, String key) throws LoopInvalid {
        if(!coordinateLayoutValid(st))throw new LoopInvalid("坐标来源未知或布局已变化，请重新标定");
        int[] c = fixedCoordinate(st,key);
        if (c == null) {
            // 对局内操作没有 dump 兜底方案，缺配置只能判无效圈
            throw new LoopInvalid("对局内按钮坐标未配置: " + key + "（见 config/resource-ids.properties）");
        }
        return c;
    }

    /**
     * 取对局内按钮坐标：<b>优先用本次 run 选中的坐标分组，缺则回落到本地固定坐标</b>（v1.7 #31）。
     *
     * <p>【为什么要有这个方法】在此之前，分组里的 {@code perf_stop_game} /
     * {@code perf_exit_game} 两个坐标运行期从未被读取过 —— 走的一直是本地 config 里的
     * 固定坐标，那份坐标不随设备走，换台手机跑压测就会点偏；而用户在分组里标定的值
     * 只在界面上看着齐全，实际上没起作用。
     *
     * <p>【回落仍然保留】这两个按钮在对局内的 TextureView 上，dump 必然失败，
     * 分组里可能确实没有它们（属选标按钮）；此时回落固定坐标可以保证跑得起来，
     * 只是那份坐标只对当初测量的那台设备有效。
     *
     * @param st       当前运行态（取其中的分组坐标快照）
     * @param groupKey 分组里的按钮键（如 perf_stop_game）
     * @param fixedKey 本地固定坐标的配置键（如 perf_stop_game_xy）
     * @return 要点击的坐标；两者都取不到时返回 null
     */
    private boolean coordinateLayoutValid(PerfRunState st) {
        if(st.plan==null)try{calibration.ensureLayoutReady(st.device,st.groupId);return true;}catch(Exception stale){return false;}
        try{String json=st.plan.settings().get("coordinate.layout");return json!=null&&new ObjectMapper().readValue(json,PerfDeviceLayout.class).matches(PerfDeviceLayout.read(adb,st.device));}catch(Exception invalid){return false;}
    }
    private int[] inGameCoordOrNull(PerfRunState st, String groupKey, String fixedKey) {
        if(!coordinateLayoutValid(st))return null;
        int[] fromGroup = st.groupCoords.get(groupKey);
        if (fromGroup != null) {
            // 只记来源、不记数值（坐标属敏感信息，只允许留在本地库与 config/）
            log.info("run#{} 对局内按钮 {} 使用分组坐标", st.id, groupKey);
            return fromGroup;
        }
        int[] fixed = fixedCoordinate(st,fixedKey);
        if (fixed != null) {
            log.info("run#{} 对局内按钮 {} 分组未标定，回落到本地固定坐标", st.id, groupKey);
        }
        return fixed;
    }

    /** {@link #inGameCoordOrNull} 的严格版本：两个来源都没有时抛异常（主流程用，缺坐标应判无效圈）。 */
    private int[] inGameCoord(PerfRunState st, String groupKey, String fixedKey) throws LoopInvalid {
        int[] c = inGameCoordOrNull(st, groupKey, fixedKey);
        if (c == null) {
            throw new LoopInvalid("对局内按钮坐标未配置: " + fixedKey + "（见 config/resource-ids.properties）");
        }
        return c;
    }

    /** 点结束游戏一次，固定等待结算回大厅。
     *  注意：结束游戏按钮与大厅「加入游戏」按钮同位，只能点一次，不能重试——
     *  重点会误触「加入游戏」又进游戏。对局内 dump 必失败，不用 dump 验证。
     *  坐标来源见 {@link #inGameCoord}（分组优先，回落本地固定坐标）。 */
    private void tapStopGameOnce(PerfRunState st) throws LoopInvalid {
        int[] stop = inGameCoord(st, "perf_stop_game", "perf_stop_game_xy");
        adb.tap(st.device, stop[0], stop[1]);
        log.info("run#{} 点击结束游戏", st.id);
        sleep(3000); // 等结算回大厅
    }

    /** 连点退出房间（右上角，对局/大厅位置固定，多次点击无害，最终回列表）。
     *  坐标来源见 {@link #inGameCoord}（分组优先，回落本地固定坐标）。 */
    private void tapExitRoomRepeatedly(PerfRunState st) throws LoopInvalid {
        int[] exit = inGameCoord(st, "perf_exit_game", "perf_exit_game_xy");
        // 连点最多 5 次：右上角退出按钮位置固定，多点几次无害，最终必然回到列表
        for (int i = 0; i < 5 && !stopped(st); i++) {
            if(!coordinateLayoutValid(st))throw new LoopInvalid("旋转或尺寸已变化，停止使用旧坐标");
            adb.tap(st.device, exit[0], exit[1]);
            log.info("run#{} 退出房间点击 {}/5", st.id, i + 1);
            if (i < 4) {
                sleep(400); // 前 4 次之间间隔 400ms
            }
        }
        sleep(1500); // 最后一次点击后再等页面稳定
    }

    /** 轮询等待指定按钮出现（最多 timeoutSec 秒）。期间若 run 被停止或本圈超时则提前退出。
     * @return true=按钮已出现 */
    private boolean pollForRid(PerfRunState st, String key, int timeoutSec) {
        // 截止时间 = 当前时间 + 超时秒数
        long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
        String r;
        try {
            r = resource(st,key);
        } catch (Exception e) {
            return false; // rid 未配置：直接认为等不到
        }
        while (System.currentTimeMillis() < deadline && alive(st)) {
            // 每 500ms dump 一次查按钮；找到即返回 true
            if (locator.locateCenter(adb.uiDump(st.device), r) != null) {
                return true;
            }
            sleep(500);
        }
        return false; // 超时或被停止：返回 false
    }

    /** 在列表页找到游戏并点击。找不到抛 LoopInvalid。 */
    private void ensureOnListAndTapGame(PerfRunState st, String gameName) throws LoopInvalid {
        String itemRid = resource(st,"perf_game_list_item");
        // 先确保在列表页
        if (!recoverToList(st)) {
            // 恢复不到列表页（可能 app 崩了/前台被抢）：判无效圈
            throw new LoopInvalid("无法回到游戏列表页");
        }
        // 当前屏找
        if (tapGameOnScreen(st, itemRid, gameName)) {
            return;
        }
        // 向下滚找（列表可能在下方）：最多滚 8 屏。循环条件带 !stopped：停止后立即放弃，
        // 不再拖着执行线程跑完整个查找（那是「停止了却还在跑」的元凶之一）
        for (int i = 0; i < 8 && !stopped(st); i++) {
            swipeDown(st.device);
            sleep(900); // 等滚动稳定
            if (tapGameOnScreen(st, itemRid, gameName)) {
                return;
            }
        }
        // 回滚到顶再找一遍：防止初始位置在列表中间导致向下漏扫
        for (int i = 0; i < 12 && !stopped(st); i++) {
            swipeUp(st.device);
            sleep(500);
        }
        // 从顶部开始逐屏向下找（最多 12 屏）
        for (int i = 0; i < 12 && !stopped(st); i++) {
            if (tapGameOnScreen(st, itemRid, gameName)) {
                return;
            }
            swipeDown(st.device);
            sleep(900);
        }
        // 全部找完没有：判无效圈
        throw new LoopInvalid("列表中未找到游戏: " + gameName);
    }

    /** 在当前屏查找目标游戏并点击。
     * @param itemRid 列表项容器 resource-id
     * @param gameName 目标游戏完整名（含 (Custom) 后缀）
     * @return true=找到并已点击 */
    private boolean tapGameOnScreen(PerfRunState st, String itemRid, String gameName) {
        // dump 当前屏布局
        String xml = adb.uiDump(st.device);
        // 在列表项容器内按文本精确匹配目标游戏，取中心坐标
        int[] c = locator.locateByText(xml, itemRid, gameName);
        if (c == null) {
            return false; // 当前屏没有该游戏
        }
        adb.tap(st.device, c[0], c[1]);
        sleep(1000); // 等页面切换
        return true;
    }

    /** 等进大厅（join 按钮出现）；检测加载失败特征。 */
    private void waitLobby(PerfRunState st) throws LoopInvalid {
        long deadline = System.currentTimeMillis() + 30000;
        // 「加入游戏」按钮 = 大厅加载完成的标志；「重新加载」按钮 = 加载失败界面的标志；最多等 30 秒
        String joinRid = resource(st,"app_common_self_in");
        String reloadRid = resource(st,"perf_reload_btn");
        boolean reloadRetried = false; // 本圈是否已自动重试过重新加载
        while (System.currentTimeMillis() < deadline && alive(st)) {
            String xml = adb.uiDump(st.device);
            // 加入按钮出现 → 已进大厅，返回
            if (locator.locateCenter(xml, joinRid) != null) {
                return;
            }
            // 出现"重新加载"按钮且页面含"加载失败"文案 → 游戏加载失败
            if (locator.locateCenter(xml, reloadRid) != null && xml.contains(LOAD_FAIL_TEXT)) {
                if (!reloadRetried) {
                    // 首次遇到：自动点「重新加载」重试一次（不直接判无效）
                    int[] c = locator.locateCenter(xml, reloadRid);
                    if (c != null) {
                        adb.tap(st.device, c[0], c[1]);
                        reloadRetried = true;
                        log.info("run#{} 检测到加载失败，自动点重新加载重试", st.id);
                        sleep(1500); // 等重试加载
                        continue;
                    }
                }
                // 已重试仍失败 → 判无效圈
                throw new LoopInvalid("游戏加载失败");
            }
            // 每 500ms 轮询一次
            sleep(500);
        }
        // 30 秒没进大厅（卡加载/闪退/黑屏等）：判无效圈
        throw new LoopInvalid("进大厅超时（30s）");
    }

    /** 大厅流程：加入→加AI→准备（→开始）。earlySettle=true 时开始到外层单独控制。 */
    private void tapLobbyFlow(PerfRunState st, boolean holdStart) throws LoopInvalid {
        tapByRid(st, "app_common_self_in");                 // 加入游戏
        tapByRid(st, "app_common_game_add_ai_players");     // 设置AI玩家（点了直接入座）
        tapByRid(st, "app_common_self_ready");              // 准备
        if (!holdStart) {
            tapByRid(st, "app_common_self_playing");        // 开始游戏
        }
    }

    /** 等进入对局。对局内 TextureView 持续渲染，uiautomator dump 必报
     *  "could not get idle state"（单次 ~12s），无法靠 dump 轮询确认「结束游戏」按钮出现；
     *  按使用者配置的 perfPlayingWaitMs 等待后采样；是否已进入对局仍需结合运行证据。 */
    private void waitPlaying(PerfRunState st) {
        // 等待使用者配置的加载时间；此等待本身不能证明已进入对局
        sleep((st.plan==null?props.perfPlayingWaitMs():st.plan.playingWaitMs()));
    }

    private void tapExit(PerfRunState st) throws LoopInvalid {
        // 对局内 dump 必失败，结束游戏/退出房间坐标写死。
        // 结束游戏点一次（重点会误触同位「加入游戏」），固定等 3s 结算回大厅，
        // 然后立即连点右上角退出回列表（要求：点结束游戏后 5 秒内点退出）。
        tapStopGameOnce(st);
        tapExitRoomRepeatedly(st);
    }

    /** 回到列表页：对局中先点结束再退；前台不是被测 app（闪退/被弹窗挤走）则重新拉起。 */
    private boolean recoverToList(PerfRunState st) {
        long started=System.nanoTime();recordEvent(st,PerfRunFacts.Type.RECOVERY_STARTED,"RECOVERY",PerfRunFacts.Reason.RECOVERY_REQUIRED,null);
        boolean success=false;
        try{success=!stopped(st)&&recoverToListInternal(st);return success;}
        finally{recordEvent(st,PerfRunFacts.Type.RECOVERY_FINISHED,"RECOVERY","STOPPED".equals(st.status)?PerfRunFacts.Reason.STOPPED:success?PerfRunFacts.Reason.RECOVERED:PerfRunFacts.Reason.RECOVERY_FAILED,elapsed(started));}
    }
    private boolean recoverToListInternal(PerfRunState st) {
        String itemRid;
        try {
            itemRid = resource(st,"perf_game_list_item");
        } catch (Exception e) {
            // 未配置列表项 rid：无法判断是否在列表页，直接放弃恢复
            return false;
        }
        // 最多重试 5 轮：每轮 dump 一次判断界面，并尝试各种路径回到列表页
        for (int i = 0; i < 5; i++) {
            recordEvent(st,PerfRunFacts.Type.RECOVERY_ATTEMPT,"RECOVERY",PerfRunFacts.Reason.RECOVERY_FAILED,null);
            if (stopped(st)) {
                break;
            }
            // 恢复路径也要过设备/屏幕门（掉线时 ensureDevice 会等待，不烧无效圈）
            if (!ensureDevice(st)) {
                // 设备最终没恢复（或已被 stop）：放弃恢复
                return false;
            }
            ensureScreenOn(st);
            String xml = adb.uiDump(st.device);
            // 当前界面已有列表项：已经在列表页，恢复成功
            if (!locator.findByResourceId(xml, itemRid).isEmpty()) {
                return true;
            }
            try {
                // 对局中 dump 失败（xml 空，TextureView 持续渲染进不了 idle）→ 按「分组优先、
                // 回落固定坐标」点一次结束游戏兜底（与主流程 tapStopGameOnce 同一套取坐标逻辑）
                if (xml == null || xml.isEmpty()) {
                    int[] stop = inGameCoordOrNull(st, "perf_stop_game", "perf_stop_game_xy");
                    if (stop != null) {
                        adb.tap(st.device, stop[0], stop[1]);
                        log.info("run#{} recoverToList: dump 失败，点结束游戏兜底", st.id);
                        sleep(1500);
                        continue;
                    }
                }
                // 对局中（结束游戏按钮在）：先点结束回大厅，避免下圈续上局
                int[] stop = locator.locateCenter(xml, resource(st,"perf_stop_game"));
                if (stop != null) {
                    adb.tap(st.device, stop[0], stop[1]);
                    log.info("run#{} recoverToList: 点击结束游戏", st.id);
                    sleep(1500);
                    continue;
                }
                // 大厅中：直接退出房间
                if (locator.locateCenter(xml, resource(st,"app_common_self_in")) != null) {
                    int[] c = locator.locateCenter(xml, resource(st,"perf_exit_game"));
                    if (c != null) {
                        adb.tap(st.device, c[0], c[1]);
                        log.info("run#{} recoverToList: 大厅退出房间", st.id);
                        sleep(2000);
                        continue;
                    }
                }
                // 未知界面：前台不是被测 app（闪退/来电/系统弹窗）→ 重新拉起
                String fg = adb.foregroundPackage(st.device);
                if (fg == null || !resource(st,"perf_package").equals(fg)) {
                    log.warn("run#{} recoverToList: 前台为 {}（被测 app={}），重新拉起", st.id, fg, resource(st,"perf_package"));
                    st.message = "app 不在前台，重新拉起…";
                    adb.startApp(st.device, resource(st,"perf_package"));
                    sleep(6000);
                    continue;
                }
                // app 在前台但界面未知（可能加载中）：加载时直接退出会残留对局，
                // 先等它加载出结束游戏按钮；最后一轮才允许点退出兜底
                int[] c = locator.locateCenter(xml, resource(st,"perf_exit_game"));
                if (c != null && i == 4) {
                    adb.tap(st.device, c[0], c[1]);
                    log.warn("run#{} recoverToList: 未知界面兜底退出房间", st.id);
                    sleep(2000);
                    continue;
                }
            } catch (Exception failure) {
                log.warn("run#{} 回到列表页失败：{}；将在限定次数内重试", st.id, failure.getMessage());
                st.message = "恢复列表失败，请检查手机是否解锁及应用是否可启动";
            }
            sleep(2000);
        }
        // 5 轮重试结束后再确认一次：在列表页则恢复成功，否则失败
        String xml = adb.uiDump(st.device);
        return !locator.findByResourceId(xml, itemRid).isEmpty();
    }
}
