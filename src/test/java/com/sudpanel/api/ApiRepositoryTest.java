package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ApiRepository 的纯逻辑测试（v1.10）：批次里用例 id 列表的读写。
 *
 * <p>为什么要专门测这个：批次把用例 id 存成逗号分隔字符串，是典型的「写的时候图省事、
 * 读的时候埋雷」的结构。库里一旦混进空串或脏字符，整个批次都读不出来 ——
 * 一条坏 id 会让用户看到「批次打不开」，而真实原因只是多了一个逗号。
 */
class ApiRepositoryTest {

    @Test
    @DisplayName("正常逗号分隔")
    void parsesNormalCsv() {
        assertThat(ApiRepository.parseIds("1,2,3")).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("空串/null → 空列表，不抛异常")
    void parsesEmpty() {
        assertThat(ApiRepository.parseIds(null)).isEmpty();
        assertThat(ApiRepository.parseIds("")).isEmpty();
        assertThat(ApiRepository.parseIds("   ")).isEmpty();
    }

    @Test
    @DisplayName("多余空格与结尾逗号都能容忍")
    void toleratesSpacesAndTrailingComma() {
        assertThat(ApiRepository.parseIds(" 1 , 2 ,3, ")).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("脏字符只跳过它自己，其余照常读出 —— 不能因为一条坏数据让整个批次打不开")
    void skipsGarbageEntries() {
        List<Long> ids = ApiRepository.parseIds("1,abc,3");
        assertThat(ids).containsExactly(1L, 3L);
    }
}
