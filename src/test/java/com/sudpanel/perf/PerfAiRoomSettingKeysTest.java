package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 人机对局配置键的清单（2026-10-06 #68 / 2026-10-09 v1.9.4 #78）。
 *
 * <p>这份清单在三个地方被当作「一次读回全部配置」的依据：页码加载时的 GET、保存时的 PUT、
 * 以及压测运行时逐项取用。**新增配置项忘了加进清单**的后果是「页面上填了、存不进去」，
 * 而且界面上不会报错 —— 属于最难自己发现的一类缺陷，所以用一条断言钉住。
 */
class PerfAiRoomSettingKeysTest {

    @Test
    @DisplayName("★ 对局结束关键字在清单里（#78 新增项漏了它 = 页面上填了存不进去）")
    void containsEndKeyword() {
        List<String> keys = PerfRepository.aiRoomSettingKeys();

        assertThat(keys).contains(PerfRepository.SETTING_AI_END_KEYWORD);
        assertThat(PerfRepository.SETTING_AI_END_KEYWORD).isEqualTo("ai_room_end_keyword");
    }

    @Test
    @DisplayName("原有的四项绑定与数量仍在（不要为了加一项而丢掉别的）")
    void keepsExistingKeys() {
        assertThat(PerfRepository.aiRoomSettingKeys()).containsExactly(
                PerfRepository.SETTING_AI_CASE_BATCH,
                PerfRepository.SETTING_AI_CASE_START,
                PerfRepository.SETTING_AI_CASE_END,
                PerfRepository.SETTING_AI_CASE_CLEAR,
                PerfRepository.SETTING_AI_COUNT,
                PerfRepository.SETTING_AI_END_KEYWORD);
    }
}
