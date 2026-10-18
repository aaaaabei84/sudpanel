package com.sudpanel.adb;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上报查询取尾部的纯函数测试（2026-10-06，需求 #64）。
 *
 * <p>为什么这几条必须压住：这段代码替代的是设备端的 {@code -t N}，而 {@code -t N}
 * 在某些 ROM 上根本不按行数返回（实测 {@code -t 2000} 只回 21 行）。换成本地裁剪之后，
 * 边界错法的表现是「最后一行变成空串」或「莫名多一个空行」——
 * 前者会让锚点每轮都失效、界面不断整段重推，后者会让界面多出一条空日志。
 * 两种都不会报错，只是「看起来怪」。
 */
class AdbClientTest {

    @Test
    @DisplayName("取最后 n 行：只留尾部，前面的丢掉")
    void keepsOnlyTail() {
        assertThat(AdbClient.lastLines("a\nb\nc\nd", 2)).isEqualTo("c\nd");
        assertThat(AdbClient.lastLines("a\nb\nc\nd", 1)).isEqualTo("d");
    }

    @Test
    @DisplayName("行数不够时原样返回（不报错、不补空行）")
    void fewerLinesThanRequested() {
        assertThat(AdbClient.lastLines("a\nb", 10)).isEqualTo("a\nb");
        assertThat(AdbClient.lastLines("only", 5)).isEqualTo("only");
    }

    @Test
    @DisplayName("★ 末尾换行不算一行：logcat 输出总是以换行结尾，不削掉就会多一个空行")
    void trailingNewlineIsNotAnExtraLine() {
        assertThat(AdbClient.lastLines("a\nb\nc\n", 2)).isEqualTo("b\nc");
        // 不削的话这里会返回 "\n"（一个空行），而空行当锚点会让每轮都判成首次取文
        assertThat(AdbClient.lastLines("a\nb\nc\n", 1)).isEqualTo("c");
    }

    @Test
    @DisplayName("★ 结果里绝不含末尾空行（它是「锚点变空串」的唯一来源）")
    void neverProducesDanglingEmptyLine() {
        assertThat(AdbClient.lastLines("a\nb\n\n\n", 2)).isEqualTo("a\nb");
        assertThat(AdbClient.lastLines("a\nb\n", 1)).isEqualTo("b");
        assertThat(AdbClient.lastLines("\n\n\n", 5)).isEmpty();
    }

    @Test
    @DisplayName("行尾回车要去掉：Windows 上的 adb 会带回车，带上它下一轮锚点就匹配不上")
    void stripsCarriageReturn() {
        assertThat(AdbClient.lastLines("a\r\nb\r\n", 1)).isEqualTo("b");
        assertThat(AdbClient.lastLines("a\r\nb\r\n", 2)).isEqualTo("a\r\nb");
    }

    @Test
    @DisplayName("边界：null / 空串 / n<=0 都不抛错")
    void degenerateInputs() {
        assertThat(AdbClient.lastLines(null, 3)).isEmpty();
        assertThat(AdbClient.lastLines("", 3)).isEmpty();
        assertThat(AdbClient.lastLines("a\nb", 0)).isEqualTo("a\nb");
        assertThat(AdbClient.lastLines("a\nb", -1)).isEqualTo("a\nb");
    }

    @Test
    @DisplayName("结果去掉末尾换行后必定是原文的后缀（没有凭空造内容）")
    void resultIsAlwaysASuffix() {
        String text = "l1\nl2\nl3\nl4\nl5\n";
        for (int n = 1; n <= 6; n++) {
            String got = AdbClient.lastLines(text, n);
            assertThat(text.stripTrailing()).endsWith(got);
            assertThat(got).doesNotEndWith("\n");
        }
    }
}
