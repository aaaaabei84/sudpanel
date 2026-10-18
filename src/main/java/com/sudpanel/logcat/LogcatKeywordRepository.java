package com.sudpanel.logcat;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 上报查询的「过滤关键字」库（2026-10-06，需求 #66）。
 *
 * <h2>为什么这个东西要落库，而日志本身不落库</h2>
 * {@link com.sudpanel.web.LogcatController} 的类注释写着「为什么不落库：这个功能定位是
 * 看正在发生什么，不是留痕」——那条结论针对的是**日志内容**，现在依然成立。
 * 这里存的是**使用者自己起的关键字快捷方式**（关键字本体 + 一句注释），
 * 性质与接口测试的「自定义变量」相同：跨会话复用的小配置，丢了要重敲一遍。
 * 它也不增长（一次最多几十条），没有清理策略的负担。
 *
 * <h2>为什么不放在浏览器 localStorage</h2>
 * 那样完全不用动后端，改起来最快；但清理浏览器数据、换 Chrome profile、
 * 甚至只是换个端口访问，都会让这份配置凭空消失，而且**没有任何提示**——
 * 使用者会以为是自己没保存成功。放进同一个库则随库备份走，与其它自定义配置一致。
 *
 * <h2>两个刻意的设计</h2>
 * <ul>
 *   <li><b>同一关键字只有一条</b>（唯一索引 + upsert）：重复保存同一个关键字
 *       应当是「改注释」而不是「多一条一模一样的」，否则下拉里会出现两条分不出差别的项。</li>
 *   <li><b>注释可以为空，关键字不许为空</b>：注释是给人看的辅助信息，可以后补；
 *       而一个没有关键字本体的条目点下去只会把输入框清空，属于坏数据。</li>
 * </ul>
 */
@Repository
public class LogcatKeywordRepository {

    private static final Logger log = LoggerFactory.getLogger(LogcatKeywordRepository.class);

    /** 关键字本体与注释各自的最大长度。防的是粘贴进来一整段日志（界面上会撑爆下拉）。 */
    public static final int MAX_KEYWORD_LEN = 200;
    public static final int MAX_NOTE_LEN = 200;

    private final JdbcTemplate jdbc;

    public LogcatKeywordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        initTables();
    }

    /** 一条保存下来的过滤关键字。 */
    public record Keyword(long id, String keyword, String note) {
    }

    private void initTables() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS logcat_keyword (
                  id INTEGER PRIMARY KEY AUTOINCREMENT,
                  keyword TEXT NOT NULL,
                  note TEXT,
                  updated_at TEXT
                )
                """);
        // 唯一索引：让「重复保存同一个关键字」变成一次更新，而不是多出一行
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS uq_logcat_keyword ON logcat_keyword(keyword)");
    }

    /**
     * 归一化关键字：去掉首尾空白。
     *
     * <p>独立成静态纯函数是为了能被单测直接压 —— 这条规则错了（比如忘了 trim）不会报错，
     * 只会让「 isIn」和「isIn」变成两条互不相干的记录，下拉里看着一模一样却点不出想要的结果。
     *
     * @return 归一化后的关键字；输入为 {@code null} 或全是空白时返回空串（调用方据此判非法）
     */
    public static String normalizeKeyword(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        return s.length() > MAX_KEYWORD_LEN ? s.substring(0, MAX_KEYWORD_LEN) : s;
    }

    /**
     * 归一化注释：去首尾空白，允许为空。
     *
     * <p>另外把内部换行折成空格 —— 注释在下拉里是**一行**显示的，
     * 留着换行会让那一项突然变成两行、把下面的项顶下去。
     *
     * <p>用 {@code \R}（任何换行序列）而不是「先把 {@code \r} 换成空格、再把 {@code \n} 换成空格」：
     * 后者会把 Windows 的 {@code \r\n} 变成**两个**空格。这条是单测逼出来的 ——
     * 手打一个 `\n` 永远测不出它，而从 Windows 那边粘一段注释过来立刻就会看到多一个空格。
     */
    public static String normalizeNote(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replaceAll("\\R", " ").trim();
        return s.length() > MAX_NOTE_LEN ? s.substring(0, MAX_NOTE_LEN) : s;
    }

    /** 全部关键字，按加入顺序。 */
    public List<Keyword> list() {
        return jdbc.query(
                "SELECT id, keyword, note FROM logcat_keyword ORDER BY id",
                (rs, i) -> new Keyword(rs.getLong("id"), rs.getString("keyword"), rs.getString("note")));
    }

    /**
     * 保存一条关键字（同一个关键字已存在则**更新它的注释**，不新开一条）。
     *
     * @return 保存后的条目；关键字为空时返回 {@code null}（调用方据此回一条 400 级的中文提示）
     */
    public Keyword save(String rawKeyword, String rawNote) {
        String keyword = normalizeKeyword(rawKeyword);
        if (keyword.isEmpty()) {
            return null;
        }
        String note = normalizeNote(rawNote);
        String now = Instant.now().toString();
        // upsert：唯一索引是 keyword，冲突时只改注释与时间
        jdbc.update("""
                INSERT INTO logcat_keyword (keyword, note, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(keyword) DO UPDATE SET note = excluded.note, updated_at = excluded.updated_at
                """, keyword, note, now);
        Keyword saved = jdbc.queryForObject(
                "SELECT id, keyword, note FROM logcat_keyword WHERE keyword = ?",
                (rs, i) -> new Keyword(rs.getLong("id"), rs.getString("keyword"), rs.getString("note")),
                keyword);
        log.info("保存过滤关键字：{}", keyword);
        return saved;
    }

    /**
     * 删除一条。
     *
     * @return 是否真的删掉了一行（删不存在的 id 返回 false，调用方据此判断要不要提示）
     */
    public boolean delete(long id) {
        return jdbc.update("DELETE FROM logcat_keyword WHERE id = ?", id) > 0;
    }
}
