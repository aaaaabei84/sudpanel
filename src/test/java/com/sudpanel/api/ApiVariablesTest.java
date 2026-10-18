package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 自定义变量替换的测试。
 *
 * <p>这里要钉住的核心只有一件事：<b>「哪一组只在哪个框里生效」</b>。
 * 两组变量同名时不串、未定义的名字原样留下、替换是全部替换 —— 这三条任何一条破了，
 * 表现都是「参数莫名变成另一个值」或者「占位符原样发出去」，而接口照样返回 200。
 */
class ApiVariablesTest {

    private static ApiVariables.VarSet vars(List<ApiVariables.Var> header,
                                           List<ApiVariables.Var> body) {
        return new ApiVariables.VarSet(header, body);
    }

    private static ApiVariables.Var v(String name, String value) {
        return new ApiVariables.Var(name, value);
    }

    // ---------- 分作用域 ----------

    @Test
    @DisplayName("两组互不干涉：请求头里用不到请求体那组的名字")
    void scopesDoNotLeak() {
        ApiVariables.VarSet vs = vars(
                List.of(v("env", "fat")),
                List.of(v("roomId", "9001")));

        ApiRequest r = ApiVariables.apply(vs,
                "http://host/{{env}}/{{roomId}}",
                "{\"X-Env\":\"{{env}}\",\"X-Room\":\"{{roomId}}\"}",
                "{\"room\":\"{{roomId}}\",\"env\":\"{{env}}\"}");

        // 地址与请求头只用 header 那组：roomId 在这两处没有定义，应当原样留着
        assertThat(r.url()).isEqualTo("http://host/fat/{{roomId}}");
        assertThat(r.headers()).isEqualTo("{\"X-Env\":\"fat\",\"X-Room\":\"{{roomId}}\"}");
        // 请求体只用 body 那组：env 在这里没有定义
        assertThat(r.body()).isEqualTo("{\"room\":\"9001\",\"env\":\"{{env}}\"}");
    }

    @Test
    @DisplayName("同名也不会串：两边各叫 id，各拿各的值")
    void sameNameDifferentScopeKeepsOwnValue() {
        ApiVariables.VarSet vs = vars(
                List.of(v("id", "HEADER-ID")),
                List.of(v("id", "BODY-ID")));

        ApiRequest r = ApiVariables.apply(vs, "http://host/", "{\"X-Id\":\"{{id}}\"}", "{\"id\":\"{{id}}\"}");

        assertThat(r.headers()).isEqualTo("{\"X-Id\":\"HEADER-ID\"}");
        assertThat(r.body()).isEqualTo("{\"id\":\"BODY-ID\"}");
    }

    // ---------- 替换行为 ----------

    @Test
    @DisplayName("同一个占位符出现多次要全部替换")
    void replacesEveryOccurrence() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("u", "abc")));
        ApiRequest r = ApiVariables.apply(vs, "http://host/", null, "{\"a\":\"{{u}}\",\"b\":\"{{u}}\"}");
        assertThat(r.body()).isEqualTo("{\"a\":\"abc\",\"b\":\"abc\"}");
    }

    @Test
    @DisplayName("没定义的名字原样留下 —— 平台自己那套占位符就是靠这一点活下来的")
    void leavesUnknownPlaceholdersAlone() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("mg_id", "MG1")));
        ApiRequest r = ApiVariables.apply(vs, "http://host/", null,
                "{\"m\":\"{{mg_id}}\",\"t\":\"{{times}}\",\"s\":\"{{sign}}\"}");
        assertThat(r.body()).isEqualTo("{\"m\":\"MG1\",\"t\":\"{{times}}\",\"s\":\"{{sign}}\"}");
    }

    @Test
    @DisplayName("只认 {{名称}}；单花括号、少一个括号都不动")
    void onlyDoubleBracesMatch() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("u", "abc")));
        ApiRequest r = ApiVariables.apply(vs, "http://host/", null,
                "{\"a\":\"{u}\",\"b\":\"u\",\"c\":\"{{u}\",\"d\":\"{{ u }}\"}");
        assertThat(r.body()).isEqualTo("{\"a\":\"{u}\",\"b\":\"u\",\"c\":\"{{u}\",\"d\":\"abc\"}");
    }

    @Test
    @DisplayName("名称两侧的空白归平台：{{ u }} 与 {{u}} 等价")
    void toleratesSpacesAroundName() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("u", "abc")));
        ApiRequest r = ApiVariables.apply(vs, "http://host/", null, "{{   u   }}");
        assertThat(r.body()).isEqualTo("abc");
    }

    @Test
    @DisplayName("值里再带占位符是允许的：替换结果会留给下一段继续处理")
    void valueMayContainPlaceholder() {
        // 典型用法：{{baseUrl}} 指向 {{host}}，host 换环境时只改一处
        ApiVariables.VarSet vs = vars(List.of(v("baseUrl", "http://{{host}}:8080"), v("host", "10.0.0.1")), List.of());
        ApiRequest r = ApiVariables.apply(vs, "{{baseUrl}}/v1/x", null, null);
        // 一次扫描只替一遍：baseUrl 被换进来之后，里面的 {{host}} 这轮不会再被展开。
        // 这是刻意的 —— 想换两遍，值里就该直接写最终结果
        assertThat(r.url()).isEqualTo("http://{{host}}:8080/v1/x");
    }

    @Test
    @DisplayName("值里出现 $ 和 \\ 时不能被当成正则的替换语法")
    void valueIsTreatedLiterally() {
        // appendReplacement 会把 $1 当成组引用、把 \ 当转义 —— 必须 quoteReplacement，
        // 否则密码里带一个 $ 就会被悄悄改写，而且不报错
        ApiVariables.VarSet vs = vars(List.of(v("p", "a$1b\\c")), List.of());
        ApiRequest r = ApiVariables.apply(vs, "http://host/?p={{p}}", null, null);
        assertThat(r.url()).isEqualTo("http://host/?p=a$1b\\c");
    }

    @Test
    @DisplayName("值是空串时替换成空，而不是把占位符留下")
    void emptyValueReplacesWithEmpty() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("debug", "")));
        ApiRequest r = ApiVariables.apply(vs, "http://host/", null, "{\"d\":\"{{debug}}\"}");
        assertThat(r.body()).isEqualTo("{\"d\":\"\"}");
    }

    // ---------- 边界 ----------

    @Test
    @DisplayName("没有变量时完全不动原文（含 null）")
    void emptyVarSetIsNoOp() {
        ApiRequest r = ApiVariables.apply(ApiVariables.VarSet.empty(),
                "http://host/{{x}}", "{{y}}", "{{z}}");
        assertThat(r.url()).isEqualTo("http://host/{{x}}");
        assertThat(r.headers()).isEqualTo("{{y}}");
        assertThat(r.body()).isEqualTo("{{z}}");

        ApiRequest n = ApiVariables.apply(null, "u", null, null);
        assertThat(n.url()).isEqualTo("u");
        assertThat(n.headers()).isNull();
        assertThat(n.body()).isNull();
    }

    @Test
    @DisplayName("名字为空的行被跳过，不会变成匹配一切的通配")
    void blankNamesAreIgnored() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("  ", "CHANGED"), v("u", "ok")));
        ApiRequest r = ApiVariables.apply(vs, "http://host/", null, "{{u}}/{{v}}");
        assertThat(r.body()).isEqualTo("ok/{{v}}");
    }

    @Test
    @DisplayName("同名以最后一条为准 —— 结果确定、可解释，不看数据库返回顺序")
    void lastDuplicateWins() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("u", "first"), v("u", "second")));
        ApiRequest r = ApiVariables.apply(vs, "http://host/", null, "{{u}}");
        assertThat(r.body()).isEqualTo("second");
    }

    // ---------- 与签名连起来的顺序 ----------

    @Test
    @DisplayName("先换变量、再算签名：签名覆盖的是换完之后的那一份")
    void variablesResolveBeforeSigning() {
        String appId = "APP";
        String secret = "secret";
        String nonce = "NONCE01";
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("roomId", "9001")));

        // 变量替换后的请求体
        ApiRequest sub = ApiVariables.apply(vs, "http://host/push", null, "{\"room\":\"{{roomId}}\"}");
        assertThat(sub.body()).isEqualTo("{\"room\":\"9001\"}");

        // 签名基于这一份算
        ApiSigner.Config cfg = new ApiSigner.Config(true, appId, secret, nonce, null);
        ApiRequest signed = ApiSigner.apply(cfg, sub);
        long times = Long.parseLong(field(signed.headers(), "timestamp"));
        String expected = sign(secret, appId, times, nonce, sub.body());
        assertThat(field(signed.headers(), "signature")).isEqualTo(expected);

        // 反证：如果拿「没换过变量的原文」去签，得到的是另一个值 —— 也就是线上那种
        // 「内容明明对、就是认证失败」的成因
        assertThat(sign(secret, appId, times, nonce, "{\"room\":\"{{roomId}}\"}")).isNotEqualTo(expected);
    }

    // ---------- 基址作用域（2026-10-04 新增，需求 #38） ----------

    private static ApiVariables.VarSet vars3(List<ApiVariables.Var> base,
                                             List<ApiVariables.Var> header,
                                             List<ApiVariables.Var> body) {
        return new ApiVariables.VarSet(base, header, body);
    }

    @Test
    @DisplayName("基址只作用于地址：同名变量不会渗进请求头")
    void baseOnlyAppliesToUrl() {
        ApiVariables.VarSet vs = vars3(
                List.of(v("base", "http://fat.example")),
                List.of(v("env", "fat")),
                List.of());

        ApiRequest r = ApiVariables.apply(vs,
                "{{base}}/api/order",
                "{\"X-Base\":\"{{base}}\",\"X-Env\":\"{{env}}\"}",
                null);

        assertThat(r.url()).isEqualTo("http://fat.example/api/order");
        // 基址进不了请求头 —— 这正是它与请求头变量分组的价值
        assertThat(r.headers()).isEqualTo("{\"X-Base\":\"{{base}}\",\"X-Env\":\"fat\"}");
    }

    @Test
    @DisplayName("地址里同名时基址赢：换环境只改基址那一行")
    void baseWinsOverHeaderInUrl() {
        ApiVariables.VarSet vs = vars3(
                List.of(v("base", "http://uat.example")),
                List.of(v("base", "http://fat.example"), v("env", "fat")),
                List.of());

        ApiRequest r = ApiVariables.apply(vs,
                "{{base}}/api/order",
                "{\"X-Base\":\"{{base}}\"}",
                null);

        // 地址用基址的值；请求头里有自己的定义，仍按请求头那组算，不被基址改写
        assertThat(r.url()).isEqualTo("http://uat.example/api/order");
        assertThat(r.headers()).isEqualTo("{\"X-Base\":\"http://fat.example\"}");
    }

    @Test
    @DisplayName("地址仍然认请求头那一组：老配置不因新增基址而失效")
    void urlStillSeesHeaderVars() {
        ApiVariables.VarSet vs = vars3(
                List.of(),
                List.of(v("baseUrl", "http://host:8080")),
                List.of());

        ApiRequest r = ApiVariables.apply(vs, "{{baseUrl}}/v1/x", null, null);

        assertThat(r.url()).isEqualTo("http://host:8080/v1/x");
    }

    /**
     * 从鉴权头里取某个字段。
     *
     * <p>必须先把请求头当 JSON 解析再取 Authorization —— 直接对原文找 {@code timestamp="}
     * 是找不到的：在 JSON 文本里那一对引号是转义形式 {@code \"}，中间隔着一个反斜杠。
     */
    private static String field(String headersJson, String name) {
        try {
            String auth = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(headersJson).get("Authorization").asText();
            String key = name + "=\"";
            int from = auth.indexOf(key);
            assertThat(from).as("鉴权头里应当有 %s", name).isGreaterThanOrEqualTo(0);
            int start = from + key.length();
            return auth.substring(start, auth.indexOf('"', start));
        } catch (Exception e) {
            throw new IllegalStateException("解析鉴权头失败：" + e.getMessage(), e);
        }
    }

    /** 与生产代码同算法但独立写一遍，用来确认「内容接对了」。 */
    private static String sign(String secret, String appId, long times, String nonce, String body)
            throws IllegalStateException {
        try {
            String raw = appId + "\n" + times + "\n" + nonce + "\n" + body + "\n";
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA1"));
            return java.util.HexFormat.of().formatHex(
                    mac.doFinal(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- 按游戏覆盖请求体变量（需求 #68） ----------

    /*
     * 性能测试的人机对局模式要为每个游戏用不同的 mg_id / app_id 推指令，
     * 而变量表里只有一份全局值。这一组要钉住的就是「覆盖只发生在请求体那一组、
     * 且不动原来的变量表」—— 破了的表现是「压测顺手把接口测试的配置改了」，
     * 而下次手工调接口用错游戏的身份时，没人会想到是压测干的。
     */

    @Test
    @DisplayName("★ 覆盖请求体里的同名变量，其余变量原封不动")
    void overrideReplacesExisting() {
        ApiVariables.VarSet vs = vars(
                List.of(v("url_fat", "http://host")),
                List.of(v("mg_id", "111"), v("app_id", "222"), v("room_id", "3333")));

        ApiVariables.VarSet out = ApiVariables.overrideBody(vs,
                java.util.Map.of("mg_id", "9999", "app_id", "8888"));

        // 直接替换到请求体上验一遍：这里比对的是「真的换进去了」
        ApiRequest r = ApiVariables.apply(out, "{{url_fat}}/push",
                "{}", "{\"mg_id\":\"{{mg_id}}\",\"app_id\":\"{{app_id}}\",\"room\":\"{{room_id}}\"}");
        assertThat(r.url()).isEqualTo("http://host/push");
        assertThat(r.body()).contains("\"mg_id\":\"9999\"").contains("\"app_id\":\"8888\"");
        // 没被覆盖的那个仍然是原值
        assertThat(r.body()).contains("\"room\":\"3333\"");
    }

    @Test
    @DisplayName("★ 覆盖不改动原变量表（压测不能顺手改掉接口测试的配置）")
    void overrideDoesNotMutateOriginal() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("mg_id", "111")));

        ApiVariables.overrideBody(vs, java.util.Map.of("mg_id", "9999"));

        // 原对象仍然是 111；调用方传进来的那份不能被写坏
        assertThat(ApiVariables.apply(vs, "http://h", "{}", "{\"m\":\"{{mg_id}}\"}").body())
                .contains("\"m\":\"111\"");
    }

    @Test
    @DisplayName("★ 值为 null = 不覆盖（保留表里的值），不是「抹成空」")
    void nullValueMeansKeep() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("mg_id", "111")));
        java.util.Map<String, String> ov = new java.util.LinkedHashMap<>();
        ov.put("mg_id", null);

        ApiVariables.VarSet out = ApiVariables.overrideBody(vs, ov);

        // null 表示「这个游戏没填」，用回全局值；若被当成空串，会发出一个空 mg_id 而服务端只回「参数错误」
        assertThat(ApiVariables.apply(out, "http://h", "{}", "{\"m\":\"{{mg_id}}\"}").body())
                .contains("\"m\":\"111\"");
    }

    @Test
    @DisplayName("空串 = 就是要它空着（与 null 是两回事）")
    void emptyStringMeansBlank() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("mg_id", "111")));

        ApiVariables.VarSet out = ApiVariables.overrideBody(vs, java.util.Map.of("mg_id", ""));

        assertThat(ApiVariables.apply(out, "http://h", "{}", "{\"m\":\"{{mg_id}}\"}").body())
                .contains("\"m\":\"\"");
    }

    @Test
    @DisplayName("覆盖里多出来的名字会追加进请求体那一组（变量表还没加过也能用）")
    void overrideAppendsUnknown() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("mg_id", "111")));

        ApiVariables.VarSet out = ApiVariables.overrideBody(vs, java.util.Map.of("game_key", "abc"));

        assertThat(ApiVariables.apply(out, "http://h", "{}", "{\"k\":\"{{game_key}}\"}").body())
                .contains("\"k\":\"abc\"");
    }

    @Test
    @DisplayName("基址与请求头两组一个字节都不动")
    void overrideTouchesBodyOnly() {
        ApiVariables.VarSet vs = vars(
                List.of(v("url_fat", "http://header-host")),
                List.of(v("mg_id", "111")));
        // 覆盖里放一个与基址同名的名字：它只该作用于请求体那组，地址仍用基址组的值
        ApiVariables.VarSet out = ApiVariables.overrideBody(vs, java.util.Map.of("url_fat", "http://evil"));

        ApiRequest r = ApiVariables.apply(out, "{{url_fat}}/x", "{}", "{}");
        assertThat(r.url()).isEqualTo("http://header-host/x");
    }

    @Test
    @DisplayName("空覆盖表 / null 覆盖表：原样返回（等同不覆盖）")
    void emptyOverrideIsNoop() {
        ApiVariables.VarSet vs = vars(List.of(), List.of(v("mg_id", "111")));

        assertThat(ApiVariables.overrideBody(vs, java.util.Map.of())).isSameAs(vs);
        assertThat(ApiVariables.overrideBody(vs, null)).isSameAs(vs);
    }

    @Test
    @DisplayName("原变量为 null 也能覆盖（新建一个只有覆盖项的三组表）")
    void overrideOnNullVars() {
        ApiVariables.VarSet out = ApiVariables.overrideBody(null, java.util.Map.of("mg_id", "777"));

        assertThat(ApiVariables.apply(out, "http://h", "{}", "{\"m\":\"{{mg_id}}\"}").body())
                .contains("\"m\":\"777\"");
    }
}
