package com.sudpanel.api;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 签名类接口的「发送前组装」。
 *
 * <p>为什么必须放在平台里做，而不是填死值：这类接口的鉴权串里通常含一个**取当前时间**的字段，
 * 签名又是拿它跟请求体一起算出来的。手工从别处抄一份，抄完那一刻它就开始过期；而且签名覆盖请求体，
 * 只要请求体差一个空格、一个换行，或者签名和请求体不是同一次生成的，服务端一律回「认证失败」——
 * 表现成「地址方法都对、HTTP 200，就是业务码不对」，从现象上几乎看不出线索。
 *
 * <p>这里实现的就是「每次发送现算一次」：
 * <ol>
 *   <li>取当前秒作为时间戳；</li>
 *   <li>把请求体里的占位符替换成真实值（<b>替换后的这一版才拿去签名，也才是真正发出去的那一版</b>）；</li>
 *   <li>按 {@code app_id \n 时间戳 \n nonce \n 请求体 \n} 四行算 HmacSHA1，输出十六进制小写；</li>
 *   <li>把鉴权头写进请求头（已有的同名头会被覆盖，不论大小写）。</li>
 * </ol>
 *
 * <p><b>本类只做上面四步，自定义变量不在这里替换。</b>变量替换（{@link ApiVariables}）必须排在
 * 本类之前：它改的正是要参与签名的那份请求体，顺序反了，签的和服务端收到的是两份内容。
 * 调用点只有一处（{@link ApiRunner#execute}），顺序在那个方法里能一眼看全。
 *
 * <p><b>替换用「全部替换」而不是「只换第一个」。</b>同一个占位符在请求体里出现两次时，
 * 「只换第一个」会让签名的内容和实际发出的内容不一致 —— 这种不一致不会有任何报错，
 * 只会表现为认证失败，极难排查。
 *
 * <p>保密约定：密钥只在本机库里，只参与运算，**不写日志、不回传界面**。
 */
public final class ApiSigner {

    /**
     * 签名设置。这是「接口所属环境」的属性（一套地址、一个应用），不是某一条用例的属性。
     *
     * @param enabled   是否在发送前自动签名
     * @param appId     应用 id
     * @param appSecret 应用密钥，仅用于算签名
     * @param nonce     随机串；留空则每次发送现生成一个
     * @param mgId      请求体模板里另一个占位符对应的值
     */
    public record Config(boolean enabled, String appId, String appSecret, String nonce, String mgId) {

        /** 开着开关但凭证没填齐时，宁可报错也不静默发一个没签名的请求。 */
        public boolean ready() {
            return enabled && notBlank(appId) && notBlank(appSecret);
        }
    }

    /** 可用占位符。界面上的说明文字与本处保持一致。 */
    public static final String P_APP_ID = "{{app_id}}";
    public static final String P_MG_ID = "{{mg_id}}";
    public static final String P_TIMES = "{{times}}";
    public static final String P_NONCE = "{{nonce}}";
    public static final String P_SIGN = "{{sign}}";

    /** 鉴权头的方案前缀。目前只有这一种；将来若出现别的方案，从这里扩展。 */
    private static final String SCHEME = "Sud-Auth";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] NONCE_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private ApiSigner() {
    }

    /**
     * 按设置把「带占位符的模板」变成「可直接发送的请求」。
     * 开关没打开时原样返回 —— 手工填死值的用例行为完全不变。
     *
     * <p>入参要求是<b>已经做过变量替换</b>的那一份（见 {@link ApiVariables#apply}）：
     * 签名覆盖的必须是最终发出去的内容，早一步晚一步结果都不一样。
     */
    public static ApiRequest apply(Config cfg, ApiRequest in) {
        if (cfg == null || !cfg.enabled()) {
            return in;
        }
        if (!cfg.ready()) {
            throw new IllegalStateException(
                    "已开启自动签名，但「签名设置」里还没填 app_id 或 app_secret，无法生成鉴权串");
        }
        return apply(cfg, in, Instant.now().getEpochSecond(),
                notBlank(cfg.nonce()) ? cfg.nonce().trim() : randomNonce(16));
    }

    /** 与系统时间变量共用时间和 nonce，保证正文与鉴权头使用同一组值。 */
    public static ApiRequest apply(Config cfg, ApiRequest in, long times, String nonce) {
        return apply(cfg, in, times, nonce, java.util.function.UnaryOperator.identity());
    }

    static ApiRequest apply(Config cfg, ApiRequest in, long times, String nonce,
                            java.util.function.UnaryOperator<String> restoreLiteralData) {
        if (cfg == null || !cfg.enabled()) return in;
        if (!cfg.ready()) throw new IllegalStateException("已开启自动签名，但 app_id 或 app_secret 未填齐");
        // 顺序要紧：请求体先替换完成，再拿它算签名 —— 签名覆盖的就是最终这份请求体
        String bodyOut = restoreLiteralData.apply(sub(in.body(), cfg, times, nonce, null));
        String sign = sign(cfg.appSecret(), cfg.appId(), times, nonce, bodyOut);
        String urlOut = restoreLiteralData.apply(sub(in.url(), cfg, times, nonce, sign));
        String headersOut = restoreLiteralData.apply(sub(in.headers(), cfg, times, nonce, sign));
        headersOut = withAuthorization(headersOut, cfg.appId(), times, nonce, sign);
        return new ApiRequest(urlOut, headersOut, bodyOut);
    }

    /** 替换占位符。{@code sign} 为 null 时不替换 {@code {{sign}}}（算签名时还没这个值）。 */
    static String sub(String text, Config cfg, long times, String nonce, String sign) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String t = text
                .replace(P_TIMES, String.valueOf(times))
                .replace(P_APP_ID, nullToEmpty(cfg.appId()))
                .replace(P_MG_ID, nullToEmpty(cfg.mgId()))
                .replace(P_NONCE, nonce);
        if (sign != null) {
            t = t.replace(P_SIGN, sign);
        }
        return t;
    }

    /**
     * 签名串是四行，最后一行也要带换行：
     * <pre>
     * app_id
     * 时间戳（秒）
     * nonce
     * 请求体
     * </pre>
     */
    static String sign(String secret, String appId, long times, String nonce, String body) {
        // 没有请求体时按空串参与运算。不能直接拼 null —— 那样第四行会变成字面量 "null"，
        // 字符串拼接过 null 不报错、只是悄悄拼错，而结果表现为「认证失败」，最难查。
        String raw = appId + "\n" + times + "\n" + nonce + "\n" + (body == null ? "" : body) + "\n";
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("算签名失败：" + e.getMessage());
        }
    }

    /** 鉴权头的取值形态。 */
    static ApiRequest applyBytes(Config cfg,ApiRequest in,long times,String nonce,java.util.function.UnaryOperator<String> restore,byte[] bytes) {
        if(!cfg.ready())throw new IllegalArgumentException("业务签名缺少appId或appSecret");
        String sign=signBytes(cfg.appSecret(),cfg.appId(),times,nonce,bytes);
        return new ApiRequest(restore.apply(sub(in.url(),cfg,times,nonce,sign)),
                withAuthorization(restore.apply(sub(in.headers(),cfg,times,nonce,sign)),cfg.appId(),times,nonce,sign),null);
    }
    /** multipart/binary签名直接使用最终发布字节，不经过字符解码。 */
    static String signBytes(String secret,String appId,long times,String nonce,byte[] body) {
        try {
            var mac=javax.crypto.Mac.getInstance("HmacSHA1");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA1"));
            mac.update((appId+"\n"+times+"\n"+nonce+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if(body!=null)mac.update(body);mac.update((byte)'\n');
            return java.util.HexFormat.of().formatHex(mac.doFinal());
        } catch(Exception e) {throw new IllegalStateException("请求字节签名失败",e);}
    }

    public static String authorizationValue(String appId, long times, String nonce, String sign) {
        return SCHEME + " app_id=\"" + appId + "\",timestamp=\"" + times
                + "\",nonce=\"" + nonce + "\",signature=\"" + sign + "\"";
    }

    /**
     * 把鉴权头合并进请求头 JSON。
     *
     * <p>这里解析一次、改一处、再序列化，而不是拿字符串拼 —— 凭证里出现引号或反斜杠时，
     * 拼字符串会得到一个格式已坏的请求头，而报错会发生在很远的地方。
     */
    private static String withAuthorization(String headers, String appId, long times,
                                            String nonce, String sign) {
        String t = headers == null ? "" : headers.trim();
        ObjectNode node;
        if (t.isEmpty()) {
            node = JSON.createObjectNode();
        } else {
            if (!t.startsWith("{")) {
                throw new IllegalArgumentException(
                        "请求头要写成 JSON 对象，例如 {\"Content-Type\":\"application/json\"}");
            }
            try {
                JsonNode parsed = JSON.readTree(t);
                if (!parsed.isObject()) {
                    throw new IllegalArgumentException("请求头要写成 JSON 对象");
                }
                node = (ObjectNode) parsed;
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("请求头 JSON 格式不对：" + e.getMessage());
            }
        }
        // 先收再删：大小写不同的同名头可能同时存在，只删一个会留下两个 Authorization
        List<String> dup = new ArrayList<>();
        node.fieldNames().forEachRemaining(n -> {
            if (n.equalsIgnoreCase("Authorization")) {
                dup.add(n);
            }
        });
        dup.forEach(node::remove);
        node.put("Authorization", authorizationValue(appId, times, nonce, sign));
        return node.toString();
    }

    /** 留空时生成一个随机串；大小写混合，照抄时不能改大小写。 */
    static String randomNonce(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(NONCE_ALPHABET[RANDOM.nextInt(NONCE_ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
