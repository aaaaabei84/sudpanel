package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 响应体断言的纯逻辑测试（2026-10-05，需求 #51）。
 *
 * <p>断言是「判错方向有两个」的一块：该失败的放过（假绿灯）与不该失败的拦下（假红灯）
 * 都很难在界面上看出来 —— 前者静默、后者表现为「接口明明是对的却红了」。
 * 所以这里刻意压边界，而不是走一遍主流程：
 * <ul>
 *   <li>数字：{@code 1.0} 与 {@code 1} 相等，但 {@code 1.0} 不满足 integer；</li>
 *   <li>路径：键不存在 / 值为 null / 下标越界 三者分开报；</li>
 *   <li>数组：{@code [*]} 全部满足、空数组判失败、通配用在非数组上；</li>
 *   <li>非 JSON：根路径仍可断「等于/包含」，按路径下钻则明确报错；</li>
 *   <li>配置本身：整行空要忽略、条件没选要报、整段解析不了不能静默放过。</li>
 * </ul>
 */
class ApiBodyAssertTest {

    private static final String BODY = """
            {"code":0,"data":{"roomId":10086,"roomName":"开黑房","ratio":1.0,"tags":["a","b"],
            "players":[{"uid":1,"seat":3,"ready":true},{"uid":2,"seat":1,"ready":false},
            {"uid":3,"seat":2,"ready":true}]}}
            """;

    private static List<ApiBodyAssert.Failure> run(String body, String path, String op, String expect) {
        return ApiBodyAssert.evaluate(body, List.of(new ApiBodyAssert.Rule(path, op, expect)));
    }

    private static void pass(String body, String path, String op, String expect) {
        assertThat(run(body, path, op, expect))
                .as("%s %s %s 应当通过", path, op, expect)
                .isEmpty();
    }

    private static String fail(String body, String path, String op, String expect) {
        List<ApiBodyAssert.Failure> f = run(body, path, op, expect);
        assertThat(f).as("%s %s %s 应当失败", path, op, expect).hasSize(1);
        return f.get(0).render();
    }

    // ---------- 没有断言 ----------

    @Test
    @DisplayName("没有规则时不产生任何失败")
    void noRulesNoFailure() {
        assertThat(ApiBodyAssert.evaluate(BODY, List.of())).isEmpty();
        assertThat(ApiBodyAssert.evaluate(BODY, null)).isEmpty();
    }

    // ---------- 类型 ----------

    @Test
    @DisplayName("integer 是 number 的子集：整数两个都过")
    void integerIsNumber() {
        pass(BODY, "data.roomId", "type", "integer");
        pass(BODY, "data.roomId", "type", "number");
    }

    @Test
    @DisplayName("1.0 是 number 但不是 integer（原文带小数点）")
    void decimalIsNotInteger() {
        pass(BODY, "data.ratio", "type", "number");
        String msg = fail(BODY, "data.ratio", "type", "integer");
        assertThat(msg).contains("带小数点的 number");
    }

    @Test
    @DisplayName("类型不符时说明期望与实际，并带上实际值")
    void typeMismatch() {
        String msg = fail(BODY, "data.roomId", "type", "string");
        assertThat(msg).contains("data.roomId").contains("期望类型 string").contains("实际是 number")
                .contains("10086");
    }

    @Test
    @DisplayName("对象与数组、null 各自可断")
    void objectArrayNullTypes() {
        pass(BODY, "data", "type", "object");
        pass(BODY, "data.players", "type", "array");
        pass("{\"data\":{\"x\":null}}", "data.x", "type", "null");
        // 默认的字符串/布尔/数字之外，未知类型名要明确报出来
        assertThat(fail(BODY, "data.roomId", "type", "long")).contains("无法识别的类型名");
        assertThat(fail(BODY, "data.roomId", "type", "")).contains("没有选择要断言的类型");
    }

    // ---------- 相等 ----------

    @Test
    @DisplayName("数字按数值比：equals 10086.0 与 10086 相等")
    void numericEqualsIsNumeric() {
        pass(BODY, "data.roomId", "equals", "10086");
        pass(BODY, "data.roomId", "equals", "10086.0");
        pass(BODY, "data.roomId", "equals", "1.0086E4");
    }

    @Test
    @DisplayName("字符串相等按原文")
    void textEquals() {
        pass(BODY, "data.roomName", "equals", "开黑房");
        String msg = fail(BODY, "data.roomName", "equals", "别的房");
        assertThat(msg).contains("期望 \"别的房\"").contains("开黑房");
    }

    @Test
    @DisplayName("布尔值按文本比较")
    void booleanEquals() {
        pass(BODY, "data.players[0].ready", "equals", "true");
        pass(BODY, "data.players[1].ready", "notEquals", "true");
    }

    @Test
    @DisplayName("不等于：相等时才算失败")
    void notEquals() {
        pass(BODY, "data.roomId", "notEquals", "1");
        assertThat(fail(BODY, "data.roomId", "notEquals", "10086")).contains("但实际就是它");
    }

    // ---------- 存在性 ----------

    @Test
    @DisplayName("键不存在 与 值为 null 分开报")
    void missingVersusNull() {
        String nullBody = "{\"data\":{\"x\":null}}";
        assertThat(fail(nullBody, "data.x", "exists", "")).contains("值是 null");
        assertThat(fail(nullBody, "data.y", "exists", "")).contains("键不存在");
        assertThat(fail(BODY, "data.nope", "exists", "")).contains("键不存在");
    }

    @Test
    @DisplayName("不存在：键没有、值为 null 都算通过；真的有值才算失败")
    void notExists() {
        String nullBody = "{\"data\":{\"x\":null}}";
        pass(nullBody, "data.x", "notExists", "");
        pass(nullBody, "data.y", "notExists", "");
        pass(BODY, "data.nope", "notExists", "");
        assertThat(fail(BODY, "data.roomId", "notExists", "")).contains("字段存在").contains("10086");
    }

    @Test
    @DisplayName("路径走到一半是标量：明确说走不下去，不糊成「键不存在」")
    void walkIntoScalar() {
        assertThat(fail(BODY, "data.roomId.foo", "exists", "")).contains("路径走不到底").contains("number");
    }

    // ---------- 数组 ----------

    @Test
    @DisplayName("[*] 全部元素都要满足")
    void wildcardAllMustSatisfy() {
        pass(BODY, "data.players[*].ready", "type", "boolean");
        pass(BODY, "data.players[*].uid", "type", "integer");
        pass(BODY, "data.tags[*]", "type", "string");
    }

    @Test
    @DisplayName("[*] 有元素不满足时报出是第几个")
    void wildcardReportsWhichElement() {
        String msg = fail(BODY, "data.players[*].ready", "equals", "true");
        assertThat(msg).contains("第 2 个元素不满足").contains("期望 \"true\"").contains("false");
    }

    @Test
    @DisplayName("[*] 遇到空数组一律判失败，不走「空集恒真」")
    void wildcardOnEmptyArrayFails() {
        String msg = fail("{\"data\":{\"list\":[]}}", "data.list[*]", "type", "string");
        assertThat(msg).contains("数组为空");
    }

    @Test
    @DisplayName("[*] 用在非数组上要明确报出来")
    void wildcardOnNonArray() {
        assertThat(fail(BODY, "data.roomId[*]", "exists", "")).contains("[*] 只能用在数组上");
    }

    @Test
    @DisplayName("下标越界：说明越界与真实长度")
    void indexOutOfRange() {
        assertThat(fail(BODY, "data.players[5]", "exists", "")).contains("下标 5 越界").contains("实际长度 3");
    }

    @Test
    @DisplayName("下标要写数字或 *")
    void badIndexSyntax() {
        assertThat(fail(BODY, "data.players[x]", "exists", "")).contains("下标要写数字或 *");
        assertThat(fail(BODY, "data.players[0", "exists", "")).contains("少了一个 ]");
    }

    @Test
    @DisplayName("数组元素可以继续往下钻")
    void nestedIntoArrayElement() {
        pass(BODY, "data.players[2].seat", "equals", "2");
        pass(BODY, "data.players[0].uid", "equals", "1");
    }

    // ---------- 长度 ----------

    @Test
    @DisplayName("长度等于：数组元素数 与 字符串字符数")
    void length() {
        pass(BODY, "data.players", "length", "3");
        pass(BODY, "data.tags", "length", "2");
        pass(BODY, "data.roomName", "length", "3");
        assertThat(fail(BODY, "data.players", "length", "4")).contains("期望长度 4").contains("实际 3");
        assertThat(fail(BODY, "data.roomId", "length", "1")).contains("只有数组和字符串有长度");
    }

    @Test
    @DisplayName("路径末段写 .length 与用「长度等于」等价（两种写法都认）")
    void lengthPathAlias() {
        pass(BODY, "data.players.length", "equals", "3");
        pass(BODY, "data.roomName.length", "equals", "3");
        // 真的有叫 length 的键时以真实键为准
        pass("{\"data\":{\"length\":99}}", "data.length", "equals", "99");
    }

    // ---------- 数值比较 ----------

    @Test
    @DisplayName("大于/大于等于/小于/小于等于")
    void comparisons() {
        pass(BODY, "data.roomId", "gt", "10000");
        pass(BODY, "data.roomId", "gte", "10086");
        pass(BODY, "data.roomId", "lt", "20000");
        pass(BODY, "data.roomId", "lte", "10086");
        assertThat(fail(BODY, "data.roomId", "gt", "10086")).contains("期望 大于 10086");
        assertThat(fail(BODY, "data.roomId", "lt", "1")).contains("期望 小于 1");
    }

    @Test
    @DisplayName("数值比较遇到非数字或期望值不是数字，要说清楚是哪一边的问题")
    void comparisonGuards() {
        assertThat(fail(BODY, "data.roomName", "gt", "1")).contains("实际不是数字");
        assertThat(fail(BODY, "data.roomId", "gt", "abc")).contains("期望值要写一个数字");
    }

    // ---------- 文本与集合 ----------

    @Test
    @DisplayName("包含子串")
    void contains() {
        pass(BODY, "data.roomName", "contains", "开黑");
        assertThat(fail(BODY, "data.roomName", "contains", "abc")).contains("期望包含");
    }

    @Test
    @DisplayName("属于其中之一：逗号分隔，中文逗号也认")
    void in() {
        pass(BODY, "data.roomId", "in", "10086,10087");
        pass(BODY, "data.roomId", "in", "1，10086");
        assertThat(fail(BODY, "data.roomId", "in", "1,2")).contains("期望属于其中之一");
    }

    // ---------- 非 JSON 与空响应体 ----------

    @Test
    @DisplayName("非 JSON：根路径仍可断等于/包含/长度")
    void nonJsonRoot() {
        pass("ok", "", "equals", "ok");
        pass("<html><body>500 error</body></html>", "", "contains", "500");
        pass("ok", "", "length", "2");
        pass("ok", "", "type", "string");
    }

    @Test
    @DisplayName("非 JSON 想按路径下钻：明确报出来，不糊成「键不存在」")
    void nonJsonWithPath() {
        String msg = fail("<html>err</html>", "data.code", "equals", "0");
        assertThat(msg).contains("响应体不是 JSON");
    }

    @Test
    @DisplayName("响应体为空：任何条件都失败并说明原因")
    void blankBody() {
        assertThat(fail("", "data.x", "exists", "")).contains("响应体为空");
        assertThat(fail("   ", "", "exists", "")).contains("响应体为空");
        assertThat(fail(null, "data.x", "type", "number")).contains("响应体为空");
    }

    // ---------- 多条一起报 ----------

    @Test
    @DisplayName("多条断言同时不过时全部列出（不是遇到第一条就停）")
    void reportsAllFailures() {
        List<ApiBodyAssert.Rule> rules = List.of(
                new ApiBodyAssert.Rule("data.roomId", "type", "string"),
                new ApiBodyAssert.Rule("data.roomName", "equals", "别的房"),
                new ApiBodyAssert.Rule("data.players", "length", "3"));
        List<ApiBodyAssert.Failure> fails = ApiBodyAssert.evaluate(BODY, rules);
        assertThat(fails).hasSize(2);
        assertThat(fails.get(0).render()).contains("data.roomId");
        assertThat(fails.get(1).render()).contains("data.roomName");
    }

    // ---------- 配置解析 ----------

    @Test
    @DisplayName("解析：null/空/整行空的草稿行都不算规则")
    void parseSkipsBlank() {
        assertThat(ApiBodyAssert.parse(null)).isEmpty();
        assertThat(ApiBodyAssert.parse("")).isEmpty();
        assertThat(ApiBodyAssert.parse("   ")).isEmpty();
        assertThat(ApiBodyAssert.parse("[{\"path\":\"\",\"op\":\"\",\"expect\":\"\"}]")).isEmpty();
        assertThat(ApiBodyAssert.parse("[]")).isEmpty();
    }

    @Test
    @DisplayName("解析：正常配置逐条读出来")
    void parseReadsRules() {
        String json = "[{\"path\":\"data.roomId\",\"op\":\"type\",\"expect\":\"integer\"},"
                + "{\"path\":\"data.players[*].ready\",\"op\":\"equals\",\"expect\":\"true\"}]";
        List<ApiBodyAssert.Rule> rules = ApiBodyAssert.parse(json);
        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).path()).isEqualTo("data.roomId");
        assertThat(rules.get(0).op()).isEqualTo("type");
        assertThat(rules.get(1).path()).isEqualTo("data.players[*].ready");
        assertThat(rules.get(1).expect()).isEqualTo("true");
        // 解析出来的规则要能直接拿去判定
        assertThat(ApiBodyAssert.evaluate(BODY, rules)).hasSize(1);
        assertThat(ApiBodyAssert.evaluate(BODY,
                ApiBodyAssert.parse("[{\"path\":\"data.roomId\",\"op\":\"type\",\"expect\":\"integer\"}]")))
                .isEmpty();
    }

    @Test
    @DisplayName("整段配置解析不了：明确失败，不能静默当成「没设断言」")
    void brokenConfigFailsLoudly() {
        List<ApiBodyAssert.Rule> broken = ApiBodyAssert.parse("这不是 JSON");
        assertThat(broken).hasSize(1);
        List<ApiBodyAssert.Failure> fails = ApiBodyAssert.evaluate(BODY, broken);
        assertThat(fails).hasSize(1);
        assertThat(fails.get(0).render()).contains("断言配置无法解析");
    }

    @Test
    @DisplayName("条件没选、条件名不认识：都报出来而不是放过")
    void badOperators() {
        assertThat(fail(BODY, "data.roomId", "", "")).contains("没有选择判断条件");
        assertThat(fail(BODY, "data.roomId", "  ", "")).contains("没有选择判断条件");
        assertThat(fail(BODY, "data.roomId", "blah", "")).contains("无法识别的判断条件");
    }

    @Test
    @DisplayName("条件名容忍大小写与分隔符差异")
    void operatorNameTolerance() {
        assertThat(ApiBodyAssert.evaluate(BODY, List.of(new ApiBodyAssert.Rule("data.x", "NOT_EXISTS", ""))))
                .isEmpty();
        assertThat(ApiBodyAssert.evaluate(BODY, List.of(new ApiBodyAssert.Rule("data.roomId", "Not-Equals", "1"))))
                .isEmpty();
    }

    @Test
    @DisplayName("超过上限必须明确失败，不能把第51条断言静默丢弃")
    void ruleCountCapped() {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < ApiBodyAssert.MAX_RULES + 20; i++) {
            parts.add("{\"path\":\"data.roomId\",\"op\":\"exists\",\"expect\":\"\"}");
        }
        List<ApiBodyAssert.Rule> rules = ApiBodyAssert.parse("[" + String.join(",", parts) + "]");
        assertThat(rules).hasSize(1);
        assertThat(ApiBodyAssert.evaluate(BODY, rules)).isNotEmpty();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> ApiBodyAssert.validateRules("[" + String.join(",", parts) + "]"));
    }

    @Test
    @DisplayName("根路径断言在整个响应体上生效")
    void rootAssertions() {
        pass(BODY, "", "type", "object");
        pass(BODY, "  ", "contains", "\"roomId\"");
        assertThat(fail(BODY, "", "notExists", "")).contains("字段存在");
        assertThat(fail(BODY, "", "equals", "{}")).contains("期望");
        assertThat(fail(BODY, "", "type", "array")).contains("期望类型 array").contains("实际是 object");
    }

    // ---------- 逐条判定结果（需求 #58）----------
    //
    // 这一组盯的是「通过的那半边也要能拿出来」：界面上要在断言全过时也显示一条
    // 「N 条全部通过」，否则一个绿灯分不清是断言过了还是压根没配断言。

    @Test
    @DisplayName("逐条判定：通过的也在结果里，且条数与规则数一致")
    void judgeAllKeepsPassing() {
        List<ApiBodyAssert.Judged> js = ApiBodyAssert.judgeAll(BODY, List.of(
                new ApiBodyAssert.Rule("data.roomId", "equals", "10086"),
                new ApiBodyAssert.Rule("data.roomName", "equals", "不存在的名字"),
                new ApiBodyAssert.Rule("data.tags[*]", "type", "string")));
        assertThat(js).hasSize(3);
        assertThat(js.get(0).ok()).isTrue();
        assertThat(js.get(1).ok()).isFalse();
        assertThat(js.get(2).ok()).isTrue();
        // 通过的 failure 必须是 null，「有 Failure 但 ok」这种自相矛盾不允许出现
        assertThat(js.get(0).failure()).isNull();
        assertThat(js.get(1).failure()).isNotNull();
    }

    @Test
    @DisplayName("逐条判定与 evaluate 是同一套判定（两个出口不许漂移）")
    void judgeAllAgreesWithEvaluate() {
        List<ApiBodyAssert.Rule> rules = List.of(
                new ApiBodyAssert.Rule("data.roomId", "equals", "10086"),
                new ApiBodyAssert.Rule("data.nope", "exists", ""),
                new ApiBodyAssert.Rule("data.roomId", "gt", "99999"),
                new ApiBodyAssert.Rule("", "type", "array"));
        List<String> fromJudge = ApiBodyAssert.judgeAll(BODY, rules).stream()
                .filter(j -> !j.ok())
                .map(j -> j.failure().render())
                .toList();
        List<String> fromEvaluate = ApiBodyAssert.evaluate(BODY, rules).stream()
                .map(ApiBodyAssert.Failure::render)
                .toList();
        assertThat(fromJudge).isEqualTo(fromEvaluate);
        assertThat(fromJudge).hasSize(3);
    }

    @Test
    @DisplayName("逐条判定：没有规则时返回空（不是一条空结果）")
    void judgeAllEmptyRules() {
        assertThat(ApiBodyAssert.judgeAll(BODY, List.of())).isEmpty();
        assertThat(ApiBodyAssert.judgeAll(BODY, null)).isEmpty();
        assertThat(ApiBodyAssert.judgeAll(null, List.of())).isEmpty();
    }

    @Test
    @DisplayName("逐条判定：结构性问题与 notexists 都如实反映在 ok 上")
    void judgeAllStructuralCases() {
        // notexists 命中不存在的键 —— evaluate 里算「通过」，这里也必须算通过
        assertThat(ApiBodyAssert.judgeAll(BODY, List.of(
                new ApiBodyAssert.Rule("data.nope", "notExists", ""))).get(0).ok()).isTrue();
        // 空数组 + [*] 是结构性问题：任何条件都不接受，必须 ok=false
        assertThat(ApiBodyAssert.judgeAll("{\"a\":[]}", List.of(
                new ApiBodyAssert.Rule("a[*]", "type", "string"))).get(0).ok()).isFalse();
        // 配置坏掉（整段解析不了）在逐条出口里同样是一条失败
        List<ApiBodyAssert.Rule> broken = ApiBodyAssert.parse("{不是 JSON");
        assertThat(ApiBodyAssert.judgeAll(BODY, broken)).hasSize(1);
        assertThat(ApiBodyAssert.judgeAll(BODY, broken).get(0).ok()).isFalse();
    }

    @Test
    @DisplayName("判定结果序列化成落库 JSON：字段齐全、通过的 why 是 null")
    void toJsonShape() {
        String json = ApiBodyAssert.toJson(ApiBodyAssert.judgeAll(BODY, List.of(
                new ApiBodyAssert.Rule("data.roomId", "equals", "10086"),
                new ApiBodyAssert.Rule("data.roomId", "equals", "1"))));
        assertThat(json).contains("\"path\":\"data.roomId\"");
        assertThat(json).contains("\"op\":\"equals\"");
        assertThat(json).contains("\"expect\":\"10086\"");
        assertThat(json).contains("\"ok\":true");
        assertThat(json).contains("\"why\":null");
        // 未通过那条的 why 只放说明、不带路径（路径是同一行的独立字段）
        assertThat(json).doesNotContain("data.roomId 期望");
        assertThat(json).contains("\"ok\":false");
    }

    @Test
    @DisplayName("判定结果序列化：空列表就是 []，不是 null")
    void toJsonEmpty() {
        assertThat(ApiBodyAssert.toJson(List.of())).isEqualTo("[]");
    }
}
