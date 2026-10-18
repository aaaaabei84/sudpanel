package com.sudpanel.perf;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * M6 性能测试持久化。5 张表：perf_game / perf_run / perf_loop / perf_sample / perf_check_result。
 * INSERT 取 id 一律同一连接内完成（DriverManagerDataSource 非连接池的老坑，见 M2 教训）。
 */
@Repository
public class PerfRepository {

    private final JdbcTemplate jdbc;

    /**
     * 构造器：注入 Spring 的 JdbcTemplate，并在构造时完成建表。
     * 建表放在构造器里可保证"Repository 一被使用，表一定已就绪"。
     */
    public PerfRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        initTables();
        new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()))
                .executeWithoutResult(status -> {
                    PerfRunFacts.initialize(jdbc);
                    boolean qualityColumn=jdbc.queryForList("PRAGMA table_info(perf_sample)").stream().anyMatch(row->"quality_json".equals(row.get("name")));
                    if(!qualityColumn)jdbc.execute("ALTER TABLE perf_sample ADD COLUMN quality_json TEXT");
                    jdbc.execute("CREATE TABLE IF NOT EXISTS perf_loop_quality(loop_id INTEGER PRIMARY KEY,diagnostics_json TEXT NOT NULL)");
                    jdbc.execute("CREATE TRIGGER IF NOT EXISTS tr_perf_quality_delete_loop AFTER DELETE ON perf_loop BEGIN DELETE FROM perf_loop_quality WHERE loop_id=OLD.id; END");
                    addLoopColumn("api_env_id", "INTEGER");
                    addLoopColumn("api_env_name", "TEXT");
                    addLoopColumn("api_config_captured_at", "TEXT");
                    addLoopColumn("round_id_evidence", "TEXT");
                    addLoopColumn("plan_hash", "TEXT");
                    jdbc.execute("CREATE TABLE IF NOT EXISTS perf_loop_cleanup(loop_id INTEGER PRIMARY KEY,run_id INTEGER NOT NULL,game_id INTEGER,status TEXT NOT NULL,reason TEXT,evidence_missing INTEGER NOT NULL DEFAULT 0,updated_at TEXT NOT NULL)");
                    jdbc.execute("CREATE INDEX IF NOT EXISTS ix_perf_run_status_date ON perf_run(status,start_time,id)");
                    jdbc.execute("CREATE TABLE IF NOT EXISTS perf_settlement_check(id INTEGER PRIMARY KEY AUTOINCREMENT,run_id INTEGER NOT NULL,loop_id INTEGER NOT NULL,summary_json TEXT NOT NULL,created_at TEXT NOT NULL)");
                    jdbc.execute("CREATE TRIGGER IF NOT EXISTS tr_settlement_delete_run AFTER DELETE ON perf_run BEGIN DELETE FROM perf_settlement_check WHERE run_id=OLD.id; END");
                    jdbc.execute("CREATE TRIGGER IF NOT EXISTS tr_settlement_delete_loop AFTER DELETE ON perf_loop BEGIN DELETE FROM perf_settlement_check WHERE loop_id=OLD.id; END");
                    jdbc.execute("CREATE TABLE IF NOT EXISTS perf_run_plan(run_id INTEGER PRIMARY KEY,plan_json TEXT NOT NULL,plan_hash TEXT NOT NULL,captured_at TEXT NOT NULL)");
                    jdbc.execute("CREATE TRIGGER IF NOT EXISTS tr_perf_plan_delete AFTER DELETE ON perf_run BEGIN DELETE FROM perf_run_plan WHERE run_id=OLD.id; END");

                });
    }

    public PerfRunFacts facts() { return new PerfRunFacts(jdbc); }

    private void addLoopColumn(String name, String type) {
        boolean exists = jdbc.queryForList("PRAGMA table_info(perf_loop)").stream()
                .anyMatch(row -> name.equals(row.get("name")));
        if (!exists) {
            try { jdbc.execute("ALTER TABLE perf_loop ADD COLUMN " + name + " " + type); }
            catch (org.springframework.dao.DataAccessException e) {
                if (e.getMessage() == null || !e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("duplicate column")) throw e;
            }
        }
    }

    /** 只记录本圈开局请求实际使用的环境，不反推旧记录。 */
    public void markLoopApiEnvironment(long loopId, Long envId, String envName, String capturedAt) {
        if (envId == null) return;
        jdbc.update("UPDATE perf_loop SET api_env_id=?,api_env_name=?,api_config_captured_at=? WHERE id=?",
                envId, envName, capturedAt, loopId);
    }

    /**
     * 建表/迁移（幂等）：5 张表全部 CREATE TABLE IF NOT EXISTS，每次启动都执行，重复跑不报错。
     * 若后续表结构演进，在这里追加 ALTER 迁移语句即可。
     */
    private void initTables() {
        // 表1 perf_game：被测游戏主档。name 唯一（按完整名去重），sort_order 控制前端展示顺序（可 NULL）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS perf_game (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT UNIQUE NOT NULL,
                  short_name TEXT,
                  created_at TEXT,
                  sort_order INTEGER
                )
                """);
        // 旧库兼容：早期版本没有 sort_order 列，建表后尝试补列。
        // SQLite 不支持 "ADD COLUMN IF NOT EXISTS"，只能用 try/catch 吞掉"列已存在"异常来模拟幂等
        // 旧表迁移：补 sort_order 列（已存在则忽略）
        try {
            jdbc.execute("ALTER TABLE perf_game ADD COLUMN sort_order INTEGER");
        } catch (Exception ignore) {
            // 列已存在
        }
        /*
         * 旧表迁移：补 mg_id / app_id 两列（2026-10-06，需求 #68）。
         *
         * <p>这两列是「每个游戏各自的推送身份」：人机对局模式往房间里推指令时，
         * 要把当前游戏的这两个值注入请求体（用例里写的是 {{mg_id}} / {{app_id}}）。
         * 之所以挂在游戏主档上而不是全局一份：不同游戏的 mg_id / app_id 本来就不同，
         * 全局存一份意味着每换一个游戏都得手工改一次接口测试的变量 —— 那正是这次要消掉的麻烦。
         *
         * <p>允许为空：只有走人机对局模式的游戏才需要填，其余模式不碰这两列。
         */
        try {
            jdbc.execute("ALTER TABLE perf_game ADD COLUMN mg_id TEXT");
        } catch (Exception ignore) {
            // 列已存在
        }
        try {
            jdbc.execute("ALTER TABLE perf_game ADD COLUMN app_id TEXT");
        } catch (Exception ignore) {
            // 列已存在
        }
        /*
         * 旧表迁移：补 ai_count / body_override 两列（2026-10-07，需求 #69）。
         *
         * <p>ai_count = 这个游戏推几个人（null=没配，退回全局 ai_room_count，再没有走用例原文）。
         * 挂在游戏上而不是只用全局一份：单人和多人玩法所需的对局人数不同，
         * 全局一份意味着每次换游戏都得手工改一遍。
         *
         * <p>body_override = 这个游戏的推送体模板（null=没用例原文）。写的是与接口用例同一种
         * 带占位符的模板，整段替换用例请求体 —— 让使用者能逐字段定制推送内容，
         * 而不是只能在「用例原文」和「按数量生成」里二选一。
         */
        try {
            jdbc.execute("ALTER TABLE perf_game ADD COLUMN ai_count INTEGER");
        } catch (Exception ignore) {
            // 列已存在
        }
        try {
            jdbc.execute("ALTER TABLE perf_game ADD COLUMN body_override TEXT");
        } catch (Exception ignore) {
            // 列已存在
        }
        /*
         * 旧表迁移：补 room_id / human_count / ai_level / game_seconds 四列（2026-10-07，需求 #70）。
         *
         * <p>room_id：推送请求里的房间号，应跟随游戏场景配置，避免全局变量误指向其他房间。
         *
         * <p>human_count：名单里真人（is_ai=0）的条数。#69 固定 1 条，#70 起随场景可配。
         *
         * <p>ai_level：人机的对局强度，配了写进每个人机条目；null 沿用用例样板原值。
         *
         * <p>game_seconds：这个游戏「开局后多久收尾」（= 对局内采样时长）。null 用 run 启动
         * 时配的全局值；watchdog 截止会按它同步延长，超过 4 分钟的局不会被误判超时。
         */
        try {
            jdbc.execute("ALTER TABLE perf_game ADD COLUMN room_id TEXT");
        } catch (Exception ignore) {
            // 列已存在
        }
        try {
            jdbc.execute("ALTER TABLE perf_game ADD COLUMN human_count INTEGER");
        } catch (Exception ignore) {
            // 列已存在
        }
        try {
            jdbc.execute("ALTER TABLE perf_game ADD COLUMN ai_level INTEGER");
        } catch (Exception ignore) {
            // 列已存在
        }
        try {
            jdbc.execute("ALTER TABLE perf_game ADD COLUMN game_seconds INTEGER");
        } catch (Exception ignore) {
            // 列已存在
        }
        /*
         * 旧表迁移：补 machine_uid 列（2026-10-07，需求 #71）。
         *
         * <p>本机账号的 uid：名单真人首位直接用这个身份推送（is_ai=0）。使用者要求
         * 「房间里的人全部由接口推送」——真机客户端必须发起一次加入才能收到对局画面
         * （游戏机制），把真人位用本机账号身份推送后，客户端随后的加入被服务端幂等
         * 合并，房间账本上不再有「多出来的手机账号」。null = 真人位沿用 cy01。
         */
        try {
            jdbc.execute("ALTER TABLE perf_game ADD COLUMN machine_uid TEXT");
        } catch (Exception ignore) {
            // 列已存在
        }
        // 表2 perf_run：一次压测运行（一批游戏串行跑）。status 流转：RUNNING → OK/FAILED/INTERRUPTED
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS perf_run (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  device TEXT,
                  mode TEXT NOT NULL,
                  status TEXT NOT NULL,
                  game_queue TEXT,
                  loop_count INTEGER,
                  sample_seconds INTEGER,
                  early_tap_delay_ms INTEGER,
                  start_time TEXT,
                  end_time TEXT
                )
                """);
        /*
         * 旧表迁移：补 ai_count 列（2026-10-06，需求 #68）—— 人机对局模式这一轮推了几个人机。
         *
         * <p>为什么要存：人机数量可以在页面上覆盖用例原文（填了按数量生成、留空用用例里的名单），
         * 于是「用例里写了几个人」与「这一轮实际推了几个人」是两件事。事后复核时若只看得见用例，
         * 就解释不了某一轮为什么只有 4 个人机 —— 那正是会让人怀疑平台坏了的那类疑问。
         */
        try {
            jdbc.execute("ALTER TABLE perf_run ADD COLUMN ai_count INTEGER");
        } catch (Exception ignore) {
            // 列已存在
        }
        // 表3 perf_loop：单游戏单圈。含基线/局内指标、机器判定（verdict/judge_json）与人工复核字段
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS perf_loop (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  run_id INTEGER NOT NULL,
                  game_id INTEGER NOT NULL,
                  loop_no INTEGER NOT NULL,
                  status TEXT NOT NULL,
                  invalid_reason TEXT,
                  baseline_pss_kb INTEGER,
                  baseline_ws_threads INTEGER,
                  in_game_ws_threads INTEGER,
                  pss_avg_kb INTEGER,
                  pss_peak_kb INTEGER,
                  cpu_avg REAL,
                  cpu_peak REAL,
                  cpu_cores INTEGER,
                  cpu_avg_sys REAL,
                  cpu_peak_sys REAL,
                  verdict TEXT,
                  judge_json TEXT,
                  human_verdict TEXT,
                  human_note TEXT,
                  start_time TEXT,
                  end_time TEXT,
                  baseline_java_kb INTEGER,
                  baseline_native_kb INTEGER,
                  baseline_graphics_kb INTEGER,
                  baseline_code_kb INTEGER,
                  baseline_private_other_kb INTEGER,
                  baseline_system_kb INTEGER
                )
                """);
        // 表4 perf_sample：单圈内逐秒采样（PSS/RSS/CPU/线程数/ws 线程数），是单圈曲线的最小数据单元
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS perf_sample (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  loop_id INTEGER NOT NULL,
                  seq INTEGER,
                  pss_kb INTEGER,
                  rss_kb INTEGER,
                  cpu_percent REAL,
                  cpu_cores INTEGER,
                  thread_count INTEGER,
                  ws_thread_count INTEGER,
                  net_conn INTEGER,
                  ts TEXT,
                  java_heap_kb INTEGER,
                  native_heap_kb INTEGER,
                  graphics_kb INTEGER,
                  code_kb INTEGER,
                  private_other_kb INTEGER,
                  system_kb INTEGER
                )
                """);        // 表5 perf_check_result：游戏级自动判定结果；check_no 对应 PerfJudge 的检测项编号（1/3/4/5）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS perf_check_result (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  run_id INTEGER NOT NULL,
                  game_id INTEGER NOT NULL,
                  check_no INTEGER NOT NULL,
                  check_name TEXT,
                  verdict TEXT,
                  evidence TEXT,
                  human_verdict TEXT,
                  human_note TEXT
                )
                """);
        // 老库迁移：perf_sample 补 net_conn 列（方案 C 网络层证据）、perf_loop 补 battery_level 列（电量监控）
        // 列已存在时 ALTER 报错，忽略即可
        try {
            jdbc.execute("ALTER TABLE perf_sample ADD COLUMN net_conn INTEGER");
        } catch (Exception ignore) {
        }
        try {
            jdbc.execute("ALTER TABLE perf_loop ADD COLUMN battery_level INTEGER");
        } catch (Exception ignore) {
        }
        // 老库迁移（2026-10-03，v1.5 #21）：CPU 整机口径 —— perf_loop 补核数与整机均值/峰值，
        // perf_sample 补每点核数。老数据这些列为 NULL：判定与展示必须容忍（回退单核口径并标注）。
        try {
            jdbc.execute("ALTER TABLE perf_loop ADD COLUMN cpu_cores INTEGER");
        } catch (Exception ignore) {
        }
        try {
            jdbc.execute("ALTER TABLE perf_loop ADD COLUMN cpu_avg_sys REAL");
        } catch (Exception ignore) {
        }
        try {
            jdbc.execute("ALTER TABLE perf_loop ADD COLUMN cpu_peak_sys REAL");
        } catch (Exception ignore) {
        }
        // 老库迁移（2026-10-09，v1.9.4 #78）：perf_loop 补「对局提前结束」标记。
        // 1 = 检测到上报里的对局销毁事件、本圈提前收尾；NULL/0 = 满时长跑完。
        // 老数据全为 NULL —— 展示与判定必须容忍「这一列还没数据」。
        try {
            jdbc.execute("ALTER TABLE perf_loop ADD COLUMN early_end INTEGER");
        } catch (Exception ignore) {
        }
        // 老库迁移（2026-10-10，v1.10.0 #84）：perf_loop 补「触发系统回收后的基线」7 列。
        //
        // 增加系统回收后的第二套基线，辅助观察可回收部分与持续增长。
        // 两次采样的差值表示本次回收前后的变化，不能单独确认或排除泄漏。
        // 开关默认关闭（perf_setting.perf_trim_baseline），所以老数据这些列全是 NULL，
        // 展示与判定必须容忍「这一版还没有第二套基线」。
        try {
            jdbc.execute("ALTER TABLE perf_loop ADD COLUMN trimmed_pss_kb INTEGER");
        } catch (Exception ignore) {
        }
        for (String col : new String[]{"trimmed_java_kb", "trimmed_native_kb", "trimmed_graphics_kb",
                "trimmed_code_kb", "trimmed_private_other_kb", "trimmed_system_kb"}) {
            try {
                jdbc.execute("ALTER TABLE perf_loop ADD COLUMN " + col + " INTEGER");
            } catch (Exception ignore) {
            }
        }
        // 老库迁移（2026-10-10，v1.10.4 需求 #86）：每圈记录**真实对局的 roundId**。
        //
        // 圈号是我们自己数的，只在报告内部有意义；roundId 才是跟服务端对局记录对得上的那个标识。
        // 有了它，报告里任何一圈异常都能直接拿去查服务端的对局数据。
        // 只有人机对局模式会写这一列（它每圈开局前清 logcat，取到的才是本局的），
        // 其余模式为 NULL —— 展示与导出必须容忍。
        try {
            jdbc.execute("ALTER TABLE perf_loop ADD COLUMN game_round_id TEXT");
        } catch (Exception ignore) {
        }
        try {
            jdbc.execute("ALTER TABLE perf_sample ADD COLUMN cpu_cores INTEGER");
        } catch (Exception ignore) {
        }
        // 老库迁移（2026-09-28，v1.2 #14）：perf_sample 补 meminfo App Summary 分项列（6 项）。
        // 列已存在时 ALTER 报错，忽略即可 —— 与上面两处同款幂等写法；历史 run 的这些列保持 NULL，
        // 展示与判定必须能容忍「部分圈有值、部分圈为 NULL」。
        for (String col : new String[]{"java_heap_kb", "native_heap_kb", "graphics_kb",
                "code_kb", "private_other_kb", "system_kb"}) {
            try {
                jdbc.execute("ALTER TABLE perf_sample ADD COLUMN " + col + " INTEGER");
            } catch (Exception ignore) {
            }
        }
        // 同上：perf_loop 补「关游戏后基线」的分项列（6 项）。
        // 基线分项是定位泄漏类型的**关键证据**：基线逐圈抬高说明退出后内存未归还，
        // 再看是哪一类（Native / Java / Graphics …）在涨即可锁定方向。
        for (String col : new String[]{"baseline_java_kb", "baseline_native_kb", "baseline_graphics_kb",
                "baseline_code_kb", "baseline_private_other_kb", "baseline_system_kb"}) {
            try {
                jdbc.execute("ALTER TABLE perf_loop ADD COLUMN " + col + " INTEGER");
            } catch (Exception ignore) {
            }
        }

        // 表6 perf_setting：性能模块的运行参数（2026-10-06，需求 #68）。
        //
        // 只放「页面上选的、要跨会话记住的」那几项，当前是人机对局模式的四条用例绑定与人机数量。
        // 为什么不塞进 perf_run：那是「一次运行」的历史记录；这里存的是「下次打开页面还是这套」，
        // 两者生命周期不同 —— 混在一起会让「改了配置想恢复默认」变成翻历史 run 抄一遍。
        // 也不用 application.properties：它随包发布、运行期改不了，而这些是使用者随时要调的值。
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS perf_setting (
                  key TEXT PRIMARY KEY,
                  value TEXT,
                  updated_at TEXT
                )
                """);

        // 索引（2026-09-28 新增，修复 #3）：
        // 按 run/game 和 loop 查询会随采样量增加而变慢，为相关过滤列建立索引。
        // IF NOT EXISTS 保证幂等：已存在的库上等价于无操作，不影响存量数据。
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_perf_loop_run_game ON perf_loop(run_id, game_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_perf_sample_loop ON perf_sample(loop_id)");
    }

    // ---------- perf_game ----------

    /** 扫描到的游戏 upsert（按完整名去重），返回该游戏 id。 */
    public long upsertGame(String name, String shortName) {
        // 第一步（查）：按唯一键 name 查库；命中说明游戏已收录，直接复用旧 id
        Long id = jdbc.query("SELECT id FROM perf_game WHERE name=?", (rs, i) -> rs.getLong(1), name)
                .stream().findFirst().orElse(null);
        if (id != null) {
            return id;
        }
        // 第二步（插）：未命中才插入。
        // 用 ConnectionCallback 让"插入 + 取自增 id"在同一个数据库连接内完成：
        // DriverManagerDataSource 不是连接池，每次 getConnection() 都是全新连接，
        // 若分开两次拿连接，第二次连接上取不到本次 INSERT 产生的 last_insert_rowid()（M2 老坑）
        return jdbc.execute((ConnectionCallback<Long>) con -> {
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO perf_game(name,short_name,created_at) VALUES(?,?,?)")) {
                // 按顺序绑定三个参数：完整名 / 短名 / 创建时间（ISO-8601 字符串）
                ps.setString(1, name);
                ps.setString(2, shortName);
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
            }
            // 同一连接上取 SQLite 自增主键：last_insert_rowid() 返回本连接上一次 INSERT 的 rowid
            try (Statement st = con.createStatement(); ResultSet r = st.executeQuery("SELECT last_insert_rowid()")) {
                r.next();
                return r.getLong(1);
            }
        });
    }

    /**
     * 查询全部游戏（列表页用）。
     * 排序技巧：ORDER BY sort_order IS NULL 让未排过序的（NULL）永远垫底，再按 sort_order 升序、id 倒序稳定展示。
     * 返回 Map 而非实体类：键即前端字段名，省一层 DTO 转换。
     */
    public List<Map<String, Object>> listGames() {
        return jdbc.query(
                "SELECT id,name,short_name,created_at,mg_id,app_id,room_id,human_count,ai_count,ai_level,"
                        + "game_seconds,machine_uid,body_override FROM perf_game "
                        + "ORDER BY sort_order IS NULL, sort_order, id DESC",
                (rs, i) -> {
                    // LinkedHashMap 保持 put 顺序，前端按此顺序渲染列
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("name", rs.getString("name"));
                    m.put("shortName", rs.getString("short_name"));
                    m.put("createdAt", rs.getString("created_at"));
                    // 推送身份（需求 #68）：人机对局模式用它替换用例里的 {{mg_id}} / {{app_id}}。
                    // 为空就原样留着 —— 那种情况下推送会用接口测试变量里的全局值，与从前一样。
                    m.put("mgId", rs.getString("mg_id"));
                    m.put("appId", rs.getString("app_id"));
                    // 推送配置（需求 #69；#70 扩成名单/房间号/等级/时长）：
                    // 全部 null = 没配，走全局或用例原文
                    m.put("roomId", rs.getString("room_id"));
                    int humanCount = rs.getInt("human_count");
                    m.put("humanCount", rs.wasNull() ? null : humanCount);
                    int aiCount = rs.getInt("ai_count");
                    m.put("aiCount", rs.wasNull() ? null : aiCount);
                    int aiLevel = rs.getInt("ai_level");
                    m.put("aiLevel", rs.wasNull() ? null : aiLevel);
                    int gameSeconds = rs.getInt("game_seconds");
                    m.put("gameSeconds", rs.wasNull() ? null : gameSeconds);
                    m.put("machineUid", rs.getString("machine_uid"));
                    m.put("bodyOverride", rs.getString("body_override"));
                    return m;
                });
    }

    /**
     * 改一个游戏的推送配置（#68 出身，#69/#70/#71 扩成九项）。空串归一成 null ——
     * 两者在库里的差别只是「存过空串」，在语义上没有区别，界面上也都是「没填」。
     */
    public void updateGameIds(long gameId, String mgId, String appId, String roomId,
                              Integer humanCount, Integer aiCount, Integer aiLevel,
                              Integer gameSeconds, String machineUid, String bodyOverride) {
        jdbc.update("UPDATE perf_game SET mg_id=?, app_id=?, room_id=?, human_count=?, ai_count=?,"
                        + " ai_level=?, game_seconds=?, machine_uid=?, body_override=? WHERE id=?",
                blankToNull(mgId), blankToNull(appId), blankToNull(roomId),
                nonNeg(humanCount), nonNeg(aiCount), nonNeg(aiLevel), nonNeg(gameSeconds),
                blankToNull(machineUid), blankToNull(bodyOverride), gameId);
    }

    /** null 或负数归一成 null（= 没配）；正整数原样存。 */
    private static Integer nonNeg(Integer v) {
        return v == null || v < 0 ? null : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ---------- perf_setting ----------

    /*
     * 配置项的键名（2026-10-06，需求 #68）。集中在这里，是因为后端读写与前端展示要用同一批字符串：
     * 散在两端各写一遍的话，改一处忘一处，表现成「页面上改了却不生效」——
     * 那种问题查起来要先怀疑后端没存，实际只是键名对不上。
     */
    /** 人机对局模式：推人机那一步用的用例 id。 */
    public static final String SETTING_AI_CASE_BATCH = "ai_room_case_batch";
    /** 人机对局模式：开局用的用例 id。 */
    public static final String SETTING_AI_CASE_START = "ai_room_case_start";
    /** 人机对局模式：结束游戏用的用例 id。 */
    public static final String SETTING_AI_CASE_END = "ai_room_case_end";
    /** 人机对局模式：清空房间用的用例 id。 */
    public static final String SETTING_AI_CASE_CLEAR = "ai_room_case_clear";
    /** 人机对局模式：人机数量；留空 = 用用例原文的名单。 */
    public static final String SETTING_AI_COUNT = "ai_room_count";
    /**
     * 人机对局模式：**对局提前结束**的关键字（需求 #78）。
     *
     * <p>留空 = 不做提前结束检测（与本次改动之前的行为完全一致）—— 这个关键字是环境相关的
     * 上报事件名，写死在代码里换个环境就失效，所以必须由使用者填。
     */
    public static final String SETTING_AI_END_KEYWORD = "ai_room_end_keyword";

    /**
     * 性能测试：每圈采「关后基线」时，是否先触发一次系统内存回收再采第二个点（2026-10-10，v1.10.0 #84）。
     *
     * <p><b>默认勾选</b>（2026-10-10 使用者拍板从「默认关」改为「默认开」）：
     * 只有显式存过 "false" 才不算。做成开关而不是写死，是因为回收会改变被测 App 的状态 ——
     * 万一某个客户端收到回收信号后重载资源、反而抬高下一圈，取消勾选即可回到旧口径。
     *
     * <p>这个键**不放进 {@link #aiRoomSettingKeys()}**：它对三个模式都适用，不是人机对局专属配置。
     */
    public static final String SETTING_PERF_TRIM_BASELINE = "perf_trim_baseline";

    /** 人机对局模式的全部配置键（页面一次读回）。 */
    public static List<String> aiRoomSettingKeys() {
        return List.of(SETTING_AI_CASE_BATCH, SETTING_AI_CASE_START, SETTING_AI_CASE_END,
                SETTING_AI_CASE_CLEAR, SETTING_AI_COUNT, SETTING_AI_END_KEYWORD);
    }

    /** 读一个配置项；没有则返回 null。 */
    public String getSetting(String key) {
        return jdbc.query("SELECT value FROM perf_setting WHERE key=?",
                (rs, i) -> rs.getString(1), key).stream().findFirst().orElse(null);
    }

    /** 写一个配置项（存在则覆盖）。值为 null 时写入空串，与「没配过」的区别由调用方按默认值处理。 */
    public void putSetting(String key, String value) {
        jdbc.update("""
                INSERT INTO perf_setting(key,value,updated_at) VALUES(?,?,?)
                ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at
                """, key, value == null ? "" : value, Instant.now().toString());
    }

    /** 一次读回多个配置项（页面加载时用，省几趟查询）。 */
    public Map<String, String> getSettings(List<String> keys) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String k : keys) {
            String v = getSetting(k);
            if (v != null) {
                out.put(k, v);
            }
        }
        return out;
    }

    /** 删除游戏及其全部圈/采样/判定记录（run 的 game_queue 不重写，展示时前端回退 #id）。 */
    /** 批量删除游戏（复用单删级联逻辑，逐删圈/采样/判定数据）。 */
    public void deleteGames(List<Long> ids) {
        if (ids == null) {
            return;
        }
        // 逐个调用单删逻辑（内部级联删除该游戏的圈/采样/判定数据）
        for (Long id : ids) {
            if (id != null) {
                deleteGame(id);
            }
        }
    }

    /** 批量重排：orderedIds 按给定顺序排最前，未传入的按原有顺序跟在后面，整表重赋 sort_order。 */
    public void reorderGames(List<Long> orderedIds) {
        // 重排算法：用户给定的顺序排最前，其余游戏保持原有顺序跟在后面，最后整表重赋 sort_order
        // 1) 取当前全部游戏 id（按现有排序规则，未指定的游戏顺序保持不变）
        List<Long> all = jdbc.query(
                "SELECT id FROM perf_game ORDER BY sort_order IS NULL, sort_order, id DESC",
                (rs, i) -> rs.getLong(1));
        // 2) 用户指定的顺序段：过滤 null、去重
        List<Long> ordered = orderedIds == null ? List.of()
                : orderedIds.stream().filter(id -> id != null).distinct().toList();
        // 3) 未指定的游戏（rest）保持原有相对顺序
        List<Long> rest = new ArrayList<>(all);
        rest.removeAll(ordered);
        // 4) 合并：ordered 在前、rest 在后
        List<Long> merged = new ArrayList<>(ordered);
        merged.addAll(rest);
        // 5) 按下标重写整表 sort_order：位置即顺序
        for (int i = 0; i < merged.size(); i++) {
            jdbc.update("UPDATE perf_game SET sort_order=? WHERE id=?", i, merged.get(i));
        }
    }

    /**
     * 级联删除一个游戏及其全部关联数据。
     * 删除顺序：采样（引用 loop）→ 判定（引用 game）→ 圈 → 游戏，逐层清干净。
     * 注意：历史 run 的 game_queue 里仍保留该游戏 id 字符串，前端查不到游戏时回退显示 "#id"。
     *
     * <p>2026-09-28 修复 #3：四条 DELETE 包进**单个事务**。原实现是四条独立语句，中途失败
     * （进程被杀、磁盘写满）会留下「圈已删但判定残留」这类脏数据，而 perf_* 五张表没有外键约束兜底。
     * 本项目的 DataSource 是手动装配的 DriverManagerDataSource（非连接池），
     * 所以必须用**同一个 Connection** 手动 commit/rollback，不能依赖多条 jdbc.update 的自动提交。
     */
    public void deleteGame(long gameId) {
        // 第一步：查该游戏下全部圈 id，用于删采样
        List<Long> loopIds = jdbc.queryForList("SELECT id FROM perf_loop WHERE game_id=?", Long.class, gameId);
        String in = joinIds(loopIds);
        // 第二步：同一连接内按引用层级逐表删，全部成功才提交
        jdbc.execute((ConnectionCallback<Void>) con -> {
            boolean oldAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                if (!in.isEmpty()) {
                    // 动态拼 IN 子句（"1,2,3"），一次 DELETE 删掉所有圈的采样
                    try (Statement st = con.createStatement()) {
                        st.executeUpdate("DELETE FROM perf_sample WHERE loop_id IN (" + in + ")");
                    }
                }
                deleteByKey(con, "DELETE FROM perf_check_result WHERE game_id=?", gameId);
                deleteByKey(con, "DELETE FROM perf_loop WHERE game_id=?", gameId);
                deleteByKey(con, "DELETE FROM perf_game WHERE id=?", gameId);
                con.commit();
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(oldAutoCommit);
            }
            return null;
        });
    }

    /** 把 id 列表拼成 SQL IN 子句用的 "1,2,3"；空列表返回空串（调用方据此跳过该条语句）。 */
    private static String joinIds(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        for (Long id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /** 事务内的单条按 id 删除：抽出来避免多处重复的 prepareStatement/setLong/executeUpdate 样板。 */
    private static void deleteByKey(Connection con, String sql, long key) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setLong(1, key);
            ps.executeUpdate();
        }
    }

    /** 上移/下移游戏（up=true 上移）。交换相邻项后整表重赋 sort_order，顺序稳定。 */
    public void moveGame(long gameId, boolean up) {
        // 先按当前展示顺序取出全部游戏 id
        List<Long> ids = jdbc.query(
                "SELECT id FROM perf_game ORDER BY sort_order IS NULL, sort_order, id DESC",
                (rs, i) -> rs.getLong(1));
        int idx = ids.indexOf(gameId);
        // 游戏不在列表中：不处理
        if (idx < 0) {
            return;
        }
        // 上移 = 与前一格交换，下移 = 与后一格交换
        int target = up ? idx - 1 : idx + 1;
        // 已在最上/最下：无法再移动
        if (target < 0 || target >= ids.size()) {
            return;
        }
        // 交换相邻两个位置
        Long tmp = ids.get(idx);
        ids.set(idx, ids.get(target));
        ids.set(target, tmp);
        // 重写整表 sort_order（位置即顺序），保证排序结果稳定可预期
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("UPDATE perf_game SET sort_order=? WHERE id=?", i, ids.get(i));
        }
    }

    /** 按 id 查单个游戏（报告页/展示/推送取数用），查不到返回 null。 */
    public Map<String, Object> getGame(long gameId) {
        return jdbc.query("SELECT id,name,short_name,mg_id,app_id,room_id,human_count,ai_count,ai_level,"
                        + "game_seconds,machine_uid,body_override FROM perf_game WHERE id=?", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getLong("id"));
            m.put("name", rs.getString("name"));
            m.put("shortName", rs.getString("short_name"));
            m.put("mgId", rs.getString("mg_id"));
            m.put("appId", rs.getString("app_id"));
            m.put("roomId", rs.getString("room_id"));
            int humanCount = rs.getInt("human_count");
            m.put("humanCount", rs.wasNull() ? null : humanCount);
            int aiCount = rs.getInt("ai_count");
            m.put("aiCount", rs.wasNull() ? null : aiCount);
            int aiLevel = rs.getInt("ai_level");
            m.put("aiLevel", rs.wasNull() ? null : aiLevel);
            int gameSeconds = rs.getInt("game_seconds");
            m.put("gameSeconds", rs.wasNull() ? null : gameSeconds);
            m.put("machineUid", rs.getString("machine_uid"));
            m.put("bodyOverride", rs.getString("body_override"));
            return m;
        }, gameId).stream().findFirst().orElse(null);
    }

    // ---------- perf_run ----------

    /**
     * 新建一次压测 run，状态初始为 RUNNING，返回自增 run id。
     * 同样在单一连接内完成"插入 + 取 id"（同 upsertGame 的连接复用考量）。
     */
    public long insertRun(String device, String mode, String gameQueue, int loopCount,
                          int sampleSeconds, long earlyTapDelayMs) {
        return insertRun(device, mode, gameQueue, loopCount, sampleSeconds, earlyTapDelayMs, null);
    }

    /**
     * 新建一次压测 run（带「本轮推了几个人机」，需求 #68）。
     *
     * @param aiCount 人机数量；null 表示「用用例原文的名单」，与 0 不是一回事
     */
    public long insertRun(String device, String mode, String gameQueue, int loopCount,
                          int sampleSeconds, long earlyTapDelayMs, Integer aiCount) {
        return jdbc.execute((ConnectionCallback<Long>) con -> {
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO perf_run(device,mode,status,game_queue,loop_count,sample_seconds,early_tap_delay_ms,start_time,ai_count)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """)) {
                // 绑定 9 个参数：设备 / 模式 / 初始状态 RUNNING / 游戏 id 串 / 圈数 / 每圈采样秒数 / 提前结算延时 ms / 开始时间 / 人机数量
                ps.setString(1, device);
                ps.setString(2, mode);
                ps.setString(3, "RUNNING");
                ps.setString(4, gameQueue);
                ps.setInt(5, loopCount);
                ps.setInt(6, sampleSeconds);
                ps.setLong(7, earlyTapDelayMs);
                ps.setString(8, Instant.now().toString());
                if (aiCount == null) {
                    ps.setNull(9, java.sql.Types.INTEGER);
                } else {
                    ps.setInt(9, aiCount);
                }
                ps.executeUpdate();
            }
            // 同连接取自增 id
            try (Statement st = con.createStatement(); ResultSet r = st.executeQuery("SELECT last_insert_rowid()")) {
                r.next();
                return r.getLong(1);
            }
        });
    }

    public long insertPlannedRun(String device, PerfExecutionPlan plan) {
        return new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> {
            long id=insertRun(device,plan.mode(),plan.gameIds().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")),
                    plan.loopCount(),plan.sampleSeconds(),plan.earlyTapDelayMs(),plan.aiCount());
            savePlan(id,plan); return id;
        });
    }
    public int prepareResume(long runId,PerfExecutionPlan newPlan) {
        return new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())).execute(status -> {
            if(newPlan!=null)savePlan(runId,newPlan);
            int removed=deleteInterruptedLoops(runId);resumeRun(runId);return removed;
        });
    }
    public void savePlan(long runId, PerfExecutionPlan plan) {
        jdbc.update("INSERT INTO perf_run_plan(run_id,plan_json,plan_hash,captured_at) VALUES(?,?,?,?)",runId,new com.sudpanel.api.ApiPlanVault(jdbc).encrypt("perf-run",runId,plan.json()),plan.hash(),plan.capturedAt());
        facts().freeze(runId,plan);
    }
    public PerfExecutionPlan plan(long runId) {
        var rows=jdbc.queryForList("SELECT plan_json,plan_hash FROM perf_run_plan WHERE run_id=?",runId);
        if(rows.isEmpty()) return null;
        PerfExecutionPlan plan=PerfExecutionPlan.parse(new com.sudpanel.api.ApiPlanVault(jdbc).decrypt("perf-run",runId,(String)rows.getFirst().get("plan_json")));
        if(!plan.hash().equals(rows.getFirst().get("plan_hash"))) throw new IllegalStateException("性能快照校验失败，禁止猜测配置续跑");
        return plan;
    }
    public Map<String,Object> planSummary(long runId) {
        var plan=plan(runId);
        return plan==null?Map.of("state","LEGACY_UNKNOWN","message","旧运行未记录完整配置，不能反推原配置"):plan.summary();
    }
    public void markLoopPlan(long loopId, String hash) { jdbc.update("UPDATE perf_loop SET plan_hash=? WHERE id=?",hash,loopId); }

    /** 结束一次 run：写最终状态（OK/FAILED/INTERRUPTED）并记录结束时间。 */
    public void finishRun(long runId, String status) {
        jdbc.update("UPDATE perf_run SET status=?, end_time=? WHERE id=?", status, Instant.now().toString(), runId);
    }

    /** 查询全部 run（新的在前），行映射统一走 runMap（会附带解析好的游戏短名列表）。 */
    public List<Map<String, Object>> listRuns() {
        return jdbc.query("SELECT * FROM perf_run ORDER BY id DESC", (rs, i) -> runMap(rs));
    }

    public record HistoryFilter(Long gameId,String mode,String status,String from,String to,String cleanupStatus) {}
    public record HistoryPage(List<Map<String,Object>> items,String nextCursor,boolean hasMore) {}
    public HistoryPage history(HistoryFilter filter,String cursor,int limit) {
        if(limit<1||limit>100) throw new IllegalArgumentException("分页大小须为1～100");
        if(filter==null) filter=new HistoryFilter(null,null,null,null,null,null);
        if(filter.gameId()!=null&&filter.gameId()<1) throw new IllegalArgumentException("游戏ID无效");
        if(filter.mode()!=null&&!List.of("NORMAL","AI_ROOM","EARLY_SETTLE").contains(filter.mode())) throw new IllegalArgumentException("模式无效");
        List<String> states=filter.status()==null?List.of():List.of(filter.status().split(","));
        if(states.stream().anyMatch(x->!List.of("RUNNING","WAIT_DEVICE","WAIT_ENV","STOPPED","COMPLETED","ABORTED","ERROR","INTERRUPTED").contains(x))) throw new IllegalArgumentException("状态无效");
        String from=parseHistoryDate(filter.from());
        String to=parseHistoryDate(filter.to());
        if(from!=null&&to!=null&&Instant.parse(from).isAfter(Instant.parse(to))) throw new IllegalArgumentException("日期范围无效");
        String fingerprint=Integer.toHexString(filter.toString().hashCode());
        Long before=null;
        if(cursor!=null&&!cursor.isBlank()) try {
            if(cursor.length()>512)throw new IllegalArgumentException();
            String[] token=new String(java.util.Base64.getUrlDecoder().decode(cursor),java.nio.charset.StandardCharsets.UTF_8).split(":");
            if(token.length!=2||!fingerprint.equals(token[1])) throw new IllegalArgumentException();
            before=Long.valueOf(token[0]);if(before<1) throw new IllegalArgumentException();
        }catch(Exception e){throw new IllegalArgumentException("游标无效或筛选条件已改变，请回到第一页");}
        StringBuilder sql=new StringBuilder("SELECT r.* FROM perf_run r WHERE 1=1");var args=new ArrayList<Object>();
        if(before!=null){sql.append(" AND r.id<?");args.add(before);}
        if(filter.gameId()!=null){sql.append(" AND (','||r.game_queue||',') LIKE ?");args.add("%,"+filter.gameId()+",%");}
        if(filter.mode()!=null){sql.append(" AND r.mode=?");args.add(filter.mode());}
        if(!states.isEmpty()){sql.append(" AND r.status IN ("+String.join(",",java.util.Collections.nCopies(states.size(),"?"))+")");args.addAll(states);}
        if(from!=null){sql.append(" AND r.start_time>=?");args.add(from);}
        if(to!=null){sql.append(" AND r.start_time<=?");args.add(to);}
        if(filter.cleanupStatus()!=null) {
            String status=cleanupFilter(filter.cleanupStatus());
            if("NOT_RECORDED".equals(status)) sql.append(" AND EXISTS(SELECT 1 FROM perf_loop l WHERE l.run_id=r.id AND NOT EXISTS(SELECT 1 FROM perf_loop_cleanup c WHERE c.loop_id=l.id))");
            else {sql.append(" AND EXISTS(SELECT 1 FROM perf_loop_cleanup c WHERE c.run_id=r.id AND c.status=?)");args.add(status);}
        }
        sql.append(" ORDER BY r.id DESC LIMIT ?");args.add(limit+1);
        var rows=jdbc.query(sql.toString(),(rs,i)->runMap(rs),args.toArray());
        boolean more=rows.size()>limit;var items=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
        String next=more?java.util.Base64.getUrlEncoder().withoutPadding().encodeToString((items.getLast().get("id")+":"+fingerprint).getBytes(java.nio.charset.StandardCharsets.UTF_8)):null;
        return new HistoryPage(items,next,more);
    }
    private static String parseHistoryDate(String date) {try{return date==null?null:Instant.parse(date).toString();}catch(Exception e){throw new IllegalArgumentException("筛选日期须为有效ISO时间");}}
    public static String cleanupFilter(String status) { if(!List.of("SUCCESS","FAILED","NOT_RECORDED").contains(status)) throw new IllegalArgumentException("清理筛选无效");return status; }
    public List<Map<String,Object>> activeRuns() {return jdbc.query("SELECT * FROM perf_run WHERE status IN ('RUNNING','WAIT_DEVICE','WAIT_ENV') ORDER BY id DESC LIMIT 100",(rs,i)->runMap(rs));}

    /** 按 id 查单个 run（报告页用），查不到返回 null。 */
    public Map<String, Object> getRun(long runId) {
        return jdbc.query("SELECT * FROM perf_run WHERE id=?", (rs, i) -> runMap(rs), runId)
                .stream().findFirst().orElse(null);
    }

    /** 删除 run 及其全部圈/采样/判定记录。 */
    public void deleteRun(long runId) {
        // 与 deleteGame 同套路：先收集本 run 下全部圈 id，拼 IN 子句删采样
        List<Long> loopIds = jdbc.queryForList("SELECT id FROM perf_loop WHERE run_id=?", Long.class, runId);
        String in = joinIds(loopIds);
        // 2026-09-28 修复 #3：与 deleteGame 一致，四条 DELETE 包进单个事务（同一连接手动提交）。
        // run 的级联删除数据量更大（一个 run 可能上万条采样），中途失败更容易留下脏数据。
        jdbc.execute((ConnectionCallback<Void>) con -> {
            boolean oldAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                if (!in.isEmpty()) {
                    try (Statement st = con.createStatement()) {
                        st.executeUpdate("DELETE FROM perf_sample WHERE loop_id IN (" + in + ")");
                    }
                }
                deleteByKey(con, "DELETE FROM perf_check_result WHERE run_id=?", runId);
                deleteByKey(con, "DELETE FROM perf_loop WHERE run_id=?", runId);
                deleteByKey(con, "DELETE FROM perf_run WHERE id=?", runId);
                con.commit();
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(oldAutoCommit);
            }
            return null;
        });
    }

    // ---------- 断点续跑 / 启动对账 ----------

    /** 某游戏已完成的圈数（OK+INVALID；断点续跑从这里接着跑）。 */
    public int countLoops(long runId, long gameId) {
        // 只统计已终结的圈（OK=正常完成 / INVALID=判定无效）；RUNNING/INTERRUPTED 不计入"已完成"
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM perf_loop WHERE run_id=? AND game_id=? AND status IN ('OK','INVALID')",
                Integer.class, runId, gameId);
        // COUNT 结果可能为 null（理论不会，兜底防御），统一返回 0
        return n != null ? n : 0;
    }

    /** 删除某游戏旧聚合判定（续跑后重算前先清，避免重复）。 */
    public void deleteChecks(long runId, long gameId) {
        jdbc.update("DELETE FROM perf_check_result WHERE run_id=? AND game_id=?", runId, gameId);
    }

    /** 删除 run 下 INTERRUPTED 的圈（中断时数据不完整，续跑时重做这些圈号）。 */
    public int deleteInterruptedLoops(long runId) {
        // 先找出所有 INTERRUPTED 的圈 id（中断圈的采样数据不完整，续跑要重做这些圈）
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM perf_loop WHERE run_id=? AND status='INTERRUPTED'", Long.class, runId);
        // 逐圈删采样（圈都没了，采样不能留）
        for (Long id : ids) {
            jdbc.update("DELETE FROM perf_sample WHERE loop_id=?", id);
        }
        // 再整批删圈：直接带 status 条件删，无需拼 IN 子句
        if (!ids.isEmpty()) {
            jdbc.update("DELETE FROM perf_loop WHERE run_id=? AND status='INTERRUPTED'", runId);
        }
        // 返回删除的圈数，调用方可提示"续跑将重做 N 圈"
        return ids.size();
    }

    /** 续跑：run 状态改回 RUNNING，清 end_time。 */
    public void resumeRun(long runId) {
        jdbc.update("UPDATE perf_run SET status='RUNNING', end_time=NULL WHERE id=?", runId);
    }

    /**
     * 启动对账：进程重启后残留的运行态 run 标记为 INTERRUPTED。
     *
     * <p>{@code WAIT_ENV}（2026-10-09，v1.9.4 #74）也要算进来：那是「接口环境不可达、正在等待」的
     * 挂起态，同样是非终态。漏掉它的话，在等环境期间被强杀的面板会让这条 run 永远卡在 WAIT_ENV，
     * 既续跑不了也停不掉。
     */
    public int markInterruptedRuns() {
        return jdbc.update("UPDATE perf_run SET status='INTERRUPTED', end_time=? "
                        + "WHERE status IN ('RUNNING','WAIT_ENV','WAIT_DEVICE')",
                Instant.now().toString());
    }

    /** 启动对账：残留的 RUNNING 圈标记为 INTERRUPTED。 */
    public int markInterruptedLoops() {
        return jdbc.update("UPDATE perf_loop SET status='INTERRUPTED' WHERE status='RUNNING'");
    }

    /** game_queue 的 id 串 → 游戏短名列表（报告/列表展示命名用）。 */
    private List<String> gameNamesOf(String queue) {
        List<String> names = new ArrayList<>();
        // 空串/空白串直接返回空列表
        if (queue == null || queue.isBlank()) {
            return names;
        }
        // game_queue 存的是逗号分隔的游戏 id 串，逐个解析成短名
        for (String s : queue.split(",")) {
            try {
                long id = Long.parseLong(s.trim());
                // 优先用短名（展示更简洁）；短名为空时回退用完整名
                String n = jdbc.query("SELECT short_name,name FROM perf_game WHERE id=?",
                        (rs, i) -> {
                            String sn = rs.getString(1);
                            return sn != null && !sn.isBlank() ? sn : rs.getString(2);
                        }, id).stream().findFirst().orElse(null);
                if (n != null) {
                    names.add(n);
                }
            } catch (NumberFormatException ignore) {
                // 队列里混入非数字（如已删除游戏的占位 id）时跳过，不让单个脏数据拖垮整个列表
            }
        }
        return names;
    }

    /** 行映射器：perf_run 一行 → 驼峰键 Map；同时解析 game_queue 附带游戏短名列表（gameNames）。 */
    private Map<String, Object> runMap(ResultSet rs) throws java.sql.SQLException {
        // 下划线列名 → 对外驼峰键名的转换统一在这层做，业务代码不接触原始列名
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("device", rs.getString("device"));
        m.put("mode", rs.getString("mode"));
        m.put("status", rs.getString("status"));
        m.put("gameQueue", rs.getString("game_queue"));
        // 额外附带解析结果：前端列表直接显示游戏名，无需自己解析 id 串
        m.put("gameNames", gameNamesOf(rs.getString("game_queue")));
        m.put("loopCount", rs.getInt("loop_count"));
        m.put("sampleSeconds", rs.getInt("sample_seconds"));
        m.put("earlyTapDelayMs", rs.getLong("early_tap_delay_ms"));
        // 人机对局模式这一轮推了几个人机（需求 #68）：老 run 没有这一列的值，读出来是 null，
        // 前端把它显示成「—」而不是 0 —— 「没这一项」与「推了 0 个」是两件事
        int aiCount = rs.getInt("ai_count");
        m.put("aiCount", rs.wasNull() ? null : aiCount);
        m.put("startTime", rs.getString("start_time"));
        m.put("endTime", rs.getString("end_time"));
        return m;
    }

    // ---------- perf_loop ----------

    /** 为某 run 的某游戏开一个新圈（状态 RUNNING），返回圈 id。聚合指标字段留空，圈结束后由 finishLoop 回填。 */
    public long insertLoop(long runId, long gameId, int loopNo) {
        return jdbc.execute((ConnectionCallback<Long>) con -> {
            try (PreparedStatement ps = con.prepareStatement("""
                    INSERT INTO perf_loop(run_id,game_id,loop_no,status,start_time) VALUES(?,?,?,?,?)
                    """)) {
                // 绑定：run id / 游戏 id / 圈号（从 1 开始递增）/ 初始状态 RUNNING / 开始时间
                ps.setLong(1, runId);
                ps.setLong(2, gameId);
                ps.setInt(3, loopNo);
                ps.setString(4, "RUNNING");
                ps.setString(5, Instant.now().toString());
                ps.executeUpdate();
            }
            // 同连接取自增 id
            try (Statement st = con.createStatement(); ResultSet r = st.executeQuery("SELECT last_insert_rowid()")) {
                r.next();
                return r.getLong(1);
            }
        });
    }

    /**
     * 删除一圈的记录（需求 #74）。
     *
     * <p>只在一个场景用：环境不可达导致本圈**从没真正开始**（推人机就失败了）时，
     * 把已经建好的空圈撤掉，等环境恢复后重跑同一个圈号。
     *
     * <p>为什么必须删而不是把状态改成 INVALID：圈号是「这个游戏跑到第几圈」的计数依据
     * （{@code countLoops} 既用于页面展示，也用于续跑时判断从第几圈接着跑）。
     * 留一条假圈会让计数虚高，续跑时就会少跑。
     */
    public void deleteLoop(long loopId) {
        jdbc.update("DELETE FROM perf_loop WHERE id=?", loopId);
    }

    /**
     * 标记一圈为「对局提前结束」（需求 #78）。
     *
     * <p>单独一个 UPDATE 而不是扩展 {@link #finishLoop} 的参数列表 —— 那边已有 12 个参数，
     * 与 {@link #updateLoopBaselineDetail}、{@link #updateLoopCpuSys} 同一先例。
     */
    public void markLoopEarlyEnd(long loopId) {
        jdbc.update("UPDATE perf_loop SET early_end=1 WHERE id=?", loopId);
    }

    public void markLoopRoundMissing(long loopId,String reason) {
        try {jdbc.update("UPDATE perf_loop SET game_round_id=NULL,round_id_evidence=? WHERE id=?",new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("state","MISSING","source","LOGCAT","candidateIds",List.of(),"matchCount",0,"missingReason",reason)),loopId);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    public void markLoopRoundEvidence(long loopId, PerfLoopLogWindow window) {
        try { jdbc.update("UPDATE perf_loop SET game_round_id=?,round_id_evidence=? WHERE id=?",window.reliableRoundId(),
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(window.roundEvidence()),loopId); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("ID证据序列化失败",e); }
    }

    /**
     * 回填本圈对局的 roundId（2026-10-10，v1.10.4 需求 #86）。
     *
     * <p>与 {@link #markLoopTrimmedBaseline} 同一先例：单开一个 UPDATE，不扩 {@code finishLoop} 的参数。
     * 取不到时调用方不写，这一列就是 NULL —— 缺一个标识不影响本圈的有效性。
     */
    public void markLoopGameRoundId(long loopId, String roundId) {
        if (roundId == null || roundId.isBlank()) {
            return;
        }
        jdbc.update("UPDATE perf_loop SET game_round_id=? WHERE id=?", roundId.trim(), loopId);
    }

    /**
     * 回填一圈「触发系统内存回收之后的基线」（2026-10-10，v1.10.0 #84）。
     *
     * <p>与 {@link #updateLoopBaselineDetail} 同一先例：单开一个 UPDATE，不扩 {@link #finishLoop} 的 12 个参数。
     */
    public void markLoopTrimmedBaseline(long loopId, PerfCollector.Sample s) {
        jdbc.update("""
                UPDATE perf_loop SET trimmed_pss_kb=?, trimmed_java_kb=?, trimmed_native_kb=?,
                  trimmed_graphics_kb=?, trimmed_code_kb=?, trimmed_private_other_kb=?, trimmed_system_kb=?
                WHERE id=?
                """, s.pssKb(), s.javaHeapKb(), s.nativeHeapKb(), s.graphicsKb(),
                s.codeKb(), s.privateOtherKb(), s.systemKb(), loopId);
    }

    /**
     * 把「一圈」拆成三段的净增量（kB，PSS 口径；2026-10-10，v1.10.0 #82）。
     *
     * <p>为什么需要它：只看「关后基线」的累计曲线，分不清「打的时候在涨」还是「退出不归还」。
     * 拆成三段之后，一眼就能看出增量落在哪一步：
     * <ol>
     *   <li><b>进房与开局</b>：上一圈关后基线 → 本圈首个采样点</li>
     *   <li><b>局内采样</b>：本圈首个采样点 → 本圈末个采样点</li>
     *   <li><b>收尾退出</b>：本圈末个采样点 → 本圈关后基线</li>
     * </ol>
     * 各阶段的差值可帮助选择复核环节；阶段相关性本身不能确认泄漏原因。
     *
     * @return loopId → [进房与开局, 局内采样, 收尾退出]；数据不全（首圈无上一圈基线、无采样）的圈不出现
     */
    public Map<Long, double[]> stageDeltas(long runId, long gameId) {
        Map<Long, double[]> out = new LinkedHashMap<>();
        Double prevBaseline = null;
        for (Map<String, Object> l : loopsOf(runId, gameId)) {
            Double base = l.get("baselinePssKb") == null
                    ? null : ((Number) l.get("baselinePssKb")).doubleValue();
            Double first = null;
            Double last = null;
            long loopId = ((Number) l.get("id")).longValue();
            for (Map<String, Object> x : samplesOf(loopId)) {
                if (x.get("pssKb") == null) {
                    continue;
                }
                double v = ((Number) x.get("pssKb")).doubleValue();
                if (first == null) {
                    first = v;
                }
                last = v;
            }
            if (prevBaseline != null && first != null && last != null && base != null) {
                out.put(loopId, new double[]{first - prevBaseline, last - first, base - last});
            }
            if (base != null) {
                prevBaseline = base;
            }
        }
        return out;
    }

    /** 圈结束回填聚合数据。samples 为空时聚合字段为 null。 */
    /** 电量监控：回填一圈开始时的设备电量（%）。不影响主流程，失败静默。 */
    public void updateBattery(long loopId, Integer level) {
        if (level == null) {
            return;
        }
        jdbc.update("UPDATE perf_loop SET battery_level=? WHERE id=?", level, loopId);
    }

    public void finishLoop(long loopId, String status, String invalidReason,
                           Integer baselinePssKb, Integer baselineWsThreads, Integer inGameWsThreads,
                           Integer pssAvg, Integer pssPeak, Double cpuAvg, Double cpuPeak,
                           String verdict, String judgeJson) {
        // 一次性回填圈的所有聚合结果：状态、无效原因、基线/局内指标、机器判定结论与证据 JSON
        jdbc.update("""
                UPDATE perf_loop SET status=?, invalid_reason=?, baseline_pss_kb=?, baseline_ws_threads=?,
                  in_game_ws_threads=?, pss_avg_kb=?, pss_peak_kb=?, cpu_avg=?, cpu_peak=?,
                  verdict=?, judge_json=?, end_time=?
                WHERE id=?
                """, status, invalidReason, baselinePssKb, baselineWsThreads, inGameWsThreads,
                pssAvg, pssPeak, cpuAvg, cpuPeak, verdict, judgeJson, Instant.now().toString(), loopId);
    }

    /**
     * 回填一圈「关游戏后基线」的 meminfo 分项（2026-09-28，v1.2 #14）。
     *
     * <p>单独一个 UPDATE 而不是扩展 {@link #finishLoop} 的参数列表 —— 那边已有 12 个参数，
     * 再加 6 个可读性太差。基线的分项是定位泄漏类型的核心证据：基线逐圈抬高说明退出后未归还，
     * 再看是哪一类在涨即可锁定方向。
     *
     * @param loopId 圈 id
     * @param s 基线采样点；分项解析不到时为 null，如实写入（不猜值）
     */
    public void updateLoopBaselineDetail(long loopId, PerfCollector.Sample s) {
        jdbc.update("""
                UPDATE perf_loop SET baseline_java_kb=?, baseline_native_kb=?, baseline_graphics_kb=?,
                  baseline_code_kb=?, baseline_private_other_kb=?, baseline_system_kb=?
                WHERE id=?
                """, s.javaHeapKb(), s.nativeHeapKb(), s.graphicsKb(),
                s.codeKb(), s.privateOtherKb(), s.systemKb(), loopId);
    }

    /**
     * 回填一圈的 CPU 整机口径统计（2026-10-03，v1.5 #21）。
     *
     * <p>单独一个 UPDATE 而不是扩展 {@link #finishLoop} 的参数列表（同 {@link #updateLoopBaselineDetail}
     * 的先例，那边已有 12 个参数）。{@code cores} 取本圈首个观测到的在线核数（单圈内基本恒定）；
     * 老数据这三列为 NULL，判定与展示需回退单核口径并标注。
     *
     * @param loopId  圈 id
     * @param cores   本圈在线核数（无有效观测时为 null）
     * @param avgSys  整机口径圈均值（单核均值 ÷ 核数）；无有效观测时为 null
     * @param peakSys 整机口径单圈峰值
     */
    public void updateLoopCpuSys(long loopId, Integer cores, Double avgSys, Double peakSys) {
        jdbc.update("UPDATE perf_loop SET cpu_cores=?, cpu_avg_sys=?, cpu_peak_sys=? WHERE id=?",
                cores, avgSys, peakSys, loopId);
    }

    /** 查某 run 下某游戏的全部圈（按圈号升序），供报告/判定使用。 */
    public List<Map<String, Object>> loopsOf(long runId, long gameId) {
        return loopsOf(runId,gameId,null);
    }

    public Map<String,Object> getLoop(long id) {return jdbc.query(loopSelection()+" WHERE l.id=?",(rs,i)->loopMap(rs),id).stream().findFirst().orElse(null);}
    public void recordSettlement(long runId,long loopId,Map<String,Object> summary) {
        try{jdbc.update("INSERT INTO perf_settlement_check(run_id,loop_id,summary_json,created_at) VALUES(?,?,?,?)",runId,loopId,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(summary),Instant.now().toString());}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("结算摘要序列化失败",e);}
    }
    public List<Map<String,Object>> settlementChecks(long loopId) {
        return jdbc.query("SELECT id,summary_json FROM perf_settlement_check WHERE loop_id=? ORDER BY id DESC LIMIT 20",(rs,i)->{
            try{Map<String,Object> row=new com.fasterxml.jackson.databind.ObjectMapper().readValue(rs.getString("summary_json"),Map.class);row.put("id",rs.getLong("id"));return row;}
            catch(Exception e){return Map.<String,Object>of("id",rs.getLong("id"),"status","UNKNOWN","evidenceUnreadable",true);}
        },loopId);
    }

    private String loopSelection() { return "SELECT l.*,c.status cleanup_status,c.reason cleanup_reason,c.evidence_missing cleanup_missing FROM perf_loop l LEFT JOIN perf_loop_cleanup c ON c.loop_id=l.id"; }
    public List<Map<String,Object>> loopsOf(long runId,long gameId,String cleanupStatus) {
        String sql=loopSelection()+" WHERE l.run_id=? AND l.game_id=?";
        var args=new ArrayList<Object>(List.of(runId,gameId));
        if(cleanupStatus!=null) {String value=cleanupFilter(cleanupStatus);if("NOT_RECORDED".equals(value)) sql+=" AND c.loop_id IS NULL";else{sql+=" AND c.status=?";args.add(value);}}
        return jdbc.query(sql+" ORDER BY l.loop_no",(rs,i)->loopMap(rs),args.toArray());
    }

    /** 查某游戏跨所有 run 的全部圈（游戏维度，如"游戏历史表现"场景用）。 */
    public List<Map<String, Object>> loopsOfGame(long gameId) {
        return jdbc.query(loopSelection()+" WHERE l.game_id=? ORDER BY l.run_id,l.loop_no",(rs,i)->loopMap(rs),gameId);
    }

    /** 行映射器：perf_loop 一行 → 驼峰键 Map。数值列用 rs.getObject 再强转，NULL 值能正确映射为 Java null。 */
    private Map<String, Object> loopMap(ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("runId", rs.getLong("run_id"));
        m.put("gameId", rs.getLong("game_id"));
        m.put("loopNo", rs.getInt("loop_no"));
        m.put("status", rs.getString("status"));
        m.put("invalidReason", rs.getString("invalid_reason"));
        m.put("cleanupStatus",rs.getString("cleanup_status")==null?"NOT_RECORDED":rs.getString("cleanup_status"));
        m.put("cleanupReason",rs.getString("cleanup_reason"));m.put("evidenceMissing",rs.getObject("cleanup_missing")==null?null:rs.getInt("cleanup_missing")!=0);
        // 聚合指标列允许 NULL（圈未跑完/无采样）：getObject 返回 null 再强转是安全的，不会 NPE
        m.put("baselinePssKb", (Integer) rs.getObject("baseline_pss_kb"));
        m.put("baselineWsThreads", (Integer) rs.getObject("baseline_ws_threads"));
        m.put("inGameWsThreads", (Integer) rs.getObject("in_game_ws_threads"));
        m.put("pssAvgKb", (Integer) rs.getObject("pss_avg_kb"));
        m.put("pssPeakKb", (Integer) rs.getObject("pss_peak_kb"));
        m.put("cpuAvg", (Double) rs.getObject("cpu_avg"));
        m.put("cpuPeak", (Double) rs.getObject("cpu_peak"));
        // CPU 整机口径（2026-10-03，v1.5 #21）：历史 run 与未回填的圈为 null
        m.put("cpuCores", (Integer) rs.getObject("cpu_cores"));
        m.put("cpuAvgSys", (Double) rs.getObject("cpu_avg_sys"));
        m.put("cpuPeakSys", (Double) rs.getObject("cpu_peak_sys"));
        m.put("verdict", rs.getString("verdict"));
        m.put("judgeJson", rs.getString("judge_json"));
        m.put("humanVerdict", rs.getString("human_verdict"));
        m.put("humanNote", rs.getString("human_note"));
        m.put("batteryLevel", (Integer) rs.getObject("battery_level"));
        // 对局提前结束标记（2026-10-09，v1.9.4 #78）：1=提前收尾，NULL/0=满时长。老数据为 NULL
        m.put("earlyEnd", (Integer) rs.getObject("early_end"));
        // 触发系统回收后的基线（2026-10-10，v1.10.0 #84）：开关关闭或老数据时为 null
        m.put("trimmedPssKb", (Integer) rs.getObject("trimmed_pss_kb"));
        m.put("trimmedJavaKb", (Integer) rs.getObject("trimmed_java_kb"));
        m.put("trimmedNativeKb", (Integer) rs.getObject("trimmed_native_kb"));
        m.put("trimmedGraphicsKb", (Integer) rs.getObject("trimmed_graphics_kb"));
        m.put("trimmedCodeKb", (Integer) rs.getObject("trimmed_code_kb"));
        m.put("trimmedPrivateOtherKb", (Integer) rs.getObject("trimmed_private_other_kb"));
        m.put("trimmedSystemKb", (Integer) rs.getObject("trimmed_system_kb"));
        // 本圈真实对局的 roundId（2026-10-10，v1.10.4 #86）：只有人机对局模式会写，其余为 null
        m.put("gameRoundId", rs.getString("game_round_id"));
        m.put("planHash",rs.getString("plan_hash"));
        String roundEvidence=rs.getString("round_id_evidence");
        try { m.put("roundIdEvidence",roundEvidence==null?Map.of("state","NOT_RECORDED"):new com.fasterxml.jackson.databind.ObjectMapper().readValue(roundEvidence,Map.class)); }
        catch(Exception invalid) { m.put("roundIdEvidence",Map.of("state","UNKNOWN","missingReason","存储的ID证据损坏")); }
        m.put("apiEnvId", rs.getObject("api_env_id") == null ? null : rs.getLong("api_env_id"));
        m.put("apiEnvName", rs.getString("api_env_name"));
        m.put("apiConfigCapturedAt", rs.getString("api_config_captured_at"));
        m.put("startTime", rs.getString("start_time"));
        m.put("endTime", rs.getString("end_time"));
        // 关游戏后基线的 meminfo 分项（2026-09-28，v1.2 #14）：历史 run 与未回填的圈为 null
        m.put("baselineJavaKb", (Integer) rs.getObject("baseline_java_kb"));
        m.put("baselineNativeKb", (Integer) rs.getObject("baseline_native_kb"));
        m.put("baselineGraphicsKb", (Integer) rs.getObject("baseline_graphics_kb"));
        m.put("baselineCodeKb", (Integer) rs.getObject("baseline_code_kb"));
        m.put("baselinePrivateOtherKb", (Integer) rs.getObject("baseline_private_other_kb"));
        m.put("baselineSystemKb", (Integer) rs.getObject("baseline_system_kb"));
        return m;
    }

    // ---------- perf_sample ----------

    /** 批量写入一圈的采样点。seq 从 0 开始自动编号；时间戳由毫秒数转成 ISO-8601 字符串存储。
     *  2026-10-02：由「逐条 jdbc.update」改为 `batchUpdate` —— 复用同一条 PreparedStatement，
     *  省掉每条 SQL 的解析/语句创建开销（长程 run 每圈约 10 点、700 圈即约 7000 条）。SQL 与参数顺序不变。 */
    public void insertSamples(long loopId, List<PerfCollector.Sample> samples) {
        if (samples == null || samples.isEmpty()) {
            return;
        }
        int seq = 0;
        List<Object[]> batch = new ArrayList<>(samples.size());
        for (PerfCollector.Sample s : samples) {
            // seq++ 保证按采样先后顺序编号，画曲线时按 seq 排序即可还原时序
            // 末 6 列为 meminfo App Summary 分项（2026-09-28，v1.2 #14），解析不到时写入 NULL
            batch.add(new Object[]{
                    loopId, seq++, s.pssKb(), s.rssKb(), s.cpuPercent(), s.cpuCores(), s.threadCount(), s.wsThreadCount(),
                    s.netConnections(), Instant.ofEpochMilli(s.tsMillis()).toString(),
                    s.javaHeapKb(), s.nativeHeapKb(), s.graphicsKb(), s.codeKb(), s.privateOtherKb(), s.systemKb(),qualityJson(s.quality())});
        }
        jdbc.batchUpdate("""
                INSERT INTO perf_sample(loop_id,seq,pss_kb,rss_kb,cpu_percent,cpu_cores,thread_count,ws_thread_count,net_conn,ts,
                                        java_heap_kb,native_heap_kb,graphics_kb,code_kb,private_other_kb,system_kb,quality_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, batch);
    }

    /** 查一圈的全部采样点（按 seq 升序），用于绘制单圈曲线。 */
    public List<Map<String, Object>> samplesOf(long loopId) {
        return jdbc.query("SELECT * FROM perf_sample WHERE loop_id=? ORDER BY seq", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", rs.getInt("seq"));
            m.put("quality",parseQuality(rs.getString("quality_json")));
            m.put("pssKb", (Integer) rs.getObject("pss_kb"));
            m.put("rssKb", (Integer) rs.getObject("rss_kb"));
            m.put("cpuPercent", (Double) rs.getObject("cpu_percent"));
            m.put("threadCount", (Integer) rs.getObject("thread_count"));
            m.put("wsThreadCount", (Integer) rs.getObject("ws_thread_count"));
            m.put("netConn", (Integer) rs.getObject("net_conn"));
            // meminfo App Summary 分项（2026-09-28，v1.2 #14）：历史 run 无此数据，值为 null，前端需容忍
            m.put("javaHeapKb", (Integer) rs.getObject("java_heap_kb"));
            m.put("nativeHeapKb", (Integer) rs.getObject("native_heap_kb"));
            m.put("graphicsKb", (Integer) rs.getObject("graphics_kb"));
            m.put("codeKb", (Integer) rs.getObject("code_kb"));
            m.put("privateOtherKb", (Integer) rs.getObject("private_other_kb"));
            m.put("systemKb", (Integer) rs.getObject("system_kb"));
            m.put("ts", rs.getString("ts"));
            return m;
        }, loopId);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper QUALITY_JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static String qualityJson(Object value){if(value==null)return null;try{return QUALITY_JSON.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("采样质量序列化失败",e);}}
    private static Map<String,Object> parseQuality(String text){if(text==null)return Map.of("state","NOT_RECORDED");try{return QUALITY_JSON.readValue(text,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){return Map.of("state","UNREADABLE");}}
    public void saveLoopQuality(long loopId,Map<String,Object> value){
        // 被撤销的环境失败圈不得重新制造孤儿证据。
        jdbc.update("INSERT INTO perf_loop_quality(loop_id,diagnostics_json) SELECT id,? FROM perf_loop WHERE id=? ON CONFLICT(loop_id) DO UPDATE SET diagnostics_json=excluded.diagnostics_json",qualityJson(value),loopId);
    }
    public Map<String,Object> loopQuality(long loopId){if(getLoop(loopId)==null)throw new IllegalArgumentException("圈不存在");var rows=jdbc.queryForList("SELECT diagnostics_json FROM perf_loop_quality WHERE loop_id=?",String.class,loopId);return rows.isEmpty()?Map.of("state","NOT_RECORDED","message","旧圈或本圈尚未记录采样条件"):parseQuality(rows.getFirst());}
    public PerfDeviceHealth.Policy healthPolicy(){return PerfDeviceHealth.Policy.of(getSettings(List.of(PerfDeviceHealth.TEMP_WARN,PerfDeviceHealth.THERMAL_WARN)));}
    public void saveHealthPolicy(PerfDeviceHealth.Policy policy){var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));tx.executeWithoutResult(status->{putSetting(PerfDeviceHealth.TEMP_WARN,policy.temperatureWarnC()==null?"":policy.temperatureWarnC().toString());putSetting(PerfDeviceHealth.THERMAL_WARN,policy.thermalWarn()==null?"":policy.thermalWarn().toString());});}

    public void validateExportScope(long runId,Long gameId,Long loopId) {
        if(getRun(runId)==null) throw new IllegalArgumentException("运行不存在");
        if(gameId!=null&&jdbc.queryForObject("SELECT count(*) FROM perf_loop WHERE run_id=? AND game_id=?",Long.class,runId,gameId)==0) throw new IllegalArgumentException("该运行没有此游戏的圈记录");
        if(loopId!=null&&jdbc.queryForObject("SELECT count(*) FROM perf_loop WHERE id=? AND run_id=?"+(gameId==null?"":" AND game_id=?"),Long.class,gameId==null?new Object[]{loopId,runId}:new Object[]{loopId,runId,gameId})==0) throw new IllegalArgumentException("圈不属于所选运行或游戏");
    }
    public void readExportSnapshot(Runnable action) {
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
        tx.setReadOnly(true);tx.executeWithoutResult(status->action.run());
    }
    private String sampleWhere(Long gameId,Long loopId) { return " WHERE l.run_id=?"+(gameId==null?"":" AND l.game_id=?")+(loopId==null?"":" AND l.id=?"); }
    private Object[] sampleArgs(long runId,Long gameId,Long loopId) {var args=new ArrayList<Object>();args.add(runId);if(gameId!=null)args.add(gameId);if(loopId!=null)args.add(loopId);return args.toArray();}
    public long sampleCount(long runId,Long gameId,Long loopId) {return jdbc.queryForObject("SELECT count(*) FROM perf_sample s JOIN perf_loop l ON l.id=s.loop_id"+sampleWhere(gameId,loopId),Long.class,sampleArgs(runId,gameId,loopId));}
    public void forEachSample(long runId,Long gameId,Long loopId,java.util.function.Consumer<Map<String,Object>> consumer) {
        jdbc.query("SELECT s.*,l.run_id,l.game_id,l.loop_no FROM perf_sample s JOIN perf_loop l ON l.id=s.loop_id"+sampleWhere(gameId,loopId)+" ORDER BY l.id,s.seq",(org.springframework.jdbc.core.RowCallbackHandler)rs->{
            var row=new LinkedHashMap<String,Object>();var meta=rs.getMetaData();for(int i=1;i<=meta.getColumnCount();i++)row.put(meta.getColumnLabel(i),rs.getObject(i));consumer.accept(row);
        },sampleArgs(runId,gameId,loopId));
    }
    public void forEachExportLoop(long runId,Long gameId,java.util.function.Consumer<Map<String,Object>> consumer) {
        jdbc.query(loopSelection()+" WHERE l.run_id=?"+(gameId==null?"":" AND l.game_id=?")+" ORDER BY l.id",(org.springframework.jdbc.core.RowCallbackHandler)rs->consumer.accept(loopMap(rs)),gameId==null?new Object[]{runId}:new Object[]{runId,gameId});
    }

    // ---------- perf_check_result ----------

    /** 保存一条游戏级自动判定结果（检测项编号、名称、结论与证据）。 */
    public void insertCheckResult(long runId, long gameId, int checkNo, String checkName,
                                  String verdict, String evidence) {
        jdbc.update("""
                INSERT INTO perf_check_result(run_id,game_id,check_no,check_name,verdict,evidence)
                VALUES(?,?,?,?,?,?)
                """, runId, gameId, checkNo, checkName, verdict, evidence);
    }

    /** 查某 run 某游戏的全部自动判定（按检测项编号升序），报告页展示用。 */
    public List<Map<String, Object>> checksOf(long runId, long gameId) {
        return jdbc.query(
                "SELECT * FROM perf_check_result WHERE run_id=? AND game_id=? ORDER BY check_no", (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("checkNo", rs.getInt("check_no"));
                    m.put("checkName", rs.getString("check_name"));
                    m.put("verdict", rs.getString("verdict"));
                    m.put("evidence", rs.getString("evidence"));
                    m.put("humanVerdict", rs.getString("human_verdict"));
                    m.put("humanNote", rs.getString("human_note"));
                    return m;
                }, runId, gameId);
    }

    /** 人工复核：机器初判不改，只写 human_verdict/human_note。 */
    public void reviewCheck(long checkId, String humanVerdict, String humanNote) {
        jdbc.update("UPDATE perf_check_result SET human_verdict=?, human_note=? WHERE id=?",
                humanVerdict, humanNote, checkId);
    }

    /** 提前结算模式的逐圈人工复核。 */
    public void reviewLoop(long loopId, String humanVerdict, String humanNote) {
        jdbc.update("UPDATE perf_loop SET human_verdict=?, human_note=? WHERE id=?",
                humanVerdict, humanNote, loopId);
    }
}
