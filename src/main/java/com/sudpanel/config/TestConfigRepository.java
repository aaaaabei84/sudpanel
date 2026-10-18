package com.sudpanel.config;

import java.time.Instant;   // Instant：获取当前 UTC 时间（ISO-8601 字符串），用于 created_at
import java.util.ArrayList; // 可变列表（解析/组装结果用）
import java.util.Arrays;    // 数组流处理，配合 split 解析逗号分隔的 id 串
import java.util.List;      // 列表接口

// ---- JDBC 原生 API（同 TestPointRepository：insert 需在同一个连接里取自增 id）----
import java.sql.PreparedStatement; // 预编译语句
import java.sql.ResultSet;         // 查询结果集
import java.sql.Statement;         // 普通语句

// ---- Spring JDBC ----
import org.springframework.jdbc.core.ConnectionCallback; // 同一连接内执行回调
import org.springframework.jdbc.core.JdbcTemplate;       // JDBC 模板
import org.springframework.jdbc.core.RowMapper;          // 行映射器
import org.springframework.stereotype.Repository;        // 数据访问层注解

import com.sudpanel.model.Direction;
import com.sudpanel.model.TestConfig;

/**
 * 测试点配置存储（自定义测试点组合 + 顺序）。apptomg 与 mgtoapp 分方向存储，各方向配置独立。
 */
// ===== 职责与设计 =====
// 1. 一条"配置"（TestConfig）＝ 用户自定义的一个测试点组合：起个名字 + 按执行顺序指定一批测试点 id；
// 2. 运行时按配置加载并顺序执行，实现"只跑关心的几个测试点"，区别于跑全量测试集；
// 3. 持久化在 SQLite 的 test_config 表：point_ids 列以逗号拼接的字符串存储（如 "3,5,9"），
//    由 parseIds/joinIds 两个工具方法与 List<Long> 互转；
// 4. 两个方向（APPTOMG / MGTOAPP）的配置分开管理、互不干扰。
@Repository
public class TestConfigRepository {

    // JdbcTemplate：Spring JDBC 模板（用法与 TestPointRepository 相同）
    private final JdbcTemplate jdbc;

    /** 构造器：注入 JdbcTemplate 并确保 test_config 表存在。 */
    public TestConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 建表（IF NOT EXISTS 幂等）：id 自增主键；direction 方向；name 配置名；
        // point_ids 逗号分隔的测试点 id 串；created_at 创建时间（ISO-8601 字符串）
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS test_config (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  direction TEXT NOT NULL,
                  name TEXT NOT NULL,
                  point_ids TEXT NOT NULL,
                  created_at TEXT
                )
                """);
    }

    // RowMapper：一行结果 → TestConfig。注意 point_ids 列存的是逗号串，需经 parseIds 解析成 List<Long>
    private static final RowMapper<TestConfig> MAPPER = (rs, i) -> new TestConfig(
            rs.getLong("id"),
            Direction.valueOf(rs.getString("direction")),
            rs.getString("name"),
            parseIds(rs.getString("point_ids")),
            rs.getString("created_at")
    );

    // 把数据库里逗号分隔的 id 字符串（如 "3,5,9"）解析成 List<Long>（读取时还原）
    private static List<Long> parseIds(String s) {
        // 空值/空白（配置可能不含测试点）：返回空列表
        if (s == null || s.isBlank()) {
            return new ArrayList<>();
        }
        // 管道式处理：按逗号切分 → 去首尾空白 → 过滤空串（防 "1,,2" 产生空元素）→ 字符串转 Long
        return Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .map(Long::parseLong)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    // 反向操作：把 List<Long> 拼回逗号分隔字符串存入数据库（空列表存空串，读取时由 parseIds 还原）
    private static String joinIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        // String::valueOf 把每个 Long 转成字符串，joining(",") 用逗号连接成一条串
        return ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
    }

    /** 查询某方向下的全部配置，按创建先后（id 升序）返回。 */
    public List<TestConfig> findAllByDirection(Direction direction) {
        // 两个方向的配置相互独立，用 direction=? 过滤；ORDER BY id 即按创建顺序
        return jdbc.query("SELECT * FROM test_config WHERE direction=? ORDER BY id", MAPPER, direction.name());
    }

    /**
     * 新建一条配置并返回带自增 id 的完整对象。
     *
     * @param direction 方向（APPTOMG / MGTOAPP）
     * @param name      配置名（用户自定义，如"只测加入退出"）
     * @param pointIds  按执行顺序排列的测试点 id 列表
     * @return 带数据库 id 与创建时间的 TestConfig
     */
    public TestConfig insert(Direction direction, String name, List<Long> pointIds) {
        // 记录创建时刻（ISO-8601 字符串，如 2026-09-15T09:00:00.123456Z）
        String now = Instant.now().toString();
        // 与 TestPointRepository.insert 同理：INSERT 与取 last_insert_rowid 必须在同一连接内，
        // 否则 DriverManagerDataSource 每次新开连接，取到的自增 id 恒为 0
        Long id = jdbc.execute((ConnectionCallback<Long>) con -> {
            // 预编译 INSERT：4 个占位符按列顺序 set
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO test_config(direction,name,point_ids,created_at) VALUES(?,?,?,?)")) {
                ps.setString(1, direction.name());
                ps.setString(2, name);
                // 测试点 id 列表序列化成逗号串后入库
                ps.setString(3, joinIds(pointIds));
                ps.setString(4, now);
                ps.executeUpdate();
            }
            // 同一连接上取 SQLite 自增主键
            try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
                rs.next();
                return rs.getLong(1);
            }
        });
        // 组装返回：id 用数据库生成的；pointIds 复制一份，防止外部后续修改影响返回值
        return new TestConfig(id, direction, name, new ArrayList<>(pointIds), now);
    }

    /** 更新配置的名称与测试点列表（id 与 direction 保持不变）。 */
    public void update(Long id, String name, List<Long> pointIds) {
        // 只更新 name 与 point_ids 两列，方向/创建时间保持原样
        jdbc.update("UPDATE test_config SET name=?, point_ids=? WHERE id=?", name, joinIds(pointIds), id);
    }

    /** 按 id 删除一条配置。 */
    public void delete(Long id) {
        jdbc.update("DELETE FROM test_config WHERE id=?", id);
    }
}
