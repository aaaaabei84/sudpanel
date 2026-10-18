package com.sudpanel.runner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.sudpanel.adb.AdbClient;
import com.sudpanel.config.AppProperties;
import com.sudpanel.filter.LogFilter;
import com.sudpanel.locator.UiLocator;
import com.sudpanel.model.Direction;
import com.sudpanel.model.ResultStatus;
import com.sudpanel.model.RunResult;
import com.sudpanel.model.RunStatus;
import com.sudpanel.model.TestPoint;
import com.sudpanel.perf.ButtonGroupRepository;
import com.sudpanel.point.TestPointRepository;

/**
 * 执行引擎（功能测试核心）。
 * - apptomg：后台线程批量自动执行（清日志→定位点击→等待→抓日志→过滤→自动判定）。
 * - mgtoapp：前端驱动的走查状态机（下一个/跳过/判定）。
 *
 * 【关键设计点】
 * 1. 一次执行 = 一个 RunState（内存态）+ 一行 test_run 记录（持久态）：
 *    - RunState 保存在内存 ConcurrentHashMap 中，保存运行中的实时状态（当前索引、结果列表等），
 *      因此刷新页面后运行状态依然保留（不随 HTTP 请求结束而丢失）。
 *    - test_run 表落库 run 的元信息（方向、设备、状态、时间、选中的测试点 id），用于历史列表展示。
 * 2. 两条流程分流（start 方法按 direction 分支）：
 *    - APPTOMG（App→游戏全自动）：提交到线程池后台执行 runApptomg()，前端只轮询进度；
 *    - MGTOAPP（游戏→App 人工走查）：不同步后台执行，由前端逐个调用 trigger/skip/judge 驱动状态机推进。
 * 3. 并发安全：runs 用 ConcurrentHashMap；id 用 AtomicLong 自增保证并发下 id 不重复；
 *    结果列表 addResult 加 synchronized 避免并发写 ArrayList。
 * 4. 持久化策略：每个测试点判定完成后立即写入 test_result 表（saveResult），
 *    而非全部跑完再批量写——保证中途崩溃/停止也有完整记录可查。
 * 5. 停止（stop）采用协作式：只把 status 置为 STOPPED，后台循环每轮开头检查该标志决定是否退出，
 *    不做线程中断（避免在 ADB 调用中途打断造成设备状态不确定）。
 */
@Service
public class RunService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RunService.class);

    /**
     * resource-id 配置值的前缀指令：表示「本测试点不做 dump，直接复用另一测试点本轮已定位到的坐标」。
     * 写法：{@code 本测试点的resourceKey=@reuse:被复用测试点的resourceKey}
     *
     * <p>【为什么需要它】对局内是 TextureView 持续渲染，{@code uiautomator dump} 必失败
     * （与 perf 体系同一根因）→「结束游戏」这类只出现在对局内的按钮，无法用 resource-id 定位。
     * 但这类按钮与大厅里的主按钮通常占**同一屏幕位置**，因此可以复用它的坐标。
     */
    private static final String REUSE_PREFIX = "@reuse:";

    /**
     * 解析 resource-id 配置值里的「坐标复用」指令。
     *
     * <p>返回值的三种情况需区分清楚，调用方必须同时判 null 与 isEmpty：
     * <ul>
     *   <li>{@code null} —— 不是复用指令，按常规 resource-id 处理（走 dump 定位）；</li>
     *   <li>空串 —— 是复用指令但缺被复用键（配成了 {@code @reuse:}）→ 配置错误，应报错而不是当普通 id 用；</li>
     *   <li>非空 —— 被复用的测试点 resourceKey。</li>
     * </ul>
     *
     * @param rid config/resource-ids.properties 中配置的值
     * @return 见上；非复用指令返回 null
     */
    static String reuseSourceKey(String rid) {
        if (rid == null || !rid.startsWith(REUSE_PREFIX)) {
            return null;
        }
        return rid.substring(REUSE_PREFIX.length()).trim();
    }

    // ---------- 注入的依赖组件（Spring 构造器注入，final 保证不可变） ----------
    // ADB 客户端：封装 adb 命令（清日志、dump 控件树、点击、抓日志等设备操作）
    private final AdbClient adb;
    // UI 定位器：在控件树 XML 中按 resource-id 查找控件中心坐标
    private final UiLocator locator;
    // 日志过滤器：从 logcat 原始日志中筛出与关键字匹配的行
    private final LogFilter filter;
    // 测试点仓库：读取启用中的测试点（数据库访问层）
    private final TestPointRepository points;
    // Spring JDBC 模板：对 SQLite 做 SQL 读写
    private final JdbcTemplate jdbc;
    // 应用配置：resource-id 文件路径、点击等待毫秒数等
    private final AppProperties props;
    // 按钮坐标分组仓储（在 perf 包）：apptomg 复用「性能测试里已标定」的坐标，
    // 避免同一批按钮在两个模块各标定一份（v1.6 #26）。
    // 依赖方向 run -> perf：perf 侧不反向依赖 run，无循环。
    private final ButtonGroupRepository buttonGroups;

    // ---------- 运行期内部状态 ----------
    // 内存中的运行态表：runId -> RunState（ConcurrentHashMap 保证并发安全，页面刷新后状态仍保留）
    private final ConcurrentHashMap<Long, RunState> runs = new ConcurrentHashMap<>();
    // run id 生成器：AtomicLong 保证多线程并发创建 run 时 id 不重复
    private final AtomicLong idGen = new AtomicLong(1);
    // 后台线程池：apptomg 自动执行任务提交到这里异步运行（缓存线程池，任务多时自动扩线程）
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicInteger executionOwners = new AtomicInteger();

    /** STOPPED 只表示已申请停止；后台退出且落库收尾结束才释放执行权。 */
    public boolean hasExecutionOwner() {
        return executionOwners.get() > 0;
    }
    // resource-id 配置文件（config/resource-ids.properties）的键值对：
    // 内容为 资源键 -> 控件 resource-id 字符串，用于 UI 定位
    private final Properties resourceIds = new Properties();

    /**
     * 构造函数：Spring 自动注入依赖，并在启动时完成两件初始化（建表 + 加载配置）。
     * @param adb          ADB 客户端（设备操作）
     * @param locator      UI 定位器（控件坐标查找）
     * @param filter       日志过滤器（关键字过滤）
     * @param points       测试点仓库（测试点 CRUD）
     * @param jdbc         Spring JDBC 模板（SQLite 读写）
     * @param props        应用配置项
     * @param buttonGroups 按钮坐标分组仓储（apptomg 复用 perf 标定坐标）
     */
    public RunService(AdbClient adb, UiLocator locator, LogFilter filter, TestPointRepository points,
                      JdbcTemplate jdbc, AppProperties props, ButtonGroupRepository buttonGroups) {
        this.adb = adb;
        this.locator = locator;
        this.filter = filter;
        this.points = points;
        this.jdbc = jdbc;
        this.props = props;
        this.buttonGroups = buttonGroups;
        // 初始化数据库表结构（建表 + 迁移旧表），并把 id 生成器推进到已有最大 id
        initTables();
        // 收敛上一次进程遗留的"进行中"记录（v1.6 #25，详见方法注释）
        reapOrphanRuns();
        // 加载 resource-id 配置文件到内存 Properties（apptomg 定位按钮用）
        loadResourceIds();
    }

    /**
     * 启动时收敛孤儿 run（v1.6 #25）。
     *
     * <p>【问题】mgtoapp 的执行完全由前端状态机驱动（前端逐个调 trigger / skip / judge），
     * 一旦用户中途关页面、退出应用或切换而不点「停止」，{@code test_run.status} 就永远停在
     * {@code RUNNING}，在历史列表里表现为一条永远不会结束的"进行中"。
     * apptomg 不受影响 —— 它在后台线程的 {@code finally} 里落终态，除非进程被强杀。
     *
     * <p>【为什么启动时判定是安全的】能驱动这些 run 的只剩内存态 {@code runs}，
     * 而它随进程消亡；本方法在构造期执行，那一刻 {@code runs} 必然为空。
     * 因此凡是库里仍标着进行中的行，其驱动者一定已经不存在 —— 判定无需任何额外依据。
     *
     * <p>【为什么不在运行期周期扫描】运行期的"进行中"可能是真的在跑，扫描就有误杀风险；
     * 而孤儿的唯一来源是进程退出，启动时清一次即可覆盖全部情形。
     *
     * <p>【为什么复用 STOPPED 而不新增 ABANDONED】STOPPED 是既有枚举值，前端配色、
     * 报告与导出都已认它；新增枚举要连带改三处，收益不抵成本。
     *
     * <p>【为什么只清 RUNNING】{@code test_run} 表里的状态只可能来自 {@link RunStatus} 枚举，
     * 其中"在跑"的只有 {@code RUNNING} 一个值。压测那边的 {@code WAIT_DEVICE}
     * 是 {@code perf_run} 表的状态、与本表无关，不能混进来。
     *
     * <p>不动 {@code test_result}：历史判定结果全部保留，只改主记录的状态。
     */
    private void reapOrphanRuns() {
        try {
            int n = jdbc.update(
                    "UPDATE test_run SET status=?, end_time=? WHERE status=?",
                    RunStatus.STOPPED.name(), Instant.now().toString(), RunStatus.RUNNING.name());
            if (n > 0) {
                // 只记条数，不记设备序列号等敏感值
                log.info("已收敛 {} 条孤儿 run（上次进程退出时未收尾的进行中记录，置为 STOPPED）", n);
            }
        } catch (Exception e) {
            // 收敛失败不影响服务启动；下次启动会再试一次（幂等）
            log.warn("孤儿 run 收敛失败（不影响启动）：{}", e.getMessage());
        }
    }

    /**
     * 初始化数据库：建两张核心表（test_run / test_result），
     * 并做两轮幂等迁移（补列），最后恢复 id 自增起点。
     * 幂等：方法可重复执行，已存在的表/列不会报错。
     */
    private void initTables() {
        // 建表 test_run：一次执行（run）的主记录表，一条记录 = 一次完整的测试执行。
        // 设计说明：
        // - id 不声明 AUTOINCREMENT，由应用层 idGen 显式分配，重启后从 max(id)+1 继续，
        //   避免 SQLite 自增序列与内存中的运行态脱节；
        // - selected_point_ids 记录本次勾选了哪些测试点，供历史列表回显勾选状态。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS test_run (
                  id INTEGER PRIMARY KEY,
                  direction TEXT NOT NULL,
                  device TEXT,
                  status TEXT NOT NULL,
                  start_time TEXT,
                  end_time TEXT,
                  selected_point_ids TEXT
                )
                """);
        // v0.5 迁移：旧表无 selected_point_ids 列则补加（幂等）
        // 原理：SQLite 的 ADD COLUMN 在列已存在时会抛异常，
        // 因此"尝试加列 + 捕获异常忽略"即可实现幂等——
        // 新库：CREATE TABLE 已含该列，ALTER 必然失败，被吞掉；
        // 旧库：首次 ALTER 成功补列，再次执行则失败被吞。
        try {
            jdbc.execute("ALTER TABLE test_run ADD COLUMN selected_point_ids TEXT");
        } catch (Exception ignore) {
            // 列已存在，忽略
            // （SQLite 重复加列会抛 "duplicate column name"，属预期内异常，吞掉即可）
        }
        // 建表 test_result：每个测试点的判定明细表（一对多：一个 run 含多个 point 结果）。
        // 字段含义：
        // - run_id：所属 run；point_id：测试点 id；state_name：测试点状态名；
        // - point_name：测试点名称快照（迁移后补的列，防止测试点后续改名导致历史结果对不上）；
        // - direction：方向冗余存储（便于按方向查历史）；
        // - result：判定结果 PASS/FAIL/SKIP 等；log_snippet：命中的日志片段；
        // - note：附加说明（失败原因/人工备注）；elapsed_ms：本次执行耗时（毫秒）；
        // - created_at：记录创建时间。
        // 注意：本表 id 用 AUTOINCREMENT（数据库自增），与 test_run 的应用层分配 id 不同。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS test_result (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  run_id INTEGER NOT NULL,
                  point_id INTEGER,
                  state_name TEXT,
                  point_name TEXT,
                  direction TEXT,
                  result TEXT,
                  log_snippet TEXT,
                  note TEXT,
                  elapsed_ms INTEGER,
                  created_at TEXT
                )
                """);
        // v0.6 迁移：旧表无 point_name 列则补加（测试点名称快照）
        // 与 v0.5 同样的幂等策略：列已存在时 ALTER 抛异常，捕获忽略即可。
        try {
            jdbc.execute("ALTER TABLE test_result ADD COLUMN point_name TEXT");
        } catch (Exception ignore) {
            // 列已存在，忽略
            // （幂等迁移的标准写法：重复执行不会报错）
        }
        // 重启后 idGen 从已有最大 id 继续，避免主键冲突
        // COALESCE(MAX(id),0)：表为空时 MAX(id) 为 NULL，用 COALESCE 兜底为 0，
        // 保证查询永远返回一个数字，不会因 NULL 导致后续 +1 变成 NULL。
        Long maxId = jdbc.queryForObject("SELECT COALESCE(MAX(id),0) FROM test_run", Long.class);
        if (maxId != null) {
            // 下一个新 run 从 maxId+1 开始分配，保证与库里已有记录不冲突
            idGen.set(maxId + 1);
        }
    }

    /**
     * 加载 resource-id 配置文件到内存。
     * 配置文件是标准 .properties 格式（键=值），键为 resourceKey，值为控件 resource-id。
     * 文件缺失不视为错误：apptomg 执行到对应测试点时，
     * 会因拿不到 resource-id 而抛出"未配置 resource-id"，从而让该点判定为 FAIL，便于暴露配置缺口。
     */
    private void loadResourceIds() {
        try {
            Path p = Path.of(props.resourceIdFile());
            if (Files.exists(p)) {
                // 存在才加载；Properties.load 从输入流读取键值对
                resourceIds.load(Files.newInputStream(p));
            }
        } catch (Exception e) {
            // 本地配置文件缺失属正常，apptomg 执行时会以"未配置 resource-id"记录失败
            // （读取失败也不阻断启动，属于可恢复的运行时问题）
        }
    }

    /**
     * 运行中的状态（内存态，刷新页面后仍在）。
     * 设计要点：
     * - 用 static 内部类承载状态，字段全部 public 便于 web 层直接读取；
     * - index/status 用 volatile：后台线程（apptomg）与请求线程（轮询/操作接口）会并发读写，
     *   volatile 保证可见性（一个线程的修改对另一个线程立即可见）；
     * - results 用普通 ArrayList + addResult 加 synchronized：
     *   保证并发添加结果时不会破坏数组内部结构（避免 ConcurrentModificationException / 丢失写入）。
     */
    public static class RunState {
        public final long id;                    // run id（全局唯一）
        public final Direction direction;        // 本次执行方向
        public final String deviceSerial;        // 目标设备序列号（adb 操作对象）
        public final List<TestPoint> points;     // 本次要执行的测试点列表（已确定顺序）
        public final Long buttonGroupId;         // 复用的性能测试坐标分组 id；null = 不启用坐标复用
        public volatile int index = 0;           // 当前执行到的测试点下标（0 起）
        public volatile RunStatus status = RunStatus.RUNNING; // 运行状态，初始 RUNNING
        public final List<RunResult> results = new ArrayList<>(); // 已判定完成的测试点结果（按顺序追加）
        public volatile String pendingLog;       // mgtoapp 人工判定模式下暂存的日志片段（等待人工判定）
        public final Instant startedAt = Instant.now(); // 启动时间（对象创建时刻，内存态）

        /**
         * 包级私有构造：只允许 RunService 内部创建。
         * @param id            run id
         * @param direction     执行方向
         * @param deviceSerial  设备序列号
         * @param points        测试点列表
         * @param buttonGroupId 复用的按钮坐标分组 id（null = 不用分组，全部走 dump 定位）
         */
        RunState(long id, Direction direction, String deviceSerial, List<TestPoint> points, Long buttonGroupId) {
            this.id = id;
            this.direction = direction;
            this.deviceSerial = deviceSerial;
            this.points = points;
            this.buttonGroupId = buttonGroupId;
        }

        /**
         * 追加一个判定结果。
         * synchronized 保证多线程同时 addResult 时的线程安全。
         * @param r 判定结果（已持久化到 test_result 表的记录）
         */
        public synchronized void addResult(RunResult r) {
            results.add(r);
        }
    }

    /**
     * 启动一次执行。pointIds 对 apptomg 与 mgtoapp 均生效（空/null 则全量）。返回 {runId, mode, total}。
     * 两条流程的统一入口。流程：查启用的测试点 → 组装顺序 → 分配 run id → 建内存态 + 落库 →
     *       按方向分流：APPTOMG 提交后台线程自动跑，MGTOAPP 仅清日志等前端逐步驱动。
     * @param direction    执行方向（APPTOMG=自动 / MGTOAPP=人工走查）
     * @param deviceSerial 目标设备序列号
     * @param pointIds     选中的测试点 id 列表（保持前端勾选顺序；null/空 = 全量）
     * @param buttonGroupId 复用的按钮坐标分组 id（仅 APPTOMG 有意义；null = 不启用，全部 dump 定位）
     * @return 返回 {runId, mode, total}：runId 为新 run 的 id；
     *         mode 为 "auto"（后台自动执行）或 "manual"（前端人工驱动）；
     *         total 为本次要执行的测试点总数。
     */
    public Map<String, Object> start(Direction direction, String deviceSerial, List<Long> pointIds,
                                     Long buttonGroupId) {
        // 该方向下所有"启用"的测试点（enabled=true 才参与执行）
        List<TestPoint> all = points.findEnabledByDirection(direction);
        List<TestPoint> pts;
        if (pointIds != null && !pointIds.isEmpty()) {
            // 按勾选先后顺序执行（而非默认 seq 顺序）
            // 先把全量测试点按 id 建成查找表，再按 pointIds 顺序逐个取出，
            // 这样前端勾选的顺序就是实际执行顺序（勾选顺序 ≠ 配置顺序）。
            Map<Long, TestPoint> byId = all.stream().collect(Collectors.toMap(TestPoint::id, p -> p));
            List<TestPoint> ordered = new ArrayList<>();
            for (Long pid : pointIds) {
                TestPoint p = byId.get(pid);
                // 跳过无效 id：可能已被删除或未启用，get 不到就忽略，不中断整个 run
                if (p != null) {
                    ordered.add(p);
                }
            }
            pts = ordered;
            if (pts.isEmpty()) {
                // 勾选的 id 全部无效 → 没有可执行的测试点，直接拒绝启动
                throw new IllegalStateException("没有选中任何启用的测试点");
            }
        } else {
            // 未勾选 → 执行该方向全部启用测试点（按默认 seq 顺序）
            pts = all;
        }
        if (pts.isEmpty()) {
            // 该方向一个启用的测试点都没有，无法开始
            throw new IllegalStateException("该方向没有启用的测试点");
        }
        // 分配新 run id（原子自增，并发启动多个 run 也不冲突）
        long id = idGen.getAndIncrement();
        // 只有 APPTOMG 需要点击坐标；MGTOAPP 是人工走查，分组 id 一律丢弃，避免脏参数被误用
        Long groupId = direction == Direction.APPTOMG ? buttonGroupId : null;
        // 先建内存运行态并注册到 runs 表，后续轮询接口立即能查到
        RunState rs = new RunState(id, direction, deviceSerial, pts, groupId);
        runs.put(id, rs);
        // 把选中的测试点 id 序列化成逗号分隔字符串落库（历史列表回显勾选用）
        String selIds = pts.stream().map(p -> String.valueOf(p.id())).collect(Collectors.joining(","));
        // 插入 test_run 主记录：初始状态 RUNNING，start_time 用内存态的开始时间
        jdbc.update("INSERT INTO test_run(id,direction,device,status,start_time,selected_point_ids) VALUES(?,?,?,?,?,?)",
                id, direction.name(), deviceSerial, RunStatus.RUNNING.name(), rs.startedAt.toString(), selIds);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("runId", id);
        if (direction == Direction.APPTOMG) {
            // apptomg：全自动，提交到后台线程池执行，接口立即返回，前端轮询进度
            resp.put("mode", "auto");
            resp.put("total", pts.size());
            executionOwners.incrementAndGet();
            try {
                executor.submit(() -> {
                    try { runApptomg(rs); }
                    finally { executionOwners.decrementAndGet(); }
                });
            } catch (RuntimeException e) {
                executionOwners.decrementAndGet();
                rs.status = RunStatus.ERROR;
                jdbc.update("UPDATE test_run SET status=?, end_time=? WHERE id=?",
                        RunStatus.ERROR.name(), Instant.now().toString(), rs.id);
                throw e;
            }
        } else {
            // mgtoapp：人工走查，先清一次日志（保证从干净日志开始），
            // 后续由前端调用 trigger/skip/judge 一步步驱动
            resp.put("mode", "manual");
            resp.put("total", pts.size());
            adb.logcatClear(deviceSerial);
            // 勾选记录滚动淘汰：只保留最近 50 条（含本次），超出部分按 id 最旧的删掉，级联删 test_result 防孤儿数据
            List<Long> stale = jdbc.query("""
                    SELECT id FROM test_run
                    WHERE direction = 'MGTOAPP'
                    ORDER BY id DESC LIMIT -1 OFFSET 50
                    """, (row, i) -> row.getLong("id"));
            for (Long sid : stale) {
                jdbc.update("DELETE FROM test_result WHERE run_id = ?", sid);
                jdbc.update("DELETE FROM test_run WHERE id = ?", sid);
            }
            if (!stale.isEmpty()) {
                log.info("MGTOAPP 勾选记录超 50 条，淘汰最旧 {} 条（run {}", stale.size(), stale);
            }
        }
        return resp;
    }

    /**
     * 历史 run 列表（含选中点 id，供勾选记录）。
     * 按 id 倒序（最新在前）；selectedPointIds 解析为 List<Long> 供前端回显勾选状态。
     * @return 每条 run 一个 Map：id/direction/device/status/startTime/endTime/selectedPointIds
     */
    public List<Map<String, Object>> listRuns() {
        return jdbc.query("""
                SELECT id, direction, device, status, start_time, end_time, selected_point_ids
                FROM test_run ORDER BY id DESC
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("direction", rs.getString("direction"));
            m.put("device", rs.getString("device"));
            m.put("status", rs.getString("status"));
            m.put("startTime", rs.getString("start_time"));
            m.put("endTime", rs.getString("end_time"));
            m.put("selectedPointIds", parseIds(rs.getString("selected_point_ids")));
            return m;
        });
    }

    /**
     * 历史 run 的完整测试结果（DB 直查，不依赖内存态，app 重启后仍可用）。
     * 返回 run 元信息 + 结果统计 + 每条测试点明细。
     * @param runId run id
     * @return { run, stats, results }；run 不存在时抛 EmptyResultDataAccessException（由上层转 404）
     */
    public Map<String, Object> resultsOf(long runId) {
        Map<String, Object> run = jdbc.queryForMap("""
                SELECT id, direction, device, status, start_time, end_time, selected_point_ids
                FROM test_run WHERE id = ?
                """, runId);
        // selected_point_ids 解析成 List<Long>（前端回显勾选用）
        Object sel = run.get("selected_point_ids");
        run.put("selectedPointIds", parseIds(sel == null ? null : sel.toString()));
        run.remove("selected_point_ids");

        // 结果统计：按 result 分组计数
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("PASS", 0);
        stats.put("FAIL", 0);
        stats.put("SKIP", 0);
        stats.put("TOTAL", 0);
        jdbc.query("SELECT result, COUNT(*) AS cnt FROM test_result WHERE run_id=? GROUP BY result",
                (r) -> { stats.put(r.getString("result"), r.getInt("cnt")); }, runId);
        int total = (Integer) stats.get("PASS") + (Integer) stats.get("FAIL") + (Integer) stats.get("SKIP");
        stats.put("TOTAL", total);

        // 明细：按判定顺序返回
        List<Map<String, Object>> results = jdbc.query("""
                SELECT id, point_id, state_name, point_name, result, log_snippet, note, elapsed_ms, created_at
                FROM test_result WHERE run_id = ? ORDER BY id
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("pointId", rs.getLong("point_id"));
            m.put("stateName", rs.getString("state_name"));
            m.put("pointName", rs.getString("point_name"));
            m.put("result", rs.getString("result"));
            m.put("logSnippet", rs.getString("log_snippet"));
            m.put("note", rs.getString("note"));
            m.put("elapsedMs", rs.getObject("elapsed_ms"));
            m.put("createdAt", rs.getString("created_at"));
            return m;
        }, runId);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", run);
        out.put("stats", stats);
        out.put("results", results);
        return out;
    }

    /**
     * 历史 run 的元信息（DB 直查，供报告/导出使用；不依赖内存态）。
     *
     * <p>存在的意义：报告与导出原先从 {@link #summary(long)}（内存中的 runs map）取数据，
     * 进程重启后内存态清空 → 历史 run 的 HTML/CSV 一律报「运行不存在」，
     * 而 test_run / test_result 表的数据其实是完整的。改为 DB 直查后历史报告永久可用。
     *
     * @param runId 运行 id
     * @return {id, direction, device, status, startTime, endTime, selectedPointIds}
     * @throws org.springframework.dao.EmptyResultDataAccessException run 不存在时（由上层转 404）
     */
    public Map<String, Object> runMeta(long runId) {
        Map<String, Object> m = jdbc.queryForMap("""
                SELECT id, direction, device, status, start_time, end_time, selected_point_ids
                FROM test_run WHERE id = ?
                """, runId);
        // 与 listRuns 同口径：勾选 id 解析成 List<Long> 后换名，去掉原始逗号串字段
        Object sel = m.get("selected_point_ids");
        m.put("selectedPointIds", parseIds(sel == null ? null : sel.toString()));
        m.remove("selected_point_ids");
        return m;
    }

    /**
     * 历史结果行（只读 DB 视图，专供报告/导出）。
     *
     * <p>刻意不复用 {@link com.sudpanel.model.RunResult}：后者是「实时运行态」的模型，
     * 其 result/direction 是强类型枚举，一旦 DB 里出现历史脏值就会在报告渲染时炸掉；
     * 报告只需要「原样展示」，所以 result 保留字符串，渲染端按大写分支配色。
     *
     * @param id        结果行主键
     * @param pointId   测试点 id（历史数据可能为空）
     * @param stateName 状态名
     * @param pointName 测试点名称快照
     * @param result    结果字符串（PASS / FAIL / SKIP，原样来自 DB）
     * @param logSnippet 命中日志片段
     * @param note      备注
     * @param elapsedMs 耗时（毫秒，可能为 null）
     * @param createdAt 记录时间
     */
    public record StoredResult(Long id, Long pointId, String stateName, String pointName,
                               String result, String logSnippet, String note, Long elapsedMs, String createdAt) {
    }

    /**
     * 历史 run 的结果明细（DB 直查，按判定顺序）。
     * @param runId 运行 id
     * @return 明细列表；无结果时返回空列表
     */
    public List<StoredResult> storedResults(long runId) {
        return jdbc.query("""
                SELECT id, point_id, state_name, point_name, result, log_snippet, note, elapsed_ms, created_at
                FROM test_result WHERE run_id = ? ORDER BY id
                """, (rs, i) -> new StoredResult(
                rs.getLong("id"),
                rs.getObject("point_id") == null ? null : rs.getLong("point_id"),
                rs.getString("state_name"),
                rs.getString("point_name"),
                rs.getString("result"),
                rs.getString("log_snippet"),
                rs.getString("note"),
                rs.getObject("elapsed_ms") == null ? null : rs.getLong("elapsed_ms"),
                rs.getString("created_at")
        ), runId);
    }

    /**
     * 把数据库里逗号分隔的 id 字符串解析成 List<Long>。
     * 数据库存的可能是 "1,2,3"、"" 或 NULL，需要统一处理成干净的列表。
     * @param s 逗号分隔的 id 字符串（可为 null/空）
     * @return id 列表；null/空字符串返回空列表
     */
    private static List<Long> parseIds(String s) {
        // 空值（NULL / 空串 / 纯空白）直接返回空列表，避免 split 出脏数据
        if (s == null || s.isBlank()) {
            return List.of();
        }
        return Arrays.stream(s.split(","))   // 按逗号切成多个元素
                .map(String::trim)            // 去掉每个元素首尾空白（如 " 1, 2 "）
                .filter(x -> !x.isEmpty())    // 过滤掉空串（如 "1,,2" 中间的空段）
                .map(Long::parseLong)         // 字符串转 Long
                .toList();                    // 收集为不可变 List
    }

    /**
     * apptomg 全自动执行主循环（在后台线程池中运行，不阻塞 HTTP 请求）。
     * 对每个测试点的标准动作序列：
     *   清 logcat → 取 resource-id → 定位按钮坐标 → 点击（可多次）→
     *   等待 → 抓 logcat → 关键字过滤 → 命中=PASS，未命中/异常=FAIL → 落库。
     * 定位坐标的优先级（v1.6 #26）：
     *   ① 本 run 选中的「性能测试按钮坐标分组」命中该按钮 → 直接用标定坐标；
     *   ② resource-ids.properties 配了 {@code @reuse:<key>} → 复用本轮已定位到的坐标
     *      （对局内 dump 必失败，见 {@link #REUSE_PREFIX}）；
     *   ③ 常规 dump 控件树定位。
     * 单点失败不中断整体：任何异常都被捕获并记为 FAIL，继续下一个测试点。
     * @param rs 本次运行的运行时状态（points 列表即待执行队列）
     */
    private void runApptomg(RunState rs) {
        // 本轮已定位成功的坐标缓存：resourceKey -> 中心坐标 [x, y]。
        // 只在本 run 内有效（不跨 run 持久化）——同一 run 内设备/页面状态连续，坐标可安全复用；
        // 供配了 @reuse:<key> 的测试点取用。三种坐标来源（分组/复用/dump）都会写入，
        // 这样 @reuse 无论上游走的是哪条路径都能取到值。
        Map<String, int[]> located = new HashMap<>();
        // 复用的性能测试坐标分组（v1.6 #26）：开跑前一次性载入，循环里只查内存、不再查库
        Map<String, int[]> groupCoords = Map.of();
        String groupLabel = "未指定";
        if (rs.buttonGroupId != null) {
            groupCoords = buttonGroups.coordsOf(rs.buttonGroupId);
            Map<String, Object> g = buttonGroups.getGroup(rs.buttonGroupId);
            if (g != null) {
                groupLabel = String.valueOf(g.get("name"));
            }
            // 只记「分组名 + 已标定按钮数」，不记坐标数值（坐标属敏感信息，只允许留在本地库与 config/）
            log.info("apptomg run#{} 复用坐标分组「{}」（已标定 {} 个按钮）：命中分组坐标的测试点不再 dump",
                    rs.id, groupLabel, groupCoords.size());
        }
        try {
            // 逐个测试点顺序执行（顺序 = start 时确定的顺序）
            for (int i = 0; i < rs.points.size(); i++) {
                // 协作式停止检查：每轮开始看是否被 stop() 置为 STOPPED，是则立即退出循环
                if (rs.status == RunStatus.STOPPED) {
                    break;
                }
                // 更新当前进度下标，前端轮询 current/summary 可实时看到跑到第几个
                rs.index = i;
                TestPoint p = rs.points.get(i);
                long t0 = System.currentTimeMillis(); // 计时起点：记录本测试点耗时
                String snippet = "";  // 命中的日志片段（用于展示与后续人工复核）
                String note = null;   // 附加说明（失败原因等）
                ResultStatus result;
                try {
                    // ---- 第一步：清日志，确保只统计本次点击产生的日志 ----
                    adb.logcatClear(rs.deviceSerial);
                    // ---- 第二步 + 第三步：定位坐标并点击 ----
                    // 单步：定位一次坐标，按 tapCount 连点（有些开关需要连点两次才能覆盖开/关两种上报）。
                    // 注：v1.8 曾在这里加过"多步坐标序列"分支，服务于 apptomg 的「结束游戏」；
                    // v1.9 用户澄清该动作只能人手完成（点头像 → 弹窗选），测试点已挪到 mgtoapp 人工走查，
                    // 多步分支随之删除 —— apptomg 里不再有"平台点不动"的测试点。
                    Resolved r = resolveButton(rs, p.resourceKey(), groupCoords, groupLabel, located);
                    if (!r.ok()) {
                        throw new IllegalStateException(r.failure());
                    }
                    int[] c = r.xy();
                    // 三种来源都写进本轮缓存，供后续 @reuse:<key> 取用
                    located.put(p.resourceKey(), c);
                    // 常规 dump 定位时 note 为 null，保持"无备注"的原有语义
                    note = r.note();
                    // 点击（tapCount 指定连点次数，未配置则默认点 1 次）
                    int taps = p.tapCount() > 0 ? p.tapCount() : 1;
                    for (int k = 0; k < taps; k++) {
                        adb.tap(rs.deviceSerial, c[0], c[1]);   // 点击按钮中心
                        Thread.sleep(props.waitMs());           // 每次点击后等待，给界面/上报留时间
                    }
                    // ---- 第四步：抓日志并过滤关键字 ----
                    String dump = adb.logcatDump(rs.deviceSerial);
                    List<String> hits = filter.filter(dump, p.keyword());
                    snippet = String.join("\n", hits); // 命中行拼成多行文本存入结果
                    if (hits.isEmpty()) {
                        // 没有命中关键字 → 状态上报未发生，判 FAIL
                        result = ResultStatus.FAIL;
                        note = "未命中关键字 " + p.keyword();
                    } else {
                        // 命中关键字 → 状态上报成功，判 PASS
                        result = ResultStatus.PASS;
                    }
                } catch (Exception e) {
                    // 任意环节出错（定位失败/超时/adb 异常）都记 FAIL 并带上异常信息，不中断整个 run
                    result = ResultStatus.FAIL;
                    note = e.getMessage();
                }
                long elapsed = System.currentTimeMillis() - t0; // 本点总耗时
                // 结果先落库（saveResult 返回带 id 的 RunResult），再追加到内存结果列表
                rs.addResult(saveResult(rs, p, result, snippet, note, elapsed));
            }
            // 正常跑完所有测试点（且未被停止）→ 置为 COMPLETED
            if (rs.status != RunStatus.STOPPED) {
                rs.status = RunStatus.COMPLETED;
            }
        } catch (Exception e) {
            // 循环外的大异常（极少见，如 saveResult 数据库错误）→ 整个 run 标 ERROR
            rs.status = RunStatus.ERROR;
        } finally {
            // 无论正常/异常/被停止，最后都要落库收尾：更新状态与结束时间
            jdbc.update("UPDATE test_run SET status=?, end_time=? WHERE id=?",
                    rs.status.name(), Instant.now().toString(), rs.id);
        }
    }

    /**
     * 一次按钮坐标解析的结果。
     *
     * @param xy      要点击的坐标；null 表示没解析到
     * @param note    坐标来源说明（写进结果备注，事后从报告能看出这个点是按什么点的）；可为 null
     * @param failure 解析失败的原因（中文，可直接展示给用户）；成功时为 null
     */
    private record Resolved(int[] xy, String note, String failure) {
        boolean ok() {
            return xy != null;
        }
    }

    /**
     * 解析一个按钮键对应的点击坐标。三级优先级（v1.6 #26 定序，优先级由用户确认为「分组优先」）：
     * <ol>
     *   <li>本 run 选中的性能测试坐标分组命中 → 直接用标定坐标，不 dump
     *       （跨模块只维护一份坐标；对局内按钮本来也无法 dump）；</li>
     *   <li>{@code resource-ids.properties} 里配了 {@code @reuse:<key>} → 取本 run 已定位到的坐标；</li>
     *   <li>常规 dump 控件树 + 按 resource-id 查中心。</li>
     * </ol>
     *
     * <p>抽成独立方法（而不是写在执行循环里）的原因：这是唯一一处"按钮键 → 可点坐标"的判定，
     * 注释、优先级、报错文案都集中在一处，将来加坐标来源不必在循环里翻找。
     *
     * @param rs         本次运行态（取设备序列号与所用分组 id）
     * @param resourceKey 要解析的按钮键
     * @param groupCoords 本次 run 载入的分组坐标快照
     * @param groupLabel  分组名（仅用于备注文案）
     * @param located     本轮已定位坐标缓存（既读又写：@reuse 从它取，成功结果也写回它）
     * @return 解析结果，永不为 null
     */
    private Resolved resolveButton(RunState rs, String resourceKey,
                                   Map<String, int[]> groupCoords, String groupLabel,
                                   Map<String, int[]> located) {
        // ① 分组坐标：把按钮键解析成 perf 侧的按钮 key（含别名），再查分组
        String perfKey = ButtonGroupRepository.perfKeyFor(resourceKey);
        if (perfKey != null) {
            int[] fromGroup = groupCoords.get(perfKey);
            if (fromGroup != null) {
                return new Resolved(fromGroup, "复用分组「" + groupLabel + "」的「"
                        + ButtonGroupRepository.labelOf(perfKey) + "」坐标", null);
            }
        }
        // 走到这里 = 分组没覆盖该按钮，必须有 resource-id 兜底（dump 或 @reuse）。
        // 校验放在这个位置（而不是最开始）：分组里已有坐标时压根不需要 dump，
        // 此时再因「resource-id 未配」报错，等于强迫用户为本来无法 dump 的按钮补一条配置，纯属多余。
        String rid = resourceIds.getProperty(resourceKey);
        if (rid == null || rid.isBlank()) {
            return new Resolved(null, null, rs.buttonGroupId == null
                    ? "未配置 resource-id（见 config/resource-ids.properties）"
                    : "所选坐标分组里没有该按钮，且未配置 resource-id（见 config/resource-ids.properties）");
        }
        String srcKey = reuseSourceKey(rid);
        if (srcKey != null) {
            // ② 坐标复用指令 → 完全不 dump，直接取本轮已缓存的坐标
            if (srcKey.isEmpty()) {
                return new Resolved(null, null, "坐标复用指令缺少被复用键，应写成 "
                        + REUSE_PREFIX + "<被复用测试点的 resourceKey>");
            }
            int[] c = located.get(srcKey);
            if (c == null) {
                return new Resolved(null, null, "需复用 " + srcKey
                        + " 的坐标，但本轮尚未成功定位到该按钮（确认它排在本测试点之前且执行成功）");
            }
            // 记下坐标来源，事后核对报告时能看出它是复用来的而非现算的
            return new Resolved(c, "复用 " + srcKey + " 坐标", null);
        }
        // ③ 常规路径 —— dump 控件树，按 resource-id 查中心
        int[] c = locator.locateCenter(adb.uiDump(rs.deviceSerial), rid);
        if (c == null) {
            // 控件树里没有这个 resource-id：按钮不在当前界面（可能已跳转/没加载出来）
            return new Resolved(null, null, "控件树中未找到该 resource-id，按钮可能不在当前界面");
        }
        // note 为 null：保持"无备注"的原有语义
        return new Resolved(c, null, null);
    }

    /**
     * 把一个测试点的判定结果写入 test_result 表，并返回带数据库 id 的 RunResult 内存对象。
     * 注意：apptomg 在后台线程调用、mgtoapp 在请求线程调用，两个流程共用此落库逻辑。
     * @param rs      本次运行状态（取 runId / direction）
     * @param p       当前测试点
     * @param result  判定结果（PASS/FAIL/SKIP）
     * @param snippet 命中的日志片段（无则空串）
     * @param note    附加说明（失败原因/人工备注）
     * @param elapsed 本测试点耗时（毫秒）
     * @return 构造好的 RunResult（含自增主键 id）
     */
    private RunResult saveResult(RunState rs, TestPoint p, ResultStatus result, String snippet, String note, long elapsed) {
        Instant now = Instant.now();
        // 同一连接内 INSERT + 取 last_insert_rowid，避免 DriverManagerDataSource 分离连接导致 id 恒 0
        // 背景：每个 jdbc.update/query 可能使用不同连接（无连接池时），
        // 若分开两次调用，第二次查 last_insert_rowid 时连接可能已换，取到的 id 恒为 0；
        // 因此用 ConnectionCallback 保证 INSERT 和 SELECT 在同一连接内顺序执行。
        Long id = jdbc.execute((ConnectionCallback<Long>) con -> {
            // ---- 插入结果记录（10 个字段全部用占位符 ? 预编译，防 SQL 注入 + 类型安全）----
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO test_result(run_id,point_id,state_name,point_name,direction,result,log_snippet,note,elapsed_ms,created_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """)) {
                ps.setLong(1, rs.id);                  // 所属 run id
                ps.setObject(2, p.id());               // 测试点 id
                ps.setString(3, p.stateName());        // 测试点状态名
                ps.setString(4, p.description());      // 测试点名称（快照，防改名影响历史）
                ps.setString(5, rs.direction.name());  // 方向
                ps.setString(6, result.name());        // 判定结果
                ps.setString(7, snippet);              // 日志片段
                ps.setString(8, note);                 // 备注
                ps.setLong(9, elapsed);                // 耗时毫秒
                ps.setString(10, now.toString());      // 创建时间
                ps.executeUpdate();                    // 真正执行插入
            }
            // ---- 同一连接上取最后插入行的自增 id（SQLite 的 last_insert_rowid()）----
            try (Statement st = con.createStatement(); ResultSet r = st.executeQuery("SELECT last_insert_rowid()")) {
                r.next();                              // 结果集只有一行一列
                return r.getLong(1);                   // 返回自增主键
            }
        });
        // 内存态与数据库保持同一份数据：返回带 id 的完整 RunResult 对象
        return new RunResult(id, rs.id, p.id(), p.stateName(), p.description(), rs.direction, result, snippet, note,
                elapsed, now);
    }

    /**
     * 按 runId 取内存运行态；不存在则抛异常（提示前端该 run 无效/已过期）。
     * @param runId 运行 id
     * @return 对应的 RunState（内存态）
     * @throws IllegalStateException runId 不存在时
     */
    public RunState get(long runId) {
        RunState rs = runs.get(runId);
        if (rs == null) {
            throw new IllegalStateException("运行不存在: " + runId);
        }
        return rs;
    }

    /**
     * apptomg 各测试点与「性能测试按钮坐标分组」的对应关系（前端「坐标选择」区展示用）。
     *
     * <p>【存在意义】只给前端一个下拉是不够的：apptomg 的点比分组里的按钮多，
     * 而键名能对上的更少。前端必须能明确告诉用户「选了这个分组后，哪几个点会按标定坐标点、
     * 哪几个点仍走 dump」，否则用户无法判断这个分组值不值得选。
     * 映射规则（含别名）只写在 {@link ButtonGroupRepository#perfKeyFor} 一处，
     * 前端不复制一份，避免两边漂移。
     *
     * @param groupId 选中的分组 id（null = 未选分组，matched 全为 false）
     * @return 每个启用中的 apptomg 测试点一条记录：
     *         {resourceKey, label, perfKey, perfLabel, matched}；
     *         perfKey 为 null 表示该按钮在 perf 侧无对应标定项；matched 表示该分组确实标定了它
     */
    public List<Map<String, Object>> apptomgCoordSources(Long groupId) {
        // 未选分组 → 空坐标表，所有点都判为未命中
        Map<String, int[]> coords = groupId == null ? Map.of() : buttonGroups.coordsOf(groupId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (TestPoint p : points.findEnabledByDirection(Direction.APPTOMG)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("resourceKey", p.resourceKey());
            m.put("label", p.description() == null || p.description().isBlank() ? p.stateName() : p.description());
            String perfKey = ButtonGroupRepository.perfKeyFor(p.resourceKey());
            m.put("perfKey", perfKey);
            // 别名命中也给出中文名，前端可显示「结束游戏 ← 分组里的『结束游戏』」
            m.put("perfLabel", perfKey == null ? null : ButtonGroupRepository.labelOf(perfKey));
            m.put("matched", perfKey != null && coords.containsKey(perfKey));
            out.add(m);
        }
        return out;
    }

    /**
     * mgtoapp 当前测试点视图。
     * 返回当前测试点信息与整体进度，供前端"下一个/跳过/判定"页渲染。
     * 运行中返回当前测试点详情 + 待判定日志；已结束则 current/pendingLog 置 null。
     * @param runId 运行 id
     * @return 含 runId/direction/status/total/done/current/pendingLog 的视图 Map
     */
    public Map<String, Object> current(long runId) {
        RunState rs = get(runId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", rs.id);
        m.put("direction", rs.direction.name());
        m.put("status", rs.status.name());
        m.put("total", rs.points.size());  // 总测试点数
        m.put("done", rs.index);           // 已推进到的下标（≈已完成的点数）
        if (rs.status == RunStatus.RUNNING) {
            // 运行中：给出当前测试点详情（id/名称/关键字/判定模式等）和待判定的日志
            m.put("current", pointMap(rs.points.get(rs.index)));
            m.put("pendingLog", rs.pendingLog);
        } else {
            // 已结束：没有"当前测试点"概念，置 null 让前端走完成态渲染
            m.put("current", null);
            m.put("pendingLog", null);
        }
        return m;
    }

    /**
     * mgtoapp [下一个]：抓日志过滤；空 data 状态自动判定，其余返回命中行等人工判。
     * 触发后先抓取当前日志并过滤关键字：
     * - 若测试点 autoPass=true（无需人工确认）：直接自动判定 PASS/FAIL 并推进到下一个；
     * - 否则：把命中日志暂存到 pendingLog，返回给前端等人工点"判定"按钮。
     * @param runId 运行 id
     * @return autoJudged 是否已自动判定；result 自动判定结果（仅 autoJudged=true 时）；
     *         matched 本次命中的日志片段
     */
    public Map<String, Object> trigger(long runId) {
        RunState rs = get(runId);
        ensureRunning(rs); // 已结束的 run 不允许再触发
        TestPoint p = rs.points.get(rs.index); // 当前测试点
        long t0 = System.currentTimeMillis();  // 计时：抓日志+过滤的耗时
        // 抓取当前 logcat 并过滤出与关键字匹配的行（触发后游戏应已产生新上报日志）
        List<String> hits = filter.filter(adb.logcatDump(rs.deviceSerial), p.keyword());
        String snippet = String.join("\n", hits);
        long elapsed = System.currentTimeMillis() - t0;
        boolean hit = !hits.isEmpty(); // 是否命中关键字

        Map<String, Object> resp = new LinkedHashMap<>();
        if (p.autoPass()) {
            // ---- 自动判定分支：无需人工介入，直接出结果并推进 ----
            ResultStatus result = hit ? ResultStatus.PASS : ResultStatus.FAIL;
            String note = hit ? "自动通过（命中关键字）" : "未命中关键字 " + p.keyword();
            rs.addResult(saveResult(rs, p, result, snippet, note, elapsed)); // 落库 + 记入内存
            resp.put("autoJudged", true);
            resp.put("result", result.name());
            resp.put("matched", snippet);
            advance(rs); // 推进到下一个测试点（到底则完成 run）
        } else {
            // ---- 人工判定分支：先暂存日志，等前端调用 judge() 给结论 ----
            rs.pendingLog = snippet; // 挂到运行态上，judge 时取用
            resp.put("autoJudged", false);
            resp.put("matched", snippet);
        }
        return resp;
    }

    /**
     * mgtoapp [跳过]：当前测试点记 SKIP（不计入通过/失败），并推进到下一个。
     * 场景：该状态无法操作 / 人工判定为不适用。
     * @param runId 运行 id
     */
    public void skip(long runId) {
        RunState rs = get(runId);
        ensureRunning(rs);
        TestPoint p = rs.points.get(rs.index); // 当前测试点
        // 落库一条 SKIP 记录：日志片段为空、备注"跳过"、耗时 0
        rs.addResult(saveResult(rs, p, ResultStatus.SKIP, "", "跳过", 0));
        advance(rs); // 推进到下一个
    }

    /**
     * mgtoapp [判定]：人工对当前测试点给出结论并落库。
     * 使用 trigger 暂存的 pendingLog 作为该点的日志片段；耗时按 0 计（人工耗时无意义）。
     * @param runId  运行 id
     * @param result 人工判定结果（PASS/FAIL 等）
     * @param note   人工备注（作为判定依据说明）
     */
    public void judge(long runId, ResultStatus result, String note) {
        RunState rs = get(runId);
        ensureRunning(rs);
        TestPoint p = rs.points.get(rs.index); // 当前测试点
        // 把人工结论 + 之前暂存的日志一起落库
        rs.addResult(saveResult(rs, p, result, rs.pendingLog, note, 0));
        advance(rs); // 推进到下一个
    }

    /**
     * 停止一次运行（apptomg / mgtoapp 通用）。
     * 协作式停止：只改状态标志并落库，后台执行循环会在每轮开头检查该标志自行退出，
     * 不做线程 interrupt——避免在 ADB 系统调用中途被打断导致设备状态不确定。
     * @param runId 运行 id
     */
    public void stop(long runId) {
        RunState rs = get(runId);
        rs.status = RunStatus.STOPPED; // 内存态置 STOPPED（后台循环感知）
        // 同步落库：状态 + 结束时间，保证刷新后历史列表仍显示"已停止"
        jdbc.update("UPDATE test_run SET status=?, end_time=? WHERE id=?", RunStatus.STOPPED.name(),
                Instant.now().toString(), rs.id);
    }

    /**
     * 汇总视图：返回 run 的全部判定结果（前端结果页渲染用）。
     * results 用 new ArrayList<> 拷贝一份，避免前端遍历时与后台线程的 addResult 并发冲突。
     * @param runId 运行 id
     * @return 含 runId/direction/device/status/total/results 的 Map
     */
    public Map<String, Object> summary(long runId) {
        RunState rs = get(runId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", rs.id);
        m.put("direction", rs.direction.name());
        m.put("device", rs.deviceSerial);
        m.put("status", rs.status.name());
        m.put("total", rs.points.size());
        m.put("results", new ArrayList<>(rs.results)); // 拷贝副本返回，保护内部列表
        return m;
    }

    /**
     * 状态守卫：mgtoapp 操作类接口（trigger/skip/judge）共用，
     * 运行已结束时拒绝操作（防止对已完成 run 继续推进产生脏数据）。
     * @param rs 运行态
     * @throws IllegalStateException 状态不是 RUNNING 时
     */
    private void ensureRunning(RunState rs) {
        if (rs.status != RunStatus.RUNNING) {
            throw new IllegalStateException("运行已结束，无法操作");
        }
    }

    /**
     * mgtoapp 状态机推进：下标 +1，清掉暂存日志；
     * 若已越过最后一个测试点 → run 完成（置 COMPLETED 并落库）；
     * 否则清一次 logcat，保证下一个测试点的日志从干净状态开始统计。
     * @param rs 运行态
     */
    private void advance(RunState rs) {
        rs.index++;            // 推进到下一个测试点
        rs.pendingLog = null;  // 上一个点的暂存日志作废（已判定/跳过）
        if (rs.index >= rs.points.size()) {
            // 所有测试点处理完毕 → 整个 run 完成
            rs.status = RunStatus.COMPLETED;
            jdbc.update("UPDATE test_run SET status=?, end_time=? WHERE id=?", RunStatus.COMPLETED.name(),
                    Instant.now().toString(), rs.id);
        } else {
            // 还有下一个点：先清日志，避免上一个点的日志污染下一个点的关键字匹配
            adb.logcatClear(rs.deviceSerial);
        }
    }

    /**
     * 把 TestPoint 实体转成前端友好的 Map（只暴露需要的字段）。
     * 用 LinkedHashMap 保持插入顺序，前端展示字段顺序稳定。
     * @param p 测试点实体
     * @return 测试点字段 Map
     */
    private static Map<String, Object> pointMap(TestPoint p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());                    // 测试点 id
        m.put("stateName", p.stateName());      // 状态名
        m.put("description", p.description());  // 测试点名称/描述
        m.put("dataSchema", p.dataSchema());    // 上报数据模板（给人工比对参考）
        m.put("keyword", p.keyword());          // 判定关键字
        m.put("prompt", p.prompt());            // 人工操作提示
        m.put("triggerType", p.triggerType().name()); // 触发方式
        m.put("judgeMode", p.judgeMode().name());     // 判定模式
        m.put("autoPass", p.autoPass());              // 是否自动判定
        return m;
    }
}
