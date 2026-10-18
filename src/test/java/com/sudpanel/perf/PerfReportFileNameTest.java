package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 报告文件名的安全字符规则（2026-10-08）。
 *
 * <p>要钉住的是：另存为/下载得到文件名里**不含** #、空格、括号，
 * 以减少文件分享和浏览器打开过程中的文件名兼容问题。游戏名均为合成 Demo。
 */
class PerfReportFileNameTest {

    @Test
    @DisplayName("括号与井号被清理，保留合成名称的中英文字符")
    void cleansBracketsAndHash() {
        String fn = PerfReportService.safeFileName("Demo游戏(示例)", 80);

        assertThat(fn).isEqualTo("性能报告-Demo游戏-示例-run80");
        assertThat(fn).doesNotContain("#").doesNotContain("(").doesNotContain(")").doesNotContain(" ");
    }

    @Test
    @DisplayName("英文与数字保留；其它特殊字符统一变横线")
    void keepsAscii() {
        assertThat(PerfReportService.safeFileName("Demo-Game-A", 66))
                .isEqualTo("性能报告-Demo-Game-A-run66");
        assertThat(PerfReportService.safeFileName("a/b\\c:d", 1))
                .isEqualTo("性能报告-a-b-c-d-run1");
    }

    @Test
    @DisplayName("游戏名为空也有兜底（不产生空文件名）")
    void blankFallsBack() {
        assertThat(PerfReportService.safeFileName(null, 9)).isEqualTo("性能报告-run9");
        assertThat(PerfReportService.safeFileName("", 9)).isEqualTo("性能报告-run9");
    }
}
