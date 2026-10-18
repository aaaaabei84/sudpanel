package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 复制分组时的取名规则（2026-10-10，v1.10.6）。
 *
 * <p>钉住三条：连续复制要得到 2、3、4 而不是「副本的副本」；名字里**已有**的序号要先剥掉
 * （否则「分组 2」复制出来叫「分组 2 2」）；基名过长时要截断，别让复制出来的名字直接被
 * 60 字上限拒掉 —— 那会让「复制」这个动作在长名字上莫名其妙失败。
 */
class NextGroupNameTest {

    @Test
    @DisplayName("★ 连续复制得到 2、3、4，不是「副本的副本」")
    void incrementsInsteadOfAppendingSuffix() {
        assertThat(ApiRepository.nextGroupName("分组", Set.of())).isEqualTo("分组 2");
        assertThat(ApiRepository.nextGroupName("分组", Set.of("分组", "分组 2"))).isEqualTo("分组 3");
        assertThat(ApiRepository.nextGroupName("分组", Set.of("分组", "分组 2", "分组 3")))
                .isEqualTo("分组 4");
    }

    @Test
    @DisplayName("★ 名字里已有序号时从它的下一个开始（不是跳回 2，也不是「分组 2 2」）")
    void continuesFromExistingNumber() {
        // 已有序号 → 从 N+1 开始，复制「分组 2」得到「分组 3」
        assertThat(ApiRepository.nextGroupName("分组 2", Set.of("分组", "分组 2")))
                .isEqualTo("分组 3");
        // 「分组 7」复制出「分组 8」而不是「分组 4」——后者看着像随机数
        assertThat(ApiRepository.nextGroupName("分组 7", Set.of("分组 2", "分组 3")))
                .isEqualTo("分组 8");
        // N+1 也被占了就继续往后
        assertThat(ApiRepository.nextGroupName("分组 7", Set.of("分组 8")))
                .isEqualTo("分组 9");
    }

    @Test
    @DisplayName("纯数字名字没有空格分隔的序号，按普通基名处理（复制「12」得到「12 2」）")
    void pureNumberIsTreatedAsBase() {
        assertThat(ApiRepository.nextGroupName("12", Set.of())).isEqualTo("12 2");
    }

    @Test
    @DisplayName("序号不连续时取最小的空位（删掉中间的组不影响）")
    void picksSmallestFreeSlot() {
        assertThat(ApiRepository.nextGroupName("分组", Set.of("分组 2", "分组 4")))
                .isEqualTo("分组 3");
    }

    @Test
    @DisplayName("空名/纯空白回退成「分组」，不抛异常")
    void handlesBlankBase() {
        assertThat(ApiRepository.nextGroupName(null, Set.of())).isEqualTo("分组 2");
        assertThat(ApiRepository.nextGroupName("   ", Set.of())).isEqualTo("分组 2");

    }

    @Test
    @DisplayName("★ 基名过长时截断，结果不超过 60 字（createCaseGroup 的上限）")
    void truncatesToFitNameLimit() {
        String longName = "很长的分组名".repeat(20);
        String got = ApiRepository.nextGroupName(longName, Set.of());
        assertThat(got.length()).isLessThanOrEqualTo(60);
        assertThat(got).endsWith(" 2");
    }
}
