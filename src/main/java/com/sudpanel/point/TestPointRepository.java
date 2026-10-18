package com.sudpanel.point;

import java.util.ArrayList;   // 可变列表（种子数据收集等）
import java.util.List;        // 列表接口

// ---- JDBC 原生 API：用于需要手动控制"同一条连接"的场景（见 insert() 的注释）----
import java.sql.PreparedStatement;  // 预编译语句，防 SQL 注入且可复用
import java.sql.ResultSet;          // 查询结果集游标
import java.sql.Statement;          // 普通语句（取 last_insert_rowid() 用）

// ---- Spring JDBC 封装 ----
import org.springframework.jdbc.core.ConnectionCallback; // 回调：在框架提供的同一连接内执行自定义逻辑
import org.springframework.jdbc.core.JdbcTemplate;       // JDBC 模板：连接管理 + SQL 执行 + 结果映射
import org.springframework.jdbc.core.RowMapper;          // 行映射器：ResultSet 一行 → 领域对象
import org.springframework.stereotype.Repository;        // 数据访问层组件注解

import com.sudpanel.model.Direction;
import com.sudpanel.model.JudgeMode;
import com.sudpanel.model.TestPoint;
import com.sudpanel.model.TriggerType;

/**
 * 测试点存储。表结构 + 内置种子 + CRUD。
 */
// ===== 职责与设计 =====
// 1. 测试点（TestPoint）是平台的最小执行单元：一个测试点 = 一个状态上报动作 + 触发方式 + 判定方式；
// 2. 持久化在 SQLite 的 test_point 表（首次启动自动建表，见 init()）；
// 3. 表为空时写入内置种子：apptomg 若干条（自动触发 + 自动判定）、
//    mgtoapp 若干条（手动触发，人工判定）；
//    —— 数量随版本变化，此处不写死（历史上写死过，且与代码对不上，反复修正没有价值）；
// 4. 内置多段幂等迁移（v0.4 / v0.7 / v0.8 / v1.5 / v1.7 / v1.9），老库升级后结构与数据自动对齐新版本；
// 5. 查询结果统一经 MAPPER 行映射器转成 TestPoint 领域对象。
@Repository
public class TestPointRepository {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TestPointRepository.class);

    /** mgtoapp「游戏态」分组名（种子与迁移共用，避免两处写法分叉）。 */
    private static final String MG_GAME_GROUP = "mgtoapp_game";

    /**
     * 「结束游戏」的状态名（被测协议里的 state，同时是日志过滤关键字）。
     *
     * <p>【为什么 apptomg / mgtoapp 用的是同一个值】协议侧只有这一个状态名，方向不同不改变状态名。
     * apptomg 侧那条自动测试点已删除（触发方式建错了，见 {@link #deleteLegacyApptomgEndGame()}），
     * v1.9 起该状态只在 mgtoapp 侧由人工走查覆盖。
     */
    static final String END_GAME_STATE = "app_common_self_end";

    // JdbcTemplate：Spring JDBC 模板工具，负责连接获取、语句执行、异常转换（由构造器注入）
    private final JdbcTemplate jdbc;

    /** 构造器：注入 JdbcTemplate 并触发建表/迁移/播种，Bean 创建完成后表即可用。 */
    public TestPointRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        init();
    }

    // RowMapper：把查询结果集的一行（ResultSet）映射成一个 TestPoint 对象。
    // 列名必须与 SQL 里 SELECT 出来的列名一致；这里按列名取值，与 SELECT 顺序无关。
    private static final RowMapper<TestPoint> MAPPER = (rs, i) -> new TestPoint(
            rs.getLong("id"),
            // 枚举列在库里存的是 name() 字符串，读出来用 valueOf 反解析
            Direction.valueOf(rs.getString("direction")),
            rs.getString("grp"),
            rs.getInt("seq"),
            rs.getString("state_name"),
            rs.getString("resource_key"),
            rs.getString("description"),
            rs.getString("data_schema"),
            TriggerType.valueOf(rs.getString("trigger_type")),
            rs.getString("keyword"),
            rs.getString("prompt"),
            JudgeMode.valueOf(rs.getString("judge_mode")),
            // SQLite 没有布尔型，用 INTEGER 0/1 表示；==1 即 true
            rs.getInt("auto_pass") == 1,
            rs.getInt("enabled") == 1,
            rs.getInt("tap_count")
    );

    // 初始化：建表 → 补列迁移 → 空库播种 → 数据迁移，应用启动时执行一次
    private void init() {
        // ---- 第一步：建表（IF NOT EXISTS 保证重复启动幂等）----
        // 字段语义：id=自增主键；direction=上报方向(APPTOMG/MGTOAPP)；grp=分组名；
        // seq=组内序号（决定展示/执行顺序）；state_name=状态名（上报的 state 字段，业务唯一键）；
        // resource_key=自动触发时定位按钮的控件 resource-id；description=测试点名称（展示用）；
        // data_schema=上报数据字段说明；trigger_type=触发方式(AUTO 自动/MANUAL 手动)；
        // keyword=日志过滤关键字；prompt=手动触发时的操作提示；judge_mode=判定方式(AUTO/MANUAL)；
        // auto_pass=是否自动通过(0/1)；enabled=是否启用(0/1)；tap_count=自动触发点击次数
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS test_point (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  direction TEXT NOT NULL,
                  grp TEXT NOT NULL,
                  seq INTEGER NOT NULL,
                  state_name TEXT NOT NULL,
                  resource_key TEXT,
                  description TEXT,
                  data_schema TEXT,
                  trigger_type TEXT NOT NULL,
                  keyword TEXT,
                  prompt TEXT,
                  judge_mode TEXT NOT NULL,
                  auto_pass INTEGER DEFAULT 0,
                  enabled INTEGER DEFAULT 1,
                  tap_count INTEGER DEFAULT 1
                )
                """);
        // ---- 第二步：补列迁移（v0.7/v0.8，均为幂等）----
        // v0.7 迁移：旧表无 resource_key 列则补加（幂等）
        // SQLite 的 ALTER TABLE ADD COLUMN 不支持 IF NOT EXISTS，只能 try/catch：
        // 列已存在时抛异常被吞掉，效果等同"补一次，重复执行也无害"
        try {
            jdbc.execute("ALTER TABLE test_point ADD COLUMN resource_key TEXT");
        } catch (Exception ignore) {
            // 列已存在，忽略
        }
        // v0.8 迁移：旧表无 tap_count 列则补加（幂等）
        try {
            jdbc.execute("ALTER TABLE test_point ADD COLUMN tap_count INTEGER DEFAULT 1");
        } catch (Exception ignore) {
            // 列已存在，忽略
        }
        // ---- 第三步：空库播种 ----
        // 表里一条数据都没有（全新库）时写入内置种子，保证开箱即用
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM test_point", Integer.class);
        if (count == null || count == 0) {
            seed();
        }
        // ---- 第四步：数据迁移 ----
        // v0.4 迁移：mgtoapp 统一人工判定（幂等，覆盖旧数据）
        // 早期 mgtoapp 可能配置过自动判定，但人工操作 + 日志核对更可靠，统一改为 MANUAL 且不自动通过
        jdbc.update("UPDATE test_point SET auto_pass=0, judge_mode='MANUAL' WHERE direction='MGTOAPP'");
        // v0.8 迁移：apptomg 测试点重置为最新种子（幂等）。以存在 tap_count=2（振动/音效/音乐）作为「已迁移」标记。
        migrateApptomg();
        // v1.7 / v1.9 迁移：移除 apptomg 里建错了触发方式的「结束游戏」测试点（幂等）。
        // 必须排在 migrateApptomg 之后：若上一步刚重置种子（新种子里已经没有该点），本步删不到东西也无需报错。
        deleteLegacyApptomgEndGame();
        // v1.9 迁移：给老库补上 mgtoapp 的「结束游戏」测试点（幂等）。
        // 「结束游戏」是人工操作（点头像 → 弹窗里选），只可能走 mgtoapp 人工走查，
        // 因此从 v1.9 起它只存在于 mgtoapp 侧，apptomg 侧不再有同名记录。
        insertMgEndGameIfMissing();
    }

    /**
     * v1.9 幂等迁移：给 mgtoapp 补上「结束游戏」测试点。
     *
     * <p>【为什么它是 mgtoapp 的点】用户 2026-10-03 明确：结束游戏 = 「点左上角麦位玩家头像 →
     * 在弹窗里选『结束游戏』」。这是人手在手机上做的动作，而 apptomg 是"平台自动点按钮"的方向
     * —— 平台没法自动完成这种带弹窗的两步操作。mgtoapp 本来就是人工走查（操作员动手、
     * 平台只盯日志给判定建议），放这里才放得对。
     *
     * <p>【为什么必须有这条迁移】{@link #migrateApptomg()} 只管 apptomg，mgtoapp 的种子
     * 只在"整库为空"时写过一次 → 只往种子代码里加、不动老库，那条记录永远补不进去。
     *
     * <p>【怎么判断该补】按 direction + state_name 判存在，已存在则什么都不做。
     * 用 state_name 而不是 resource_key：mgtoapp 的 resource_key 与 state_name 同值，
     * 用前者更贴近"这个测试点在测哪个状态"的语义。
     *
     * <p>【不覆盖用户改过的内容】已存在时完全不动 —— mgtoapp 的测试点可以在「测试点管理」页
     * 改备注/改名称，迁移不该把用户的修改冲掉。
     */
    private void insertMgEndGameIfMissing() {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_point WHERE direction='MGTOAPP' AND state_name=?",
                Integer.class, END_GAME_STATE);
        if (n != null && n > 0) {
            return;
        }
        // 序号接在该组现有测试点之后，避免与既有顺序冲突
        Integer maxSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(seq),0) FROM test_point WHERE direction='MGTOAPP' AND grp=?",
                Integer.class, MG_GAME_GROUP);
        int seq = (maxSeq == null ? 0 : maxSeq) + 1;
        insert(mgEndGamePoint(seq));
        log.info("已补入 mgtoapp「结束游戏」测试点（人工操作：点头像 → 弹窗选结束游戏，grp={}, seq={}）",
                MG_GAME_GROUP, seq);
    }

    // v0.8 的 apptomg 种子升级：v0.8 新增了振动/音效/背景音乐三个 tap_count=2 的测试点。
    // 判定"是否已迁移"的标准：库里是否存在 tap_count=2 的 apptomg 记录；
    // 若不存在说明还是旧种子，直接删掉旧 apptomg 数据并重灌最新种子（幂等：跑过一次后不再触发）
    private void migrateApptomg() {
        // 统计 tap_count=2 的 apptomg 记录数（0 或 null 表示未迁移）
        Integer hasTap2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM test_point WHERE direction='APPTOMG' AND tap_count=2",
                Integer.class);
        if (hasTap2 == null || hasTap2 == 0) {
            // 旧数据整体清空（id 会重新自增），再写入最新种子
            jdbc.update("DELETE FROM test_point WHERE direction='APPTOMG'");
            seedApptomg();
        }
    }

    /**
     * v1.7 / v1.9 幂等迁移：删除 apptomg 里的「结束游戏」测试点。
     *
     * <p>【为什么删】用户 2026-10-03 澄清：结束游戏**不是**点大厅那个黄色主按钮触发的，
     * 而是「点左上角麦位玩家头像 → 在弹窗里选『结束游戏』」两步操作。
     * 平台自动点不了这种带弹窗的两步操作，所以这个状态不该出现在 apptomg（自动点击）方向，
     * 只该由 mgtoapp（人工走查）覆盖 —— 见 {@link #insertMgEndGameIfMissing()}。
     * 历史上先后失败过两次：v1.5 #24 建的那条假设「与开始游戏同位、一次点击」，
     * v1.8 #30 改成两步坐标序列仍不成立（仍假设平台能自动点）。两条都要清掉。
     *
     * <p>【为什么按 state_name 删，而不是按 resource_key】历史上两条记录的 resource_key
     * 并不相同（{@code app_common_self_end} / {@code app_common_self_end_game}），
     * 但它们的 state_name 都是 {@link #END_GAME_STATE}。按状态名删一次就能同时覆盖新旧两条，
     * 也不需要再为"第二个键"加常量。
     *
     * <p>【删除条件为什么这么写】两个条件缺一不可：
     * <ul>
     *   <li>{@code direction='APPTOMG'} —— <b>最关键的一条</b>。v1.9 起 mgtoapp 侧有一条
     *       同样 state_name 的「结束游戏」测试点，{@code END_GAME_STATE} 两处共用。
     *       去掉方向条件就会把 mgtoapp 那条正常记录误删，而且每次启动都删一遍。</li>
     *   <li>{@code trigger_type='AUTO'} —— apptomg 侧被删的都是自动触发的；
     *       mgtoapp 那条是 MANUAL。再加一层保险，避免将来有人手抖把方向写错。</li>
     * </ul>
     *
     * <p>【只删测试点，不动 test_result】历史判定结果全部保留 —— {@code test_result} 里已存
     * {@code point_id} 与 {@code point_name} 快照，删除测试点不应连带抹掉跑过的历史。
     *
     * <p>幂等：按上述条件删，删不到即什么都不做，重复启动无副作用。
     */
    private void deleteLegacyApptomgEndGame() {
        int removed = jdbc.update(
                "DELETE FROM test_point WHERE direction='APPTOMG' AND trigger_type='AUTO' AND state_name=?",
                END_GAME_STATE);
        if (removed > 0) {
            log.info("已移除 apptomg「结束游戏」测试点 {} 条（平台自动点不了：真实触发是「点头像 → 弹窗选」，"
                    + "该状态改由 mgtoapp 人工走查覆盖）", removed);
        }
    }

    /** 内置三类测试点（状态名/字段来自公开 SDK 文档）。 */
    // 种子数量随版本变化，此处不再写死总数（历史上写死过，且与代码对不上）。
    // 数据与官方 SDK 文档中的状态/字段一一对应，是平台"开箱即用"的默认测试集
    private void seed() {
        seedApptomg();
        seedMgtoapp();
    }

    /** apptomg 测试点：按本地配置点击，并核对匹配到的状态日志。 */
    // 运行前需要用户配置设备、资源定位与前置界面；日志判定仅覆盖已实现的字段规则。
    // 需要额外人工操作的场景交由手动触发流程处理。
    private void seedApptomg() {
        // 用局部列表统一收集，最后批量 insert，代码更紧凑
        List<TestPoint> pts = new ArrayList<>();
        // p(...) 参数依次为：方向、分组、组内序号、状态名、定位配置键、测试点名称、上报数据说明、点击次数
        // *_cancel 是本地定位配置键，对应公开状态的 false 分支，不是额外的 SDK 状态。
        // 第 1~5 条：基础状态切换（加入/退出、准备/取消准备、开始游戏），每次点击 1 次
        pts.add(p(Direction.APPTOMG, "apptomg_common", 1, "app_common_self_in", "app_common_self_in",
                "加入游戏", "isIn=true（加入游戏）", 1));
        pts.add(p(Direction.APPTOMG, "apptomg_common", 2, "app_common_self_in", "app_common_self_in_cancel",
                "退出游戏", "isIn=false（退出游戏）", 1));
        pts.add(p(Direction.APPTOMG, "apptomg_common", 3, "app_common_self_ready", "app_common_self_ready",
                "准备", "isReady=true（准备）", 1));
        pts.add(p(Direction.APPTOMG, "apptomg_common", 4, "app_common_self_ready", "app_common_self_ready_cancel",
                "取消准备", "isReady=false（取消准备）", 1));
        pts.add(p(Direction.APPTOMG, "apptomg_common", 5, "app_common_self_playing", "app_common_self_playing",
                "开始游戏", "isPlaying=true（开始游戏）", 1));
        // 第 6~8 条：振动/音效/背景音乐开关。这类开关点一下是"开"、再点一下是"关"，
        // 需要连点两次才能覆盖两种上报，因此 tap_count=2（这也是 v0.8 迁移的判定标记）
        pts.add(p(Direction.APPTOMG, "apptomg_common", 6, "app_common_open_vibrate", "app_common_open_vibrate",
                "振动", "点两次：isOn=true(开) / isOn=false(关)", 2));
        pts.add(p(Direction.APPTOMG, "apptomg_common", 7, "app_common_open_sound", "app_common_open_sound",
                "音效", "点两次：isOn=true(开) / isOn=false(关)", 2));
        pts.add(p(Direction.APPTOMG, "apptomg_common", 8, "app_common_open_bg_music", "app_common_open_bg_music",
                "背景音乐", "点两次：isOn=true(开) / isOn=false(关)", 2));
        // 第 9~10 条：添加 AI 玩家 / 重连，无需附加数据（data_schema 为 {}）
        pts.add(p(Direction.APPTOMG, "apptomg_common", 9, "app_common_game_add_ai_players", "app_common_game_add_ai_players",
                "添加AI玩家", "{}", 1));
        pts.add(p(Direction.APPTOMG, "apptomg_common", 10, "app_common_game_reconnect", "app_common_game_reconnect",
                "重连", "{}", 1));
        // 统一逐条入库（insert 内部负责取回自增 id）
        for (TestPoint tp : pts) {
            insert(tp);
        }
    }

    /** mgtoapp 测试点（手动触发），12 游戏态 + 5 玩家态。 */
    // mgtoapp 方向：小游戏侧（MG）状态 → 上报给 App。小游戏内没有通用控件 id 可供自动点击，
    // 因此这些测试点由测试人员在手机上手动操作触发，平台监听 logcat 给出判定建议（人工确认）
    private void seedMgtoapp() {
        List<TestPoint> pts = new ArrayList<>();
        // ---- mgtoapp 通用状态-游戏（手动触发） ----
        // m(...) 参数依次为：方向、分组、组内序号、状态名、测试点名称、上报数据说明、
        // autoPass（是否自动通过，这里全部为 false = 人工判定）、操作提示文案 prompt
        // 第 1~11 条：游戏类状态（加入/退出、准备、结算、弹框、帮助、网络等）
        pts.add(m(Direction.MGTOAPP, MG_GAME_GROUP, 1, "mg_common_self_click_join_btn", "加入游戏按钮点击", "seatIndex", false,
                "请在游戏内点击【加入游戏】按钮"));
        pts.add(m(Direction.MGTOAPP, MG_GAME_GROUP, 2, "mg_common_self_click_cancel_join_btn", "取消加入(退出)按钮点击", "{}", false,
                "请在游戏内点击【取消加入/退出】按钮"));
        pts.add(m(Direction.MGTOAPP, MG_GAME_GROUP, 3, "mg_common_self_click_ready_btn", "准备按钮点击", "{}", false,
                "请在游戏内点击【准备】按钮"));
        pts.add(m(Direction.MGTOAPP, MG_GAME_GROUP, 4, "mg_common_self_click_cancel_ready_btn", "取消准备按钮点击", "{}", false,
                "请在游戏内点击【取消准备】按钮"));
        pts.add(m(Direction.MGTOAPP, MG_GAME_GROUP, 5, "mg_common_game_state", "游戏状态", "gameState 0/1/2", false,
                "请在游戏内触发游戏状态变化"));
        pts.add(m(Direction.MGTOAPP, MG_GAME_GROUP, 6, "mg_common_self_click_game_settle_close_btn", "结算界面关闭按钮点击", "{}", false,
                "请在游戏结算界面点击【关闭】"));
        pts.add(m(Direction.MGTOAPP, MG_GAME_GROUP, 7, "mg_common_self_click_game_settle_again_btn", "结算界面再来一局按钮点击", "{}", false,
                "请在游戏结算界面点击【再来一局】"));
        pts.add(m(Direction.MGTOAPP, MG_GAME_GROUP, 8, "mg_common_app_common_self_x_resp", "App通用状态操作结果错误码", "只过滤有无", false,
                "请触发一次错误操作（如单人点开始）"));
        pts.add(m(Direction.MGTOAPP, MG_GAME_GROUP, 9, "mg_common_game_network_state", "网络连接状态", "state 0/1（进游戏即上报）", false,
                "进入游戏即可"));
        pts.add(m(Direction.MGTOAPP, MG_GAME_GROUP, 10, "mg_common_alert", "游戏弹框", "state show/close", false,
                "请触发一个游戏弹窗"));
        pts.add(m(Direction.MGTOAPP, MG_GAME_GROUP, 11, "mg_common_click_help_btn", "帮助按钮点击", "{}", false,
                "请在游戏内点击【帮助】按钮"));
        // 第 12 条：结束游戏。触发方式是「点左上角麦位玩家头像 → 在弹窗里选结束游戏」，
        // 只能人工做，因此归在 mgtoapp 人工走查方向（v1.9 从 apptomg 挪过来）。
        pts.add(mgEndGamePoint(12));

        // ---- mgtoapp 通用状态-玩家（手动触发） ----
        // 第 13~17 条：玩家类状态（加入/准备/队长/游戏状态/头像点击）
        pts.add(m(Direction.MGTOAPP, "mgtoapp_player", 1, "mg_common_player_in", "加入状态", "按文档", false,
                "请完成加入游戏操作"));
        pts.add(m(Direction.MGTOAPP, "mgtoapp_player", 2, "mg_common_player_ready", "准备状态", "isReady", false,
                "请完成准备/取消准备操作"));
        pts.add(m(Direction.MGTOAPP, "mgtoapp_player", 3, "mg_common_player_captain", "队长状态", "isCaptain", false,
                "请触发队长变更"));
        pts.add(m(Direction.MGTOAPP, "mgtoapp_player", 4, "mg_common_player_playing", "游戏状态",
                "isPlaying / gameRoundId / reason / spaceMax", false, "请开始/结束游戏"));
        pts.add(m(Direction.MGTOAPP, "mgtoapp_player", 5, "mg_common_self_click_game_player_icon", "点击玩家头像(仅飞行棋)",
                "uid", false, "请在游戏内点击玩家头像"));

        // 统一逐条入库
        for (TestPoint tp : pts) {
            insert(tp);
        }
    }

    /**
     * 「结束游戏」测试点的唯一构造处（种子与迁移共用，避免两处写法分叉）。
     *
     * <p>它是 mgtoapp 的人工走查点：操作员按 prompt 在手机上点完，平台只过滤日志给判定建议，
     * 判定由人来做（{@code autoPass=false}）。
     *
     * @param seq 组内序号
     */
    private static TestPoint mgEndGamePoint(int seq) {
        return m(Direction.MGTOAPP, MG_GAME_GROUP, seq, END_GAME_STATE, "结束游戏",
                "isPlaying=false（结束游戏）", false,
                "请在游戏内点击左上角麦位玩家头像，然后在弹窗中选择【结束游戏】");
    }

    // 便捷工厂：构造一条 apptomg 种子测试点（固定 AUTO 触发 + AUTO 判定）。
    // 约定：keyword 与 state_name 相同（按状态名作为日志过滤关键字）；
    // prompt 置 null（自动触发不需要人工提示）；enabled 固定 true
    private static TestPoint p(Direction d, String grp, int seq, String state, String resourceKey, String desc,
                               String schema, int tapCount) {
        return new TestPoint(null, d, grp, seq, state, resourceKey, desc, schema, TriggerType.AUTO, state, null,
                JudgeMode.AUTO, false, true, tapCount);
    }

    // 便捷工厂：构造一条 mgtoapp 种子测试点（固定 MANUAL 触发）。
    // 注意这里 resource_key 与 keyword 都填 state（状态名）；判定方式由 autoPass 决定：
    // true → AUTO 自动判定，false → MANUAL 人工判定；点击次数固定 1
    private static TestPoint m(Direction d, String grp, int seq, String state, String desc, String schema,
                               boolean autoPass, String prompt) {
        JudgeMode jm = autoPass ? JudgeMode.AUTO : JudgeMode.MANUAL;
        return new TestPoint(null, d, grp, seq, state, state, desc, schema, TriggerType.MANUAL, state, prompt, jm,
                autoPass, true, 1);
    }

    /** 查询全部测试点，按 方向 → 分组 → 组内序号 排序（管理页面列表用）。 */
    public List<TestPoint> findAll() {
        // ORDER BY 三键排序：先按方向（APPTOMG 在 MGTOAPP 前），再按组名，最后按组内序号
        return jdbc.query("SELECT * FROM test_point ORDER BY direction, grp, seq", MAPPER);
    }

    /** 查询某方向下启用的测试点（enabled=1），按 分组 → 序号 排序（执行引擎用）。 */
    public List<TestPoint> findEnabledByDirection(Direction direction) {
        // ? 占位符预编译传参；direction.name() 与库中存储的枚举字符串一致
        return jdbc.query("SELECT * FROM test_point WHERE direction=? AND enabled=1 ORDER BY grp, seq", MAPPER,
                direction.name());
    }

    /** 返回某方向全部测试点（含禁用），供管理表格展示。 */
    // @param direction 上报方向（APPTOMG / MGTOAPP）
    // @return 该方向全部测试点；与 findEnabledByDirection 不同，禁用的也返回（管理页需要看到并可重新启用）
    public List<TestPoint> findAllByDirection(Direction direction) {
        return jdbc.query("SELECT * FROM test_point WHERE direction=? ORDER BY grp, seq", MAPPER, direction.name());
    }

    /** 某组下一个 seq（= 当前组最大 seq + 1）。 */
    public int nextSeq(String grp) {
        // COALESCE(MAX(seq),0)：组内还没有记录时 MAX 返回 NULL，用 COALESCE 兜底成 0，+1 后即从 1 开始
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0) FROM test_point WHERE grp=?", Integer.class, grp);
        // max 理论上不会为 null（COALESCE 已兜底），再判一次纯属防御
        return (max == null ? 0 : max) + 1;
    }

    /** 更新 mgtoapp 测试点的 state / 备注 / 测试点名称（keyword/state_name/prompt/description 同步）。 */
    public void updateMg(long id, String state, String note, String shortName) {
        // 一条 UPDATE 同时同步四个字段：state_name 与 keyword 都写 state（状态名即过滤关键字）、
        // prompt 写备注、description 写展示短名，保证各字段语义始终一致
        jdbc.update("UPDATE test_point SET state_name=?, keyword=?, prompt=?, description=? WHERE id=?",
                state, state, note, shortName, id);
    }

    /**
     * 插入一条测试点，并返回带数据库自增主键 id 的完整对象。
     *
     * @param tp 待插入的测试点（id 字段可为 null，由数据库生成）
     * @return 带真实 id 的 TestPoint（其余字段与入参一致）
     */
    public TestPoint insert(TestPoint tp) {
        // 必须同一连接内 INSERT + 取 last_insert_rowid，否则 DriverManagerDataSource 每调一次开新连接，返回 id 恒为 0
        // 背景：项目数据源是 DriverManagerDataSource（每次操作都开新连接），若先 insert 再在另一条连接上查
        // last_insert_rowid()，得到的一律是 0；所以用 ConnectionCallback 把"插入"和"取自增 id"两步
        // 包进框架提供的同一条连接里顺序执行，才能拿到刚插入那行的真实 id
        Long id = jdbc.execute((ConnectionCallback<Long>) con -> {
            // 预编译 INSERT：14 个 ? 占位符，参数按列顺序依次 set
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO test_point(direction,grp,seq,state_name,resource_key,description,data_schema,trigger_type,keyword,prompt,judge_mode,auto_pass,enabled,tap_count)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                // 枚举统一存 name() 字符串
                ps.setString(1, tp.direction().name());
                ps.setString(2, tp.group());
                ps.setInt(3, tp.seq());
                ps.setString(4, tp.stateName());
                ps.setString(5, tp.resourceKey());
                ps.setString(6, tp.description());
                ps.setString(7, tp.dataSchema());
                ps.setString(8, tp.triggerType().name());
                ps.setString(9, tp.keyword());
                ps.setString(10, tp.prompt());
                ps.setString(11, tp.judgeMode().name());
                // 布尔转 0/1 存入 INTEGER 列
                ps.setInt(12, tp.autoPass() ? 1 : 0);
                ps.setInt(13, tp.enabled() ? 1 : 0);
                ps.setInt(14, tp.tapCount());
                ps.executeUpdate();
            }
            // 在同一条连接上取 SQLite 的自增主键：last_insert_rowid() 返回本连接最近一次 INSERT 的行 id
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getLong(1);
            }
        });
        // 用数据库生成的 id 重建对象返回（record 不可变，只能整体重建）
        return withId(tp, id);
    }

    /**
     * 全字段更新一条测试点。
     *
     * @param tp 含 id 的完整测试点对象；除 id 外的所有字段都会覆盖写回数据库
     */
    public void update(TestPoint tp) {
        // 与 insert 相同的列清单，WHERE id=? 精确定位要更新的行（15 个占位符）
        jdbc.update("""
                        UPDATE test_point SET direction=?,grp=?,seq=?,state_name=?,resource_key=?,description=?,data_schema=?,
                        trigger_type=?,keyword=?,prompt=?,judge_mode=?,auto_pass=?,enabled=?,tap_count=? WHERE id=?
                        """,
                tp.direction().name(), tp.group(), tp.seq(), tp.stateName(), tp.resourceKey(), tp.description(),
                tp.dataSchema(), tp.triggerType().name(), tp.keyword(), tp.prompt(), tp.judgeMode().name(),
                tp.autoPass() ? 1 : 0, tp.enabled() ? 1 : 0, tp.tapCount(), tp.id());
    }

    /** 按主键删除一条测试点（管理页删除按钮调用）。 */
    public void delete(Long id) {
        jdbc.update("DELETE FROM test_point WHERE id=?", id);
    }

    // 工具方法：用新 id 重建一个 TestPoint 副本（record 不可变，只能整体 new）
    private static TestPoint withId(TestPoint tp, Long id) {
        return new TestPoint(id, tp.direction(), tp.group(), tp.seq(), tp.stateName(), tp.resourceKey(), tp.description(),
                tp.dataSchema(), tp.triggerType(), tp.keyword(), tp.prompt(), tp.judgeMode(), tp.autoPass(),
                tp.enabled(), tp.tapCount());
    }
}
