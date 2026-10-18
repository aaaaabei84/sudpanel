package com.sudpanel.perf;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ButtonGroupRepository 纯函数测试：外部 resourceKey → perf 按钮 key 的解析（v1.6 #26），
 * 以及按钮的「必标 / 选标」划分（v1.7 #31）。
 *
 * <p>覆盖重点：
 * <ul>
 *   <li>键名与 BUTTONS 相同 → 原样返回（apptomg 的 4 个大厅按钮走这条）；</li>
 *   <li>{@code app_common_self_end}（结束游戏）必须返回 null —— 它既不在 apptomg 自动测试点里
 *       （v1.9 起挪到 mgtoapp 人工走查，见 TestPointRepository），也没有可复用的标定坐标。
 *       这条是回归保护：若有人把 {@code app_common_self_end → perf_stop_game} 别名加回来，
 *       本测试会立刻失败并提醒他触发方式早已改变；</li>
 *   <li>perf 侧没有的按钮（振动/音效/背景音乐/重连）→ 返回 null，调用方据此走 dump；</li>
 *   <li>「退出游戏」「取消准备」**刻意不映射**：同位假设未经真机验证，必须返回 null；</li>
 *   <li>必标按钮必须恰好是「能被 dump 定位的 4 个大厅按钮」，多一个少一个都要报错
 *       —— 这条防止有人再把对局内按钮加进必标集合，让用户白标一遍。</li>
 * </ul>
 */
class ButtonGroupRepositoryTest {

    @Test
    @DisplayName("与 BUTTONS 同名的键原样返回")
    void sameNameKeyIsReturnedAsIs() {
        assertThat(ButtonGroupRepository.perfKeyFor("app_common_self_in")).isEqualTo("app_common_self_in");
        assertThat(ButtonGroupRepository.perfKeyFor("app_common_self_ready")).isEqualTo("app_common_self_ready");
        assertThat(ButtonGroupRepository.perfKeyFor("app_common_self_playing")).isEqualTo("app_common_self_playing");
        assertThat(ButtonGroupRepository.perfKeyFor("app_common_game_add_ai_players"))
                .isEqualTo("app_common_game_add_ai_players");
    }

    @Test
    @DisplayName("结束游戏不再映射（它已不是自动点击的测试点）")
    void endGameKeyIsNotMapped() {
        assertThat(ButtonGroupRepository.perfKeyFor("app_common_self_end")).isNull();
    }

    @Test
    @DisplayName("perf 侧没有的按钮返回 null（调用方据此走 dump）")
    void unknownKeysReturnNull() {
        assertThat(ButtonGroupRepository.perfKeyFor("app_common_open_vibrate")).isNull();
        assertThat(ButtonGroupRepository.perfKeyFor("app_common_open_sound")).isNull();
        assertThat(ButtonGroupRepository.perfKeyFor("app_common_open_bg_music")).isNull();
        assertThat(ButtonGroupRepository.perfKeyFor("app_common_game_reconnect")).isNull();
    }

    @Test
    @DisplayName("取消态按钮刻意不映射（同位假设未验证）")
    void cancelVariantsAreNotMapped() {
        assertThat(ButtonGroupRepository.perfKeyFor("app_common_self_in_cancel")).isNull();
        assertThat(ButtonGroupRepository.perfKeyFor("app_common_self_ready_cancel")).isNull();
    }

    @Test
    @DisplayName("null / 空串 / 前后空白都安全")
    void nullAndBlankAreSafe() {
        assertThat(ButtonGroupRepository.perfKeyFor(null)).isNull();
        assertThat(ButtonGroupRepository.perfKeyFor("")).isNull();
        assertThat(ButtonGroupRepository.perfKeyFor("   ")).isNull();
        assertThat(ButtonGroupRepository.perfKeyFor("  app_common_self_ready  "))
                .isEqualTo("app_common_self_ready");
    }

    @Test
    @DisplayName("必标按钮恰好是 4 个大厅按钮（对局内按钮不得进入必标集合）")
    void requiredButtonsAreExactlyTheLobbyOnes() {
        List<String> required = ButtonGroupRepository.BUTTONS.stream()
                .filter(ButtonGroupRepository.ButtonDef::required)
                .map(ButtonGroupRepository.ButtonDef::key)
                .toList();
        assertThat(required).containsExactly(
                "app_common_self_in",
                "app_common_game_add_ai_players",
                "app_common_self_ready",
                "app_common_self_playing");
    }

    @Test
    @DisplayName("可标定按钮恰好 6 个，且对局内两个是选标")
    void buttonSetIsExactlyTheSixCalibratableOnes() {
        assertThat(ButtonGroupRepository.BUTTONS).hasSize(6);
        for (String key : List.of("perf_stop_game", "perf_exit_game")) {
            ButtonGroupRepository.ButtonDef def = ButtonGroupRepository.BUTTONS.stream()
                    .filter(b -> b.key().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("BUTTONS 里缺少按钮: " + key));
            assertThat(def.required()).as("按钮 %s 应为选标", key).isFalse();
        }
    }
}
