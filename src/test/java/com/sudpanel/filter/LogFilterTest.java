package com.sudpanel.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LogFilter 纯函数测试（2026-10-02 补，本项目首批单元测试）。
 * 覆盖：命中/未命中、子串匹配、多行顺序、入参为空/空白等边界。
 */
class LogFilterTest {

    private final LogFilter filter = new LogFilter();

    @Test
    @DisplayName("命中行按出现顺序返回")
    void returnsHitsInOrder() {
        String logcat = "a\nonEvent isIn=true\nb\nonEvent isIn=true\nc";
        List<String> hits = filter.filter(logcat, "isIn=true");
        assertThat(hits).containsExactly("onEvent isIn=true", "onEvent isIn=true");
    }

    @Test
    @DisplayName("子串匹配（非整行匹配）")
    void matchesSubstringNotWholeLine() {
        assertThat(filter.filter("prefix-mid-suffix", "mid")).containsExactly("prefix-mid-suffix");
    }

    @Test
    @DisplayName("未命中返回空列表")
    void noHitReturnsEmpty() {
        assertThat(filter.filter("abc\ndef", "zzz")).isEmpty();
    }

    @Test
    @DisplayName("logcat 为 null/空 时返回空列表")
    void blankLogcatReturnsEmpty() {
        assertThat(filter.filter(null, "x")).isEmpty();
        assertThat(filter.filter("", "x")).isEmpty();
        assertThat(filter.filter("   ", "x")).isEmpty();
    }

    @Test
    @DisplayName("keyword 为 null/空白 时返回空列表")
    void blankKeywordReturnsEmpty() {
        assertThat(filter.filter("abc", null)).isEmpty();
        assertThat(filter.filter("abc", "")).isEmpty();
        assertThat(filter.filter("abc", "   ")).isEmpty();
    }

    @Test
    @DisplayName("子串匹配大小写敏感")
    void isCaseSensitive() {
        assertThat(filter.filter("ABC", "abc")).isEmpty();
    }
}
