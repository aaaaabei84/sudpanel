package com.sudpanel.logcat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.sudpanel.logcat.LogcatKeywordRepository.Keyword;

/**
 * 过滤关键字库的单测（2026-10-06，需求 #66）。
 *
 * <p><b>为什么敢在这里碰数据库</b>：项目里其余 Repository 单测都只测静态纯函数，
 * 但这一处有两条**只有真库才能压住**的规则：唯一索引 + upsert。
 * 它们错了的表现都很安静 —— upsert 没生效会让下拉里出现两条一模一样的项，
 * 而「两条一样的」这件事光看代码完全看不出来（SQL 照常执行、不报错）。
 * 用内存 SQLite 起一个真库，代价只有几毫秒。
 *
 * <p><b>为什么是 {@code SingleConnectionDataSource} 而不是普通的 DataSource</b>：
 * SQLite 的 {@code :memory:} 库是**按连接**隔离的，普通 DataSource 每取一次连接就是一个
 * 新建的空库 —— 建完表再查会发现「表不存在」，而那个报错看起来像 DDL 写错了，
 * 会把排查引到完全错误的方向（这个坑很多人在 SQLite 内存库上踩过）。
 * {@code suppressClose=true} 让它始终复用同一条连接。
 */
class LogcatKeywordRepositoryTest {

    /** 起一个只剩一条连接的内存库，并把表建好。 */
    private static LogcatKeywordRepository repo() {
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        return new LogcatKeywordRepository(new JdbcTemplate(ds));
    }

    // ==================== 归一化：纯函数部分 ====================

    @Test
    @DisplayName("关键字去首尾空白 —— 不去的话「 isIn」与「isIn」会变成两条互不相干的记录")
    void trimsKeyword() {
        assertThat(LogcatKeywordRepository.normalizeKeyword("  isIn  ")).isEqualTo("isIn");
        assertThat(LogcatKeywordRepository.normalizeKeyword("\troomId\n")).isEqualTo("roomId");
    }

    @Test
    @DisplayName("关键字为 null / 全空白 → 空串（调用方据此判非法，而不是存一条空关键字）")
    void rejectsBlankKeyword() {
        assertThat(LogcatKeywordRepository.normalizeKeyword(null)).isEmpty();
        assertThat(LogcatKeywordRepository.normalizeKeyword("")).isEmpty();
        assertThat(LogcatKeywordRepository.normalizeKeyword("   ")).isEmpty();
    }

    @Test
    @DisplayName("关键字超长截断 —— 防的是把一整段日志粘进来撑爆下拉")
    void truncatesLongKeyword() {
        String longOne = "a".repeat(LogcatKeywordRepository.MAX_KEYWORD_LEN + 50);
        assertThat(LogcatKeywordRepository.normalizeKeyword(longOne))
                .hasSize(LogcatKeywordRepository.MAX_KEYWORD_LEN);
    }

    @Test
    @DisplayName("注释内部的换行折成空格 —— 下拉里那一项是单行显示的，留着换行会把下面的项顶下去")
    void foldsNewlinesInNote() {
        assertThat(LogcatKeywordRepository.normalizeNote("找进入房间\r\n的日志"))
                .isEqualTo("找进入房间 的日志");
    }

    @Test
    @DisplayName("注释允许为空（它是后补的辅助信息），超长同样截断")
    void noteAllowsEmptyAndTruncates() {
        assertThat(LogcatKeywordRepository.normalizeNote(null)).isEmpty();
        assertThat(LogcatKeywordRepository.normalizeNote("   ")).isEmpty();
        assertThat(LogcatKeywordRepository.normalizeNote("n".repeat(LogcatKeywordRepository.MAX_NOTE_LEN + 20)))
                .hasSize(LogcatKeywordRepository.MAX_NOTE_LEN);
    }

    // ==================== 存取：真库部分 ====================

    @Test
    @DisplayName("新建一条：能读回来，且带着注释")
    void savesAndLists() {
        LogcatKeywordRepository repo = repo();
        Keyword saved = repo.save("isIn", "看进入房间");
        assertThat(saved).isNotNull();
        assertThat(saved.keyword()).isEqualTo("isIn");
        assertThat(saved.note()).isEqualTo("看进入房间");
        assertThat(repo.list()).hasSize(1).first()
                .extracting(Keyword::keyword, Keyword::note)
                .containsExactly("isIn", "看进入房间");
    }

    @Test
    @DisplayName("★ 同一个关键字再存一次是「改注释」，不是多一条 —— 否则下拉里会出现两条分不出差别的项")
    void secondSaveUpdatesNoteInsteadOfDuplicating() {
        LogcatKeywordRepository repo = repo();
        Keyword first = repo.save("isIn", "旧注释");
        Keyword second = repo.save("isIn", "新注释");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.note()).isEqualTo("新注释");
        assertThat(repo.list()).hasSize(1);
    }

    @Test
    @DisplayName("★ 归一化在存之前生效：带空白的写法不会绕过唯一索引另开一条")
    void normalizesBeforeUniqueCheck() {
        LogcatKeywordRepository repo = repo();
        repo.save("isIn", "第一次");
        repo.save("  isIn  ", "第二次");
        assertThat(repo.list()).hasSize(1);
        assertThat(repo.list().get(0).note()).isEqualTo("第二次");
    }

    @Test
    @DisplayName("关键字为空 → 不落库，返回 null（让接口层回一条中文提示，而不是存一条坏数据）")
    void blankKeywordIsNotStored() {
        LogcatKeywordRepository repo = repo();
        assertThat(repo.save("   ", "注释")).isNull();
        assertThat(repo.list()).isEmpty();
    }

    @Test
    @DisplayName("列表按加入顺序，稳定的 —— 下拉里次序每次刷新都在变会让人点错")
    void listKeepsInsertionOrder() {
        LogcatKeywordRepository repo = repo();
        repo.save("b", "");
        repo.save("a", "");
        repo.save("c", "");
        assertThat(repo.list()).extracting(Keyword::keyword).containsExactly("b", "a", "c");
    }

    @Test
    @DisplayName("删除：删掉的那条消失、其余不动；删不存在的 id 回 false 而不是抛错")
    void deletes() {
        LogcatKeywordRepository repo = repo();
        Keyword a = repo.save("a", "");
        repo.save("b", "");
        assertThat(repo.delete(a.id())).isTrue();
        assertThat(repo.list()).extracting(Keyword::keyword).containsExactly("b");
        assertThat(repo.delete(a.id())).isFalse();
        assertThat(repo.delete(9999)).isFalse();
    }
}
