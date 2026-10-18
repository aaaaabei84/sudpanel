package com.sudpanel.runner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RunService 纯函数测试：{@code @reuse:} 坐标复用指令的解析。
 *
 * <p>覆盖重点（2026-10-03 补，v1.5 #24）：
 * <ul>
 *   <li>普通 resource-id 必须原样走常规路径（返回 null）；</li>
 *   <li>合法的复用指令要取出被复用键，且容忍键前后空白；</li>
 *   <li>「只写了前缀、没写键」是配置错误，必须与「不是指令」区分开（返回空串而非 null），
 *       否则会被当成一个名叫 {@code @reuse:} 的 resource-id 去 dump，报出误导性的错误。</li>
 * </ul>
 */
class RunServiceTest {

    @Test
    @DisplayName("普通 resource-id 不是复用指令，返回 null")
    void plainResourceIdIsNotReuse() {
        assertThat(RunService.reuseSourceKey("com.example.app:id/btn_play")).isNull();
    }

    @Test
    @DisplayName("合法复用指令取出被复用键")
    void parsesReuseSourceKey() {
        assertThat(RunService.reuseSourceKey("@reuse:app_common_self_playing"))
                .isEqualTo("app_common_self_playing");
    }

    @Test
    @DisplayName("复用指令容忍被复用键前后的空白")
    void trimsSurroundingWhitespace() {
        assertThat(RunService.reuseSourceKey("@reuse:  app_common_self_playing  "))
                .isEqualTo("app_common_self_playing");
    }

    @Test
    @DisplayName("只有前缀没有键 → 返回空串（配置错误，不是普通 id）")
    void missingSourceKeyReturnsEmptyString() {
        assertThat(RunService.reuseSourceKey("@reuse:")).isEmpty();
        assertThat(RunService.reuseSourceKey("@reuse:   ")).isEmpty();
    }

    @Test
    @DisplayName("null / 空串不抛异常，返回 null")
    void nullAndBlankAreSafe() {
        assertThat(RunService.reuseSourceKey(null)).isNull();
        assertThat(RunService.reuseSourceKey("")).isNull();
    }

    @Test
    @DisplayName("前缀出现在中间不算指令（必须从头开始）")
    void prefixMustBeLeading() {
        assertThat(RunService.reuseSourceKey("com.x:id/@reuse:foo")).isNull();
    }
}
