package com.sudpanel.api;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 返回码释义。
 *
 * <p>测试时最常问的一句话是「这个接口回了个 100503，什么意思」——
 * 本类负责两件事：从响应体里把业务返回码抠出来，再查字典翻译成人话。
 *
 * <p>抠码策略：按「常见字段名优先」的顺序在 JSON 里找，找不到就递归进一层
 * （很多接口把业务码包在 {@code data} 或 {@code result} 里）。只看这几个字段名，
 * 不做猜测式取值 —— 猜错比不给更误导人；找不到就明确显示「未识别到返回码」。
 */
@Component
public class CodeDictionary {

    /**
     * 业务返回码的常见字段名，按出现频率排序。
     * 命中多个时取最靠前的，并在释义里带上字段名，方便核对取错没有。
     */
    private static final List<String> CODE_KEYS = List.of(
            "ret_code", "retCode", "sdk_code", "sdkCode",
            "errcode", "errCode", "error_code", "errorCode",
            "code", "status_code", "statusCode", "result_code", "resultCode");

    /** 容器字段：业务码经常被包在这些字段里面，值里没找到就下去一层。 */
    private static final Set<String> CONTAINER_KEYS = Set.of(
            "data", "result", "response", "body", "payload", "content");

    /** 最大下钻层数，防止异常结构导致深递归。 */
    private static final int MAX_DEPTH = 3;

    /** ObjectMapper 线程安全，做成静态复用。 */
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final ApiRepository repo;

    public CodeDictionary(ApiRepository repo) {
        this.repo = repo;
    }

    /**
     * 抠出来的返回码。
     *
     * @param raw   原始值（原样字符串，可能不是数字）
     * @param field 来自哪个字段名
     */
    public record Found(String raw, String field) {
    }

    /**
     * 从响应体里找业务返回码。
     *
     * <p>纯函数（不查字典、不碰数据库），因此做成静态的，便于单测。
     *
     * @param body 响应体原文
     * @return 找到则返回码与字段名；没找到返回 null
     */
    public static Found extract(String body) {
        if (ApiStructuredBody.overLimit(body) || body == null || body.isBlank()) {
            return null;
        }
        String t = body.trim();
        // 只处理长这样是 JSON 的响应；HTML/纯文本直接放弃，避免误抓。
        // 注意同时放行 '[' —— 有些接口直接回 [ {...} ]，先前只放行 '{' 会让下面的
        // 数组分支变成永远走不到的死代码（被单测抓出来，见 CodeDictionaryTest#extractsFromArray）。
        if (!t.startsWith("{") && !t.startsWith("[")) {
            return null;
        }
        try {
            return search(JSON.readTree(t), 0);
        } catch (Exception e) {
            return null;
        }
    }

    private static Found search(JsonNode node, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return null;
        }
        for (String key : CODE_KEYS) {
            JsonNode v = node.get(key);
            if (v != null && !v.isNull() && (v.isNumber() || v.isTextual())) {
                return new Found(v.asText(), key);
            }
        }
        for (String key : CONTAINER_KEYS) {
            JsonNode v = node.get(key);
            if (v != null && !v.isNull()) {
                Found f = search(v, depth + 1);
                if (f != null) {
                    return f;
                }
            }
        }
        // 最后兜底：数组的第一个元素（有些接口回 [ {...} ]）
        if (node.isArray() && node.size() > 0) {
            return search(node.get(0), depth + 1);
        }
        return null;
    }

    /**
     * 把码翻译成人话。
     *
     * @param raw 抠出来的原始值
     * @return 形如 {@code 100503 游戏座位有玩家未点击准备（游戏服务端错误码）}；查不到返回 null
     */
    public String describe(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        long code;
        try {
            code = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        List<ApiCode> hits = repo.findByCode(code);
        if (hits.isEmpty()) {
            return null;
        }
        ApiCode first = hits.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append(code).append(' ').append(first.message());
        sb.append('（').append(first.scopeLabel()).append('）');
        // 同一码在多个分类下都有（例如 0=成功 在客户端/SDK/游戏服务端各一份），不重复刷屏
        if (hits.size() > 1) {
            sb.append("｜该码在其他分类下也有定义，共 ").append(hits.size()).append(" 条");
        }
        return sb.toString();
    }

    /**
     * 判断实际值是否命中期望值列表（逗号分隔，命中任意一个即算通过）。
     *
     * <p>按字符串比较并去掉首尾空白：HTTP 状态码与业务码都可能被写成
     * {@code "0"}（带引号）或 {@code 0}，统一成字符串后比较最省事，
     * 也不会出现 {@code "0" != "0 "} 这种坑。
     *
     * @param expectCsv 期望值，逗号分隔；空表示不校验
     * @param actual    实际值（可能为 null）
     * @return 未设期望、或命中任一期望 → true
     */
    public static boolean matches(String expectCsv, String actual) {
        if (expectCsv == null || expectCsv.isBlank()) {
            return true;
        }
        if (actual == null) {
            return false;
        }
        String a = actual.trim();
        for (String s : expectCsv.split("[,，]")) {
            if (s.trim().equals(a)) {
                return true;
            }
        }
        return false;
    }
}
