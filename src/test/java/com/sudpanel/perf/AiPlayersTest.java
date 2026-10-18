package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 人机名单注入的测试（需求 #68；is_ai 规则改自需求 #69）。
 *
 * <p>要钉住的是三件事，每一件错了都不会报错、只会让压测「看起来在跑但其实没测对」：
 * <ol>
 *   <li><b>is_ai 规则：第 1 条真人（0）、其余人机（1）</b> —— 全推成 0 房间填不满，
 *       全推成 1 又没了真人身份，两种都采不到「真实对局」的性能；</li>
 *   <li><b>uid / 昵称必须互不相同</b> —— 都叫 cy01 的话，服务端可能只认一个，实际进房人数对不上；</li>
 *   <li><b>改写不成立时原样返回</b> —— 请求体格式特殊（不是 JSON、没有 user_infos）时
 *       宁可按用例原文推，也不能抛异常把整轮压测打断。</li>
 * </ol>
 */
class AiPlayersTest {

    /** 与使用者环境里那条用例同构的请求体：两条用户，is_ai 是 0。 */
    private static final String BODY = """
            {
              "event": "user_in_batch",
              "mg_id": "{{mg_id}}",
              "app_id": "{{app_id}}",
              "data": {
                "user_infos": [
                  {"uid": "cy01", "nick_name": "cy01", "is_ai": 0, "ai_level": 0, "seat_index": -1,
                   "is_seat_random": true, "team_id": 1, "is_ready": false, "avatar_url": "a.png"},
                  {"uid": "cy02", "nick_name": "cy02", "is_ai": 0, "ai_level": 1, "seat_index": -1,
                   "is_seat_random": false, "team_id": 1, "is_ready": true, "avatar_url": "b.png"}
                ],
                "room_id": "{{room_id}}",
                "mode": 1
              }
            }
            """;

    @Test
    @DisplayName("★ 按名单重排：条数 = 真人+人机，前 humans 条 is_ai=0、其余全是 1")
    void rebuildsToRequestedRoster() {
        AiPlayers.Applied r = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(2, 6, null, null));

        assertThat(r.applied()).isTrue();
        assertThat(AiPlayers.playerCountOf(r.body())).isEqualTo(8);
        // 需求 #70 的规则：恰好 humans 条 is_ai=0（真人），其余 ais 条全是 1。
        assertThat(countOf(r.body(), "\"is_ai\" : 0")).isEqualTo(2);
        assertThat(countOf(r.body(), "\"is_ai\" : 1")).isEqualTo(6);
        // 真人恰好在名单前 2 位（cy01、cy02 的 is_ai 都是 0，cy03 起是 1）
        int cy01 = r.body().indexOf("\"uid\" : \"cy01\"");
        int cy03 = r.body().indexOf("\"uid\" : \"cy03\"");
        assertThat(r.body().indexOf("\"is_ai\" : 0")).isGreaterThan(cy01).isLessThan(cy03);
        assertThat(countOf(r.body().substring(cy03), "\"is_ai\" : 0")).isZero();
    }

    @Test
    @DisplayName("★ 真人缺省 1（#69 口径）：只配人机数时名单 = 1 真人 + N 人机")
    void defaultOneHuman() {
        AiPlayers.Applied r = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, 7, null, null));

        assertThat(countOf(r.body(), "\"is_ai\" : 0")).isEqualTo(1);
        assertThat(countOf(r.body(), "\"is_ai\" : 1")).isEqualTo(7);
        assertThat(AiPlayers.playerCountOf(r.body())).isEqualTo(8);
    }

    @Test
    @DisplayName("★ ai_level 配了就写进每个人机条目，真人条不动")
    void levelGoesToAiEntriesOnly() {
        AiPlayers.Applied r = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(2, 6, 3, null));

        assertThat(r.applied()).isTrue();
        assertThat(r.detail()).contains("ai_level=3");
        // 样板 BODY 里原本没有 ai_level 字段（第一条样本 ai_level: 0 有吗？有，样板是 0/1）
        // 重排后 8 条全部有 ai_level：前 2 条沿用样板值，后 6 条 = 3
        assertThat(countOf(r.body(), "\"ai_level\" : 3")).isEqualTo(6);
    }

    @Test
    @DisplayName("★ 本机账号 uid 配了：真人首条用它做 uid/nick_name（is_ai=0），人机条不受影响")
    void machineUidBecomesFirstHuman() {
        AiPlayers.Applied r = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, 3, 2, "player007"));

        assertThat(r.applied()).isTrue();
        assertThat(countOf(r.body(), "\"uid\" : \"player007\"")).isEqualTo(1);
        assertThat(countOf(r.body(), "\"nick_name\" : \"player007\"")).isEqualTo(1);
        assertThat(countOf(r.body(), "\"uid\" : \"cy02\"")).isEqualTo(1);
        assertThat(countOf(r.body(), "\"uid\" : \"cy01\"")).isZero();
        // 真人首条仍 is_ai=0；detail 里能看到首位身份
        int u007 = r.body().indexOf("player007");
        assertThat(r.body().indexOf("\"is_ai\" : 0")).isGreaterThan(u007);
        assertThat(r.detail()).contains("首位=player007");
    }

    @Test
    @DisplayName("本机账号 uid 留空/空白：沿用 cy01（行为与 #70 完全一致）")
    void machineUidBlankFallsBack() {
        AiPlayers.Applied blank = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, 2, null, ""));
        AiPlayers.Applied ws = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, 2, null, "   "));

        assertThat(blank.body()).contains("\"uid\" : \"cy01\"");
        assertThat(ws.body()).contains("\"uid\" : \"cy01\"");
    }

    @Test
    @DisplayName("ai_level 不配（null）：沿用样板里的原值，一个字节不多写")
    void levelNullKeepsSample() {
        AiPlayers.Applied r = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, 3, null, null));

        assertThat(r.applied()).isTrue();
        // 样板两条的 ai_level 是 0 和 1，轮流复制 4 条：0、1、0、1
        assertThat(countOf(r.body(), "\"ai_level\" : 0")).isEqualTo(2);
        assertThat(countOf(r.body(), "\"ai_level\" : 1")).isEqualTo(2);
        assertThat(r.detail()).doesNotContain("ai_level");
    }

    @Test
    @DisplayName("★ uid 与昵称逐条不同（同名会让服务端只认一个，实际进房人数对不上）")
    void uidsAreUnique() {
        String body = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, 7, null, null)).body();

        for (int i = 1; i <= 8; i++) {
            String uid = "cy" + String.format("%02d", i);
            assertThat(body).contains("\"uid\" : \"" + uid + "\"");
        }
        // 8 个 uid 各不相同 = 恰好 8 个出现一次
        assertThat(countOf(body, "\"uid\"")).isEqualTo(8);
    }

    @Test
    @DisplayName("样板的其余字段原样保留（头像、等级、分组都是用例里那份）")
    void keepsSampleFields() {
        String body = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, 3, null, null)).body();

        // 两条样板轮流用：第 1、3 条取第一条的 a.png，第 2、4 条取第二条的 b.png
        assertThat(countOf(body, "a.png")).isEqualTo(2);
        assertThat(countOf(body, "b.png")).isEqualTo(2);
        assertThat(body).contains("\"room_id\" : \"{{room_id}}\"");
        assertThat(body).contains("\"mg_id\" : \"{{mg_id}}\"");
    }

    @Test
    @DisplayName("★ 每个都标成就绪、座位交给服务端分配")
    void marksReadyAndAutoSeat() {
        String body = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, 7, null, null)).body();

        assertThat(countOf(body, "\"is_ready\" : true")).isEqualTo(8);
        // seat_index 写死具体座位号会在多人时互相顶掉，必须是 -1
        assertThat(countOf(body, "\"seat_index\" : -1")).isEqualTo(8);
    }

    // ---------- 改写不成立时：原样返回，绝不抛 ----------

    @Test
    @DisplayName("★ 请求体不是 JSON：原样返回并给出原因（不能抛，压测正跑着）")
    void nonJsonFallsBack() {
        AiPlayers.Applied r = AiPlayers.withRoster("not a json at all", new AiPlayers.RosterSpec(1, 7, null, null));

        assertThat(r.applied()).isFalse();
        assertThat(r.body()).isEqualTo("not a json at all");
        assertThat(r.detail()).contains("不是合法 JSON");
    }

    @Test
    @DisplayName("没有 data.user_infos 数组时原样返回（用例写的是别的推送格式）")
    void missingUserInfosFallsBack() {
        String other = "{\"event\":\"game_start\",\"data\":{\"room_id\":\"1\"}}";

        AiPlayers.Applied r = AiPlayers.withRoster(other, new AiPlayers.RosterSpec(1, 7, null, null));

        assertThat(r.applied()).isFalse();
        assertThat(r.body()).isEqualTo(other);
        assertThat(r.detail()).contains("user_infos");
    }

    @Test
    @DisplayName("user_infos 是空数组时也不算改写成功")
    void emptyUserInfosFallsBack() {
        String empty = "{\"data\":{\"user_infos\":[]}}";

        assertThat(AiPlayers.withRoster(empty, new AiPlayers.RosterSpec(1, 7, null, null)).applied()).isFalse();
    }

    @Test
    @DisplayName("全零、负数、超上限、请求体为空：都按原文推")
    void badCountsFallBack() {
        assertThat(AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(0, 0, null, null)).applied()).isFalse();
        assertThat(AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(-1, 3, null, null)).applied()).isFalse();
        assertThat(AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, AiPlayers.MAX_COUNT, null, null)).applied()).isFalse();
        assertThat(AiPlayers.withRoster(null, new AiPlayers.RosterSpec(1, 7, null, null)).applied()).isFalse();
        assertThat(AiPlayers.withRoster("  ", new AiPlayers.RosterSpec(1, 7, null, null)).applied()).isFalse();
    }

    @Test
    @DisplayName("边界：0 人机（纯真人）与 1 人机都算改写成功（不该被当成非法）")
    void boundariesAreValid() {
        AiPlayers.Applied zeroAi = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, 0, null, null));
        assertThat(zeroAi.applied()).isTrue();
        assertThat(AiPlayers.playerCountOf(zeroAi.body())).isEqualTo(1);
        assertThat(countOf(zeroAi.body(), "\"is_ai\" : 0")).isEqualTo(1);

        AiPlayers.Applied zeroHuman = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(0, 4, null, null));
        assertThat(zeroHuman.applied()).isTrue();
        assertThat(countOf(zeroHuman.body(), "\"is_ai\" : 0")).isZero();
        assertThat(countOf(zeroHuman.body(), "\"is_ai\" : 1")).isEqualTo(4);
    }

    // ---------- forceFirstHuman：原文路径也要满足「第 1 条是真人」 ----------

    @Test
    @DisplayName("★ 第 1 条是 is_ai=1 的请求体：改成 0，第 2 条保持 1")
    void forceFirstHumanWhenFirstIsAi() {
        String body = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, 1, null, null)).body(); // 生成的是 cy01=0、cy02=1
        // 把第 1 条的 0 改回 1，模拟「用例模板里第一条也写成 1」的真实场景
        String rewritten = body.replaceFirst("\"is_ai\" : 0", "\"is_ai\" : 1");

        AiPlayers.Applied r = AiPlayers.forceFirstHuman(rewritten);

        assertThat(r.applied()).isTrue();
        assertThat(countOf(r.body(), "\"is_ai\" : 0")).isEqualTo(1);
        assertThat(countOf(r.body(), "\"is_ai\" : 1")).isEqualTo(1);
        // 改回来的是第 1 条（cy01 之后的第一个 is_ai）
        int cy01 = r.body().indexOf("\"uid\" : \"cy01\"");
        int firstZero = r.body().indexOf("\"is_ai\" : 0");
        assertThat(firstZero).isGreaterThan(cy01).isLessThan(r.body().indexOf("\"uid\" : \"cy02\""));
    }

    @Test
    @DisplayName("第 1 条本来就是真人：判「没改」，请求体原样（语义已满足不折腾）")
    void forceFirstHumanNoopWhenAlreadyHuman() {
        AiPlayers.Applied r = AiPlayers.forceFirstHuman(BODY);

        assertThat(r.applied()).isFalse();
        assertThat(r.body()).isEqualTo(BODY);
        assertThat(r.detail()).contains("本来就是真人");
    }

    @Test
    @DisplayName("forceFirstHuman 的宽容语义：非法 JSON / 没有 user_infos / 空数组都原样返回")
    void forceFirstHumanFallsBack() {
        assertThat(AiPlayers.forceFirstHuman("not json").applied()).isFalse();
        assertThat(AiPlayers.forceFirstHuman("{\"data\":{}}").applied()).isFalse();
        assertThat(AiPlayers.forceFirstHuman("{\"data\":{\"user_infos\":[]}}").applied()).isFalse();
        assertThat(AiPlayers.forceFirstHuman(null).applied()).isFalse();
        // 原样返回时 body 不能被动过
        assertThat(AiPlayers.forceFirstHuman("not json").body()).isEqualTo("not json");
    }

    // ---------- summarize：把名单压成一句人话 ----------

    @Test
    @DisplayName("★ 名单摘要：真人 1（uid）+ 人机 N，一句可读")
    void summarizeCounts() {
        String body = AiPlayers.withRoster(BODY, new AiPlayers.RosterSpec(1, 7, null, null)).body();

        String s = AiPlayers.summarize(body);

        assertThat(s).isEqualTo("8 人：真人 1（cy01） + 人机 7");
    }

    @Test
    @DisplayName("is_ai 取值异常的条目要被点出来（模板写岔了值得被看见）")
    void summarizeShowsOddValues() {
        String odd = "{\"data\":{\"user_infos\":[{\"uid\":\"u1\",\"is_ai\":0},{\"uid\":\"u2\",\"is_ai\":5}]}}";

        assertThat(AiPlayers.summarize(odd)).isEqualTo("2 人：真人 1（u1） + 人机 0 + is_ai 取值异常 1");
    }

    @Test
    @DisplayName("取不到名单时摘要返回 null（调用方就不打这行日志）")
    void summarizeNullWhenUnknown() {
        assertThat(AiPlayers.summarize("{\"data\":{}}")).isNull();
        assertThat(AiPlayers.summarize("garbage")).isNull();
        assertThat(AiPlayers.summarize(null)).isNull();
    }

    // ---------- 数人数 ----------

    @Test
    @DisplayName("数人数：能数出来就数，数不出来返回 -1（不是 0，0 是个合法数量）")
    void countsPlayers() {
        assertThat(AiPlayers.playerCountOf(BODY)).isEqualTo(2);
        assertThat(AiPlayers.playerCountOf("{\"data\":{}}")).isEqualTo(-1);
        assertThat(AiPlayers.playerCountOf("garbage")).isEqualTo(-1);
        assertThat(AiPlayers.playerCountOf(null)).isEqualTo(-1);
    }

    /** 数某段文本出现的次数。断言「一共 8 个」比断言「包含某个值」严格得多。 */
    private static int countOf(String haystack, String needle) {
        int n = 0;
        int i = haystack.indexOf(needle);
        while (i >= 0) {
            n++;
            i = haystack.indexOf(needle, i + needle.length());
        }
        return n;
    }
}
