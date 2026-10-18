package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sudpanel.api.ApiSpec;

/**
 * 人机对局模式「四条用例怎么找」的测试（需求 #68）。
 *
 * <p>要找错的后果很具体：<b>推错了用例</b>。而推错指令在压测结果上不会自曝 ——
 * 房间里人不对、或者根本没开局，症状看起来像「接口不通」，查起来会一路往网络和权限跑。
 * 所以这里的判据收得比较紧：<b>名字命中不止一条时宁可判找不到</b>，也不猜一条出来用。
 */
class AiCaseResolveTest {

    private static ApiSpec spec(long id, String name) {
        return new ApiSpec(id, name, "POST", "http://h/push", "{}", "{}", null,
                "200", "0", 10000, false, true, null, null);
    }

    private static final List<ApiSpec> CASES = List.of(
            spec(1, "加入游戏"),
            spec(3, "房间清理"),
            spec(4, "批量加入"),
            spec(15, "游戏开始"),
            spec(17, "游戏结束"));

    @Test
    @DisplayName("配了有效 id：直接用那一条（不看名字）")
    void configuredIdWins() {
        assertThat(PerfRunService.resolveCaseRef("3", "批量加入", CASES)).isEqualTo(3L);
    }

    @Test
    @DisplayName("★ 配的 id 已被删掉：回退按名字找，而不是判失败")
    void staleIdFallsBackToName() {
        // 999 不在列表里（用例被删了）。此时该用默认名字那条，否则用户删一条用例就得手工重配
        assertThat(PerfRunService.resolveCaseRef("999", "房间清理", CASES)).isEqualTo(3L);
    }

    @Test
    @DisplayName("没配（null / 空 / 空白）：按名字找")
    void blankFallsBackToName() {
        assertThat(PerfRunService.resolveCaseRef(null, "游戏开始", CASES)).isEqualTo(15L);
        assertThat(PerfRunService.resolveCaseRef("", "游戏开始", CASES)).isEqualTo(15L);
        assertThat(PerfRunService.resolveCaseRef("   ", "游戏开始", CASES)).isEqualTo(15L);
    }

    @Test
    @DisplayName("配的不是数字：按名字找（不抛异常）")
    void garbageIdFallsBackToName() {
        assertThat(PerfRunService.resolveCaseRef("abc", "游戏结束", CASES)).isEqualTo(17L);
    }

    @Test
    @DisplayName("★ 名字精确匹配优先于包含匹配")
    void exactNameWins() {
        List<ApiSpec> cases = List.of(spec(10, "开始游戏"), spec(11, "开始"));

        // 「开始」是「开始游戏」的子串，但有一条精确叫「开始」的，就该用那一条
        assertThat(PerfRunService.resolveCaseRef(null, "开始", cases)).isEqualTo(11L);
    }

    @Test
    @DisplayName("唯一包含时可以用（用例名带了前后缀，如「001 批量加入」）")
    void uniqueContainsWorks() {
        List<ApiSpec> cases = List.of(spec(4, "001 批量加入"), spec(15, "游戏开始"));

        assertThat(PerfRunService.resolveCaseRef(null, "批量加入", cases)).isEqualTo(4L);
    }

    @Test
    @DisplayName("★ 包含命中不止一条：判找不到，不猜（猜错的代价是推错指令）")
    void ambiguousContainsIsNull() {
        // 两条都只是「包含」，没有一条精确等于要找的名字 —— 这才是真的挑不出来。
        // （第一版拿「批量加入」+「批量加入(测试)」当例子是错的：前者精确命中，不该算歧义，
        //   断言当场把我这条测试自身的错给指出来了。）
        List<ApiSpec> cases = List.of(spec(1, "批量加入A"), spec(2, "批量加入B"));

        assertThat(PerfRunService.resolveCaseRef(null, "批量加入", cases)).isNull();
    }

    @Test
    @DisplayName("都没有：返回 null（由启动时的校验报出缺哪一条）")
    void notFoundIsNull() {
        assertThat(PerfRunService.resolveCaseRef(null, "不存在的用例", CASES)).isNull();
        assertThat(PerfRunService.resolveCaseRef("999", "不存在的用例", CASES)).isNull();
    }

    @Test
    @DisplayName("空列表 / null 列表：返回 null（不抛）")
    void emptyListIsNull() {
        assertThat(PerfRunService.resolveCaseRef("1", "批量加入", List.of())).isNull();
        assertThat(PerfRunService.resolveCaseRef("1", "批量加入", null)).isNull();
    }

    @Test
    @DisplayName("名字为空且没配 id：返回 null")
    void blankNameWithoutIdIsNull() {
        assertThat(PerfRunService.resolveCaseRef(null, null, CASES)).isNull();
        assertThat(PerfRunService.resolveCaseRef(null, "  ", CASES)).isNull();
    }

    @Test
    @DisplayName("前后空白不影响匹配（用例名被手敲进页面时最容易带空白）")
    void trimsBothSides() {
        assertThat(PerfRunService.resolveCaseRef(" 4 ", "批量加入", CASES)).isEqualTo(4L);
        assertThat(PerfRunService.resolveCaseRef(null, " 批量加入 ", CASES)).isEqualTo(4L);
    }

    @Test
    @DisplayName("四条默认名字都能在典型用例集里各找到一条（这是开箱可用的前提）")
    void defaultsAllResolvable() {
        assertThat(PerfRunService.resolveCaseRef(null, "批量加入", CASES)).isEqualTo(4L);
        assertThat(PerfRunService.resolveCaseRef(null, "游戏开始", CASES)).isEqualTo(15L);
        assertThat(PerfRunService.resolveCaseRef(null, "游戏结束", CASES)).isEqualTo(17L);
        assertThat(PerfRunService.resolveCaseRef(null, "房间清理", CASES)).isEqualTo(3L);
    }
}
