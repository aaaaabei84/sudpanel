package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 从 logcat 里提取对局 roundId（2026-10-10，v1.10.4 需求 #86）。
 *
 * <p>这个纯函数是整条链路里唯一不依赖真机、可以精确钉住的部分，所以把容错规则全写进测试：
 * <ul>
 *   <li>日志里这个键的出现方式不止一种，分隔符不能写死；</li>
 *   <li>尾部可能混着上一局的残留，取**最后一次**出现；</li>
 *   <li><b>宁可漏取，不要误取</b> —— 自然语言句子「gameRoundId is 123」不许匹配出 {@code is}。</li>
 * </ul>
 */
class PerfRunServiceRoundIdTest {

    @Test
    @DisplayName("★ 三种常见写法都能取到（等号 / 冒号 / JSON）")
    void extractsCommonForms() {
        assertThat(PerfRunService.extractGameRoundId("report gameRoundId=abc123 done"))
                .isEqualTo("abc123");
        assertThat(PerfRunService.extractGameRoundId("gameRoundId: 998877"))
                .isEqualTo("998877");
        assertThat(PerfRunService.extractGameRoundId("{\"gameRoundId\":\"uuid-1-2\"}"))
                .isEqualTo("uuid-1-2");
    }

    @Test
    @DisplayName("★ 取最后一次出现（尾部可能混着上一局的残留）")
    void takesTheLastOccurrence() {
        String log = """
                10-10 15:20:31.100  1234  1234 I GameCore: gameRoundId=OLD_ROUND
                10-10 15:20:31.200  1234  1234 I GameCore: 加载完成
                10-10 15:20:32.300  1234  1234 I GameCore: gameRoundId=NEW_ROUND
                """;
        assertThat(PerfRunService.extractGameRoundId(log)).isEqualTo("NEW_ROUND");
    }

    @Test
    @DisplayName("★ 带引号与空格也不许取错（值里可以有点、横线、下划线）")
    void tolerantToQuotesAndSpaces() {
        assertThat(PerfRunService.extractGameRoundId("gameRoundId = \"r-2026.10.10_01\""))
                .isEqualTo("r-2026.10.10_01");
        assertThat(PerfRunService.extractGameRoundId("\"gameRoundId\" : 'x9'"))
                .isEqualTo("x9");
    }

    @Test
    @DisplayName("★ 宁可漏取也不要误取：没有 : 或 = 就不许匹配")
    void neverMatchesWithoutSeparator() {
        // 放宽成「键名 + 几个非字母数字字符 + 值」的话，这一句会被取成 "is"
        assertThat(PerfRunService.extractGameRoundId("gameRoundId is 123")).isNull();
        assertThat(PerfRunService.extractGameRoundId("日志里提到了 gameRoundId 这个字段")).isNull();
        // 键名后面什么都没有
        assertThat(PerfRunService.extractGameRoundId("gameRoundId=")).isNull();
        assertThat(PerfRunService.extractGameRoundId("gameRoundId:  ")).isNull();
    }

    @Test
    @DisplayName("没有日志 / 空日志返回 null，不抛异常")
    void handlesEmptyInput() {
        assertThat(PerfRunService.extractGameRoundId(null)).isNull();
        assertThat(PerfRunService.extractGameRoundId("")).isNull();
        assertThat(PerfRunService.extractGameRoundId("与本局无关的一堆日志")).isNull();
    }

    @Test
    @DisplayName("合成状态上报：JSON 与日志前缀不影响 roundId 提取")
    void worksOnSyntheticReportFormat() {
        // Test-only：以下时间、进程号、玩家标识与对局标识均为合成值。
        // 保留公开协议的状态字段及 JSON/JS 日志前缀形状，验证解析边界。
        String line = "01-01 00:00:00.123 1000 1001 I DemoNative: 162:JS:INFO:"
                + "notifyAppChangePlayerStatus=====>>>>>>>>> DEMO_PLAYER_001 mg_common_player_playing "
                + "{\"isPlaying\":false,\"retCode\":0,\"spaceMax\":false,"
                + "\"gameRoundId\":\"FAKE_ROUND_001\",\"reason\":3}";
        assertThat(PerfRunService.extractGameRoundId(line)).isEqualTo("FAKE_ROUND_001");

        // 同一局开局时会再写一次（isPlaying=true），两次都要能取到
        String startLine = line.replace("\"isPlaying\":false", "\"isPlaying\":true").replace("\"reason\":3", "\"reason\":\"\"");
        assertThat(PerfRunService.extractGameRoundId(startLine)).isEqualTo("FAKE_ROUND_001");
    }

    @Test
    @DisplayName("合成日志行：带时间戳、pid、tag 前缀也能取到")
    void worksOnRealisticLogcatLine() {
        String line = "01-01 00:00:01.345 1000 1000 I DemoGameCore: [settle] round done "
                + "gameRoundId=DEMO_ROUND_002, cost=402s";
        assertThat(PerfRunService.extractGameRoundId(line)).isEqualTo("DEMO_ROUND_002");
    }
}
