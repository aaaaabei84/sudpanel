package com.sudpanel.perf;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 按钮坐标分组持久化（2026-09-28 新增，对应 v1.2 待做 #4）。
 *
 * <p>背景：换不同分辨率的手机会导致按钮点不准；而原先对局内的「结束游戏 / 退出房间」用的是
 * {@code resource-ids.properties} 里的**全局固定坐标、不按设备区分**，换机必然失效。
 * 本类把坐标管理重构为「按分组组织的坐标方案」：分组可自定义命名、可绑定设备序列号，
 * 组内逐按钮标定，跑压测前强制校验完成度。
 *
 * <p>两张表：
 * <ul>
 *   <li>{@code perf_button_group} —— 分组（名称 / 绑定设备 / 标定时间）</li>
 *   <li>{@code perf_button_coord} —— 分组内的逐按钮坐标（source 区分手动标定与运行时自动学习）</li>
 * </ul>
 *
 * <p><b>保密</b>：本类只处理「按钮 key」与「坐标数值」，全部落在本地 SQLite（{@code data/} 已 gitignore）。
 * 坐标严禁写入文档、记忆或 git。
 */
@Repository
public class ButtonGroupRepository {

    /**
     * 内置按钮定义。
     *
     * @param key      公开 SDK 的状态名，同时也是分组内坐标的存储键
     * @param label    中文名（前端展示与错误提示用中文，不用英文 key）
     * @param required 是否必须标定才能启动压测。<b>只有能被 dump 定位的大厅按钮才是必标</b>；
     *                 对局内的「结束游戏 / 退出房间」在 TextureView 上，dump 必然失败，
     *                 标定只能靠间接推定，因此列为选标 —— 运行期缺这两个坐标时会回落到
     *                 本地固定坐标（见 PerfRunService），不会导致跑不起来。
     */
    public record ButtonDef(String key, String label, boolean required) {
    }

    /**
     * 可标定按钮（2026-09-24 用户确认固定这批，暂不做分组内自由增删）。
     *
     * <p>前 4 个是大厅按钮，必须标定才能启动压测；后 2 个是对局内按钮
     * （结束游戏 / 退出房间），在 TextureView 上、dump 必然失败，标定只能靠间接推定，
     * 因此列为选标 —— 缺了不阻塞启动，运行期会回落到本地固定坐标。
     *
     * <p>「必标 / 选标」的划分见 {@link ButtonDef#required()}。
     */
    public static final List<ButtonDef> BUTTONS = List.of(
            new ButtonDef("app_common_self_in", "加入游戏", true),
            new ButtonDef("app_common_game_add_ai_players", "添加AI玩家", true),
            new ButtonDef("app_common_self_ready", "准备", true),
            new ButtonDef("app_common_self_playing", "开始游戏", true),
            new ButtonDef("perf_stop_game", "结束游戏", false),
            new ButtonDef("perf_exit_game", "退出房间", false));

    /** key → 中文名；查不到时回退 key 本身（保证前端永远有可读文案）。 */
    public static String labelOf(String key) {
        for (ButtonDef b : BUTTONS) {
            if (b.key().equals(key)) {
                return b.label();
            }
        }
        return key;
    }

    /**
     * 键名不同、但指向**同一个按钮**的别名表（键 = 调用方 resourceKey，值 = 本类 BUTTONS 里的 key）。
     *
     * <p>【当前为空，且是有意留着的】v1.7 之前这里有一条
     * {@code app_common_self_end → perf_stop_game}（apptomg「结束游戏」↔ perf「结束游戏」）。
     * 2026-10-03 用户澄清：结束游戏的真实触发是「点麦位玩家头像 → 弹窗里选结束游戏」，
     * 那套「与大厅主按钮同位、一次点击」的前提不成立，该别名已失效并被移除。
     * 同一天又确认这个动作只能人手做，测试点最终挪到 mgtoapp 人工走查方向（v1.9），
     * apptomg 侧不再有它 —— 因此更没有别名可配。
     *
     * <p>本方法保留这条解析链路（而不是把 {@link #perfKeyFor} 简化成纯同名匹配）的原因：
     * 跨模块的"同义不同名"映射迟早还会出现；集中在这一处维护，
     * 好过将来在 RunService 里散落 if 判断。
     */
    private static final Map<String, String> KEY_ALIAS = Map.of();

    /**
     * 把一个外部的按钮 resourceKey 解析成「本类 BUTTONS 里可用的 key」。
     *
     * <p>解析顺序：同名直配 → 别名表。两处都没有则返回 null，
     * 表示该按钮**没有可复用的标定坐标**，调用方应退回 dump 定位。
     *
     * @param resourceKey apptomg 测试点的 resourceKey（可为 null/空白）
     * @return BUTTONS 中的 key；无法映射时 null
     */
    public static String perfKeyFor(String resourceKey) {
        if (resourceKey == null || resourceKey.isBlank()) {
            return null;
        }
        String k = resourceKey.trim();
        for (ButtonDef b : BUTTONS) {
            if (b.key().equals(k)) {
                return k;
            }
        }
        return KEY_ALIAS.get(k);
    }

    private final JdbcTemplate jdbc;

    public ButtonGroupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        ensureSchema();
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_coordinate_profile(owner TEXT PRIMARY KEY,layout_json TEXT NOT NULL)");
        jdbc.execute("CREATE TRIGGER IF NOT EXISTS tr_perf_coordinate_profile_delete AFTER DELETE ON perf_button_group BEGIN DELETE FROM perf_coordinate_profile WHERE owner='group:'||OLD.id; END");
    }

    /** 建表（幂等）：每次启动都执行，重复跑不报错。 */
    private void ensureSchema() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS perf_button_group (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  name TEXT NOT NULL,
                  device_serial TEXT,
                  created_at TEXT,
                  calibrated_at TEXT
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS perf_button_coord (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  group_id INTEGER NOT NULL,
                  key TEXT NOT NULL,
                  x INTEGER,
                  y INTEGER,
                  source TEXT,
                  updated_at TEXT
                )
                """);
    }

    public PerfDeviceLayout layout(String owner) {
        var rows=jdbc.queryForList("SELECT layout_json FROM perf_coordinate_profile WHERE owner=?",String.class,owner);
        if(rows.isEmpty())return PerfDeviceLayout.unknown();
        try{return new com.fasterxml.jackson.databind.ObjectMapper().readValue(rows.getFirst(),PerfDeviceLayout.class);}catch(Exception invalid){return PerfDeviceLayout.unknown();}
    }
    public void bindLayout(String owner,PerfDeviceLayout layout) {
        if(!layout.known())throw new IllegalStateException("无法读取设备型号、有效尺寸或旋转，不能认可坐标；请检查后重新标定");
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status->{
            if(owner.startsWith("group:")&&!layout.matches(layout(owner))) jdbc.update("DELETE FROM perf_button_coord WHERE group_id=?",Long.parseLong(owner.substring(6)));
            try{jdbc.update("INSERT INTO perf_coordinate_profile(owner,layout_json) VALUES(?,?) ON CONFLICT(owner) DO UPDATE SET layout_json=excluded.layout_json",owner,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(layout));}
            catch(Exception failure){throw new IllegalStateException("设备坐标来源保存失败",failure);}
        });
    }

    /** 分组列表（按 id 升序），每条附「已标定 N / 共 M」进度。 */
    public List<Map<String, Object>> listGroups() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : jdbc.queryForList(
                "SELECT id, name, device_serial AS deviceSerial, created_at AS createdAt,"
                        + " calibrated_at AS calibratedAt FROM perf_button_group ORDER BY id")) {
            long id = ((Number) g.get("id")).longValue();
            Map<String, Object> progress = progressOf(id);
            g.put("calibrated", progress.get("calibrated"));
            g.put("total", progress.get("total"));
            // 必标口径也一并带上：前端用它渲染进度条与「能不能启动」的提示，
            // 不必再去遍历 items 自己算，避免两边算法分叉
            g.put("requiredCalibrated", progress.get("requiredCalibrated"));
            g.put("requiredTotal", progress.get("requiredTotal"));
            g.put("items", progress.get("items"));
            out.add(g);
        }
        return out;
    }

    /** 单个分组；不存在返回 null。 */
    public Map<String, Object> getGroup(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, device_serial AS deviceSerial, created_at AS createdAt,"
                        + " calibrated_at AS calibratedAt FROM perf_button_group WHERE id=?", id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 新建分组，返回新 id。name 为空时用「分组N」兜底；deviceSerial 可为 null（通用分组）。 */
    public long createGroup(String name, String deviceSerial) {
        String finalName = (name == null || name.isBlank()) ? defaultName() : name.trim();
        String device = (deviceSerial == null || deviceSerial.isBlank()) ? null : deviceSerial.trim();
        return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Long>) con -> {
            try (java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO perf_button_group(name, device_serial, created_at) VALUES(?,?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, finalName);
                ps.setString(2, device);
                ps.setString(3, Instant.now().toString());
                ps.executeUpdate();
                try (java.sql.ResultSet rs = ps.getGeneratedKeys()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        });
    }

    /** 「分组N」兜底名：取当前分组数 +1，避免空名。 */
    private String defaultName() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM perf_button_group", Integer.class);
        return "分组" + ((n == null ? 0 : n) + 1);
    }

    /** 删除分组及其坐标（包进单事务，与 PerfRepository 的做法一致）。 */
    public void deleteGroup(long id) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) con -> {
            boolean oldAuto = con.getAutoCommit();
            con.setAutoCommit(false);
            try {
                try (java.sql.PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM perf_button_coord WHERE group_id=?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                try (java.sql.PreparedStatement ps = con.prepareStatement(
                        "DELETE FROM perf_button_group WHERE id=?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
                con.commit();
            } catch (Exception e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(oldAuto);
            }
            return null;
        });
    }

    /** 写入/更新某分组某按钮的坐标（同 key 覆盖）。source 取 manual（手动标定）/ auto（运行时学习）/ migrated。 */
    public void upsertCoord(long groupId, String key, int x, int y, String source) {
        int updated = jdbc.update(
                "UPDATE perf_button_coord SET x=?, y=?, source=?, updated_at=? WHERE group_id=? AND key=?",
                x, y, source, Instant.now().toString(), groupId, key);
        if (updated == 0) {
            jdbc.update("INSERT INTO perf_button_coord(group_id, key, x, y, source, updated_at) VALUES(?,?,?,?,?,?)",
                    groupId, key, x, y, source, Instant.now().toString());
        }
        // 刷新分组标定时间（表示最近一次标定动作）
        jdbc.update("UPDATE perf_button_group SET calibrated_at=? WHERE id=?",
                Instant.now().toString(), groupId);
    }

    /** 某分组已标定的坐标：key → [x, y]。 */
    public Map<String, int[]> coordsOf(long groupId) {
        Map<String, int[]> out = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList(
                "SELECT key, x, y FROM perf_button_coord WHERE group_id=? AND x IS NOT NULL AND y IS NOT NULL",
                groupId)) {
            out.put(String.valueOf(row.get("key")),
                    new int[]{((Number) row.get("x")).intValue(), ((Number) row.get("y")).intValue()});
        }
        return out;
    }

    /**
     * 标定进度。
     *
     * @return {@code calibrated} / {@code total} —— 全部按钮的完成度（含选标）；
     *         {@code requiredCalibrated} / {@code requiredTotal} —— 只算必标按钮，
     *         这是启动校验真正看的口径（v1.7 #31 收敛后）；
     *         {@code items} —— 逐按钮明细（含中文名与是否必标），前端直接照此渲染。
     */
    public Map<String, Object> progressOf(long groupId) {
        Map<String, int[]> done = coordsOf(groupId);
        List<Map<String, Object>> items = new ArrayList<>();
        int n = 0;
        int reqN = 0;
        int reqTotal = 0;
        for (ButtonDef b : BUTTONS) {
            boolean ok = done.containsKey(b.key());
            if (ok) {
                n++;
            }
            if (b.required()) {
                reqTotal++;
                if (ok) {
                    reqN++;
                }
            }
            Map<String, Object> it = new LinkedHashMap<>();
            it.put("key", b.key());
            it.put("label", b.label());   // 前端直接用中文名展示，不用英文 key
            it.put("required", b.required());
            it.put("calibrated", ok);
            items.add(it);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("calibrated", n);
        out.put("total", BUTTONS.size());
        out.put("requiredCalibrated", reqN);
        out.put("requiredTotal", reqTotal);
        out.put("items", items);
        return out;
    }

    /**
     * 未标定的<b>必标</b>按钮中文名列表（启动校验失败时用于提示「缺哪几个」）。
     *
     * <p>只统计 {@link ButtonDef#required()} 为真的按钮：选标按钮（结束游戏 / 退出房间）
     * 缺了不阻塞启动，运行期会回落到本地固定坐标。
     */
    public List<String> missingLabels(long groupId) {
        Map<String, int[]> done = coordsOf(groupId);
        List<String> miss = new ArrayList<>();
        for (ButtonDef b : BUTTONS) {
            if (b.required() && !done.containsKey(b.key())) {
                miss.add(b.label());
            }
        }
        return miss;
    }

    /** 选中分组里缺坐标的<b>选标</b>按钮中文名（用于启动时提示"这几个将回落到固定坐标"）。 */
    public List<String> missingOptionalLabels(long groupId) {
        Map<String, int[]> done = coordsOf(groupId);
        List<String> miss = new ArrayList<>();
        for (ButtonDef b : BUTTONS) {
            if (!b.required() && !done.containsKey(b.key())) {
                miss.add(b.label());
            }
        }
        return miss;
    }

    /** 是否已有任何分组（用于判断是否需要做老缓存迁移）。 */
    public boolean isEmpty() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM perf_button_group", Integer.class);
        return n == null || n == 0;
    }

    /**
     * 兼容迁移（v1.2 #4）：把旧的「&lt;设备序列号&gt;|&lt;按钮key&gt; → x,y」缓存迁成一个默认分组。
     *
     * @param legacyCoords 旧缓存（键形如 "&lt;serial&gt;|&lt;key&gt;"）
     * @param fallbackSerial 无法从键中解析设备序列号时使用的设备（通常传当前设备）
     * @return 迁移的分组 id；无数据可迁时返回 -1
     */
    public long migrateFromLegacy(Map<String, int[]> legacyCoords, String fallbackSerial) {
        if (legacyCoords == null || legacyCoords.isEmpty()) {
            return -1L;
        }
        // 以「设备序列号」为维度聚合：同一台设备的坐标归到一个分组
        Map<String, Map<String, int[]>> byDevice = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : legacyCoords.entrySet()) {
            String k = e.getKey();
            int bar = k.indexOf('|');
            String serial = bar > 0 ? k.substring(0, bar) : fallbackSerial;
            String key = bar > 0 ? k.substring(bar + 1) : k;
            byDevice.computeIfAbsent(serial, s -> new LinkedHashMap<>()).put(key, e.getValue());
        }
        long first = -1L;
        for (Map.Entry<String, Map<String, int[]>> e : byDevice.entrySet()) {
            String serial = e.getKey();
            long gid = createGroup("迁移分组（" + shortSerial(serial) + "）", serial);
            if (first < 0) {
                first = gid;
            }
            for (Map.Entry<String, int[]> c : e.getValue().entrySet()) {
                upsertCoord(gid, c.getKey(), c.getValue()[0], c.getValue()[1], "migrated");
            }
        }
        return first;
    }

    /** 序列号只取末 4 位用于分组名（避免完整序列号出现在界面与日志）。 */
    private static String shortSerial(String serial) {
        if (serial == null || serial.isBlank()) {
            return "未知设备";
        }
        return serial.length() <= 4 ? serial : serial.substring(serial.length() - 4);
    }
}
