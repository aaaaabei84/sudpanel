package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 发送前组装（占位符替换 + 签名）的测试。
 *
 * <p>这段逻辑的特殊之处：它错了不会抛异常，只会让服务端回「认证失败」——
 * 从现象上看跟「地址写错」「请求体不对」完全分不开。所以这里覆盖的重点是
 * <b>「签名覆盖的到底是哪一份内容」</b>，而不是走一遍主流程。
 *
 * <p>已知结果用的是另一套实现（Python 的 hmac + hashlib）算出来的。拿本类的输出当期望值
 * 只能证明「它没变过」，证明不了「它算对了」。
 */
class ApiSignerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String APP_ID = "TEST_APP_ID";
    private static final String SECRET = "test-secret";
    private static final String NONCE = "TestNonce01";
    private static final String MG_ID = "MG_TEST";
    private static final long TIMES = 1700000000L;

    private static ApiSigner.Config on() {
        return new ApiSigner.Config(true, APP_ID, SECRET, NONCE, MG_ID);
    }

    /**
     * 组装一次：入参是「变量替换之后」的三处内容，出参是可发送的请求。
     *
     * <p>测试里不用真的过一遍变量替换 —— 那一步有它自己的测试。这里要钉的是签名这一段，
     * 混进来只会让失败信息更难读。
     */
    private static ApiRequest build(ApiSigner.Config cfg, String url, String headers, String body) {
        return ApiSigner.apply(cfg, new ApiRequest(url, headers, body));
    }

    // ---------- 算法本身 ----------

    @Test
    @DisplayName("签名结果与另一套实现算出的已知值一致（请求体非空）")
    void matchesKnownVector() {
        String sig = ApiSigner.sign(SECRET, APP_ID, TIMES, NONCE, "{\"a\":1}");
        assertThat(sig).isEqualTo("b923ed6c4513bdcad0e15cf1750f3d8f9313423f");
    }

    @Test
    @DisplayName("没有请求体时按空串参与运算，不能把 null 拼进去")
    void emptyBodySignsOverEmptyString() {
        // 字符串拼接遇到 null 不报错，只会把第四行变成字面量 "null" ——
        // 结果是静默算错，所以这里钉住「空请求体 = 空字符串」
        assertThat(ApiSigner.sign(SECRET, APP_ID, TIMES, NONCE, null))
                .isEqualTo("747de503a3d03acb3b84646011aa7ecb3069d3c6");
        assertThat(ApiSigner.sign(SECRET, APP_ID, TIMES, NONCE, ""))
                .isEqualTo("747de503a3d03acb3b84646011aa7ecb3069d3c6");
    }

    // ---------- 开关 ----------

    @Test
    @DisplayName("开关没打开时原样返回：手工填死值的用例行为完全不变")
    void noOpWhenDisabled() {
        ApiRequest r = build(new ApiSigner.Config(false, APP_ID, SECRET, NONCE, MG_ID),
                "http://host/{{times}}",
                "{\"A\":\"{{sign}}\"}",
                "{\"t\":\"{{times}}\"}");
        assertThat(r.url()).isEqualTo("http://host/{{times}}");
        assertThat(r.headers()).isEqualTo("{\"A\":\"{{sign}}\"}");
        assertThat(r.body()).isEqualTo("{\"t\":\"{{times}}\"}");
    }

    @Test
    @DisplayName("开了开关却缺凭证要直接报错，而不是静默发一个没签名的请求")
    void errorsWhenNotReady() {
        ApiSigner.Config cfg = new ApiSigner.Config(true, APP_ID, null, NONCE, MG_ID);
        assertThat(cfg.ready()).isFalse();
        assertThatThrownBy(() -> build(cfg, "http://host/", null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app_secret");
    }

    // ---------- 替换 ----------

    @Test
    @DisplayName("同一个占位符出现多次要全部替换")
    void replacesEveryOccurrence() {
        // 这正是不用现成脚本里那句「只换第一个」的原因：签名覆盖的内容会和实际发出的
        // 内容不一致，而两边都不会报错
        ApiRequest r = build(on(), "http://host/",
                null, "{\"a\":\"{{app_id}}\",\"b\":\"{{app_id}}\"}");
        assertThat(r.body()).isEqualTo("{\"a\":\"TEST_APP_ID\",\"b\":\"TEST_APP_ID\"}");
    }

    @Test
    @DisplayName("地址、请求头、请求体三处的占位符都会被替换")
    void resolvesAllThreePlaces() {
        ApiRequest r = build(on(),
                "http://host/push?app={{app_id}}",
                "{\"Content-Type\":\"application/json\",\"X-Nonce\":\"{{nonce}}\"}",
                "{\"m\":\"{{mg_id}}\",\"a\":\"{{app_id}}\",\"t\":\"{{times}}\"}");
        assertThat(r.url()).isEqualTo("http://host/push?app=TEST_APP_ID");
        assertThat(r.headers()).contains("TestNonce01");
        assertThat(r.body()).doesNotContain("{{");
        assertThat(r.body()).contains("\"m\":\"MG_TEST\"").contains("\"a\":\"TEST_APP_ID\"");
    }

    // ---------- 签名覆盖的是哪一份内容 ----------

    @Test
    @DisplayName("签名覆盖的是替换之后的请求体，三处时间戳必须是同一个值")
    void signatureCoversSubstitutedBody() throws Exception {
        ApiRequest r = build(on(),
                "http://host/push",
                "{\"X-Sent-At\":\"{{times}}\",\"X-Sign\":\"{{sign}}\"}",
                "{\"t\":\"{{times}}\"}");

        long times = JSON.readTree(r.body()).get("t").asLong();
        JsonNode h = JSON.readTree(r.headers());
        // 模板里的 {{times}} 落成同一秒：请求体里是它，请求头里是它，签名串第二行也是它
        assertThat(h.get("X-Sent-At").asText()).isEqualTo(String.valueOf(times));
        assertThat(authField(r.headers(), "timestamp")).isEqualTo(String.valueOf(times));

        // 用同一份「最终请求体」独立重算一遍，应当与发出去的签名一致
        String expected = hmac(APP_ID, times, NONCE, r.body());
        assertThat(authField(r.headers(), "signature")).isEqualTo(expected);
        // 请求头里的 {{sign}} 也要落成同一个签名，否则用户自己拼的头和平台算的对不上
        assertThat(h.get("X-Sign").asText()).isEqualTo(expected);
    }

    @Test
    @DisplayName("鉴权头写进请求头；已有的同名头被覆盖，大小写不同也一并清掉")
    void overwritesExistingAuthorization() throws Exception {
        ApiRequest r = build(on(), "http://host/push",
                "{\"authorization\":\"旧值\",\"AUTHORIZATION\":\"另一个旧值\","
                        + "\"Content-Type\":\"application/json\"}",
                null);
        JsonNode n = JSON.readTree(r.headers());
        assertThat(n.has("authorization")).isFalse();
        assertThat(n.has("AUTHORIZATION")).isFalse();
        assertThat(n.get("Authorization").asText()).startsWith("Sud-Auth app_id=\"TEST_APP_ID\"");
        assertThat(n.get("Authorization").asText()).contains("app_id=\"TEST_APP_ID\"");
        // 其他请求头不能被顺手弄丢
        assertThat(n.get("Content-Type").asText()).isEqualTo("application/json");
    }

    @Test
    @DisplayName("请求头留空时只补出鉴权头")
    void buildsHeadersFromScratch() throws Exception {
        ApiRequest r = build(on(), "http://host/push", "  ", null);
        JsonNode n = JSON.readTree(r.headers());
        assertThat(n.size()).isEqualTo(1);
        assertThat(n.has("Authorization")).isTrue();
    }

    @Test
    @DisplayName("请求头不是 JSON 对象时给出能照做的提示")
    void complainsAboutNonJsonHeaders() {
        assertThatThrownBy(() -> build(on(), "http://host/", "Content-Type: application/json", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 对象");
        assertThatThrownBy(() -> build(on(), "http://host/", "[1,2]", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON 对象");
    }

    // ---------- nonce ----------

    @Test
    @DisplayName("nonce 填了就用填的，留空才现生成；两次发送不会撞同一个")
    void nonceHandling() {
        assertThat(authField(build(on(), "http://host/", null, null).headers(), "nonce"))
                .isEqualTo(NONCE);

        ApiSigner.Config blank = new ApiSigner.Config(true, APP_ID, SECRET, "", MG_ID);
        String a = authField(build(blank, "http://host/", null, null).headers(), "nonce");
        String b = authField(build(blank, "http://host/", null, null).headers(), "nonce");
        assertThat(a).hasSize(16).matches("[A-Za-z0-9]+");
        assertThat(b).isNotEqualTo(a);
    }

    // ---------- 辅助 ----------

    /** 从鉴权头里取某个字段的值，例如 nonce。 */
    private static String authField(String headersJson, String field) {
        try {
            String auth = JSON.readTree(headersJson).get("Authorization").asText();
            String key = field + "=\"";
            int from = auth.indexOf(key);
            assertThat(from).as("鉴权头里应当有 %s", field).isGreaterThanOrEqualTo(0);
            int start = from + key.length();
            return auth.substring(start, auth.indexOf('"', start));
        } catch (Exception e) {
            throw new IllegalStateException("解析鉴权头失败：" + e.getMessage(), e);
        }
    }

    /** 与生产代码同算法但独立写一遍：用来验证「内容接对了」，算法本身由已知值钉住。 */
    private static String hmac(String appId, long times, String nonce, String body) throws Exception {
        String raw = appId + "\n" + times + "\n" + nonce + "\n" + body + "\n";
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
