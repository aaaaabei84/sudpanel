package com.sudpanel.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** 历史报文只生成新草稿，不自动发送；旧凭据、自动头与截断正文不可直接复用。 */
public final class ApiHistoryDraft {
    private ApiHistoryDraft() {}
    public record Draft(ApiSpec spec, Long envId, List<String> warnings) {}
    private static final Set<String> REMOVE = Set.of("authorization", "proxy-authorization", "cookie", "host", "content-length", "connection",
            "transfer-encoding", "upgrade", "expect", "te", "trailer", "sign", "times", "nonce", "app_id", "mg_id", "x-signature", "x-sign", "x-timestamp");
    public static Draft restore(ApiExec execution, ApiRepository repo) {
        JsonNode metadata = ApiHistoryCompare.parse(execution.metadata());
        if(metadata!=null&&Set.of("MULTIPART","BINARY").contains(metadata.path("requestBodyMode").asText()))throw new IllegalArgumentException("历史文件正文只保留元数据，不能还原为文本草稿；请载入原用例并核对本机文件引用");
        if (ApiHistoryCompare.truncated(execution.reqBody()) || metadata != null && metadata.path("requestBodyTruncated").asBoolean(false))
            throw new IllegalArgumentException("历史请求正文已截断，不能载入为完整草稿；请载入原用例");
        if (execution.url() == null || execution.url().isBlank() || execution.method() == null || execution.method().isBlank())
            throw new IllegalArgumentException("历史记录没有可恢复的方法或地址");
        var warnings = new ArrayList<String>();
        if (metadata == null || !metadata.path("requestBodyTruncated").isBoolean()) warnings.add("旧记录未保存请求正文完整性标记，请核对正文是否完整");
        var headers = new LinkedHashMap<>(ApiHistoryCompare.headers(execution.reqHeaders()));
        headers.keySet().removeIf(REMOVE::contains);
        String authType = metadata == null ? "" : metadata.path("authType").asText("");
        if (authType.equals("API_KEY")) {
            headers.keySet().removeIf(k -> !Set.of("content-type", "accept", "accept-language", "user-agent").contains(k));
            warnings.add("原请求使用 API Key，未知键名的应用头已移除；查询参数仍需手动核对并重新配置鉴权");
        }
        if (execution.reqHeaders() == null) warnings.add("旧记录没有请求头快照，请补充内容类型和必需头");
        warnings.add("已移除旧 Authorization/Cookie/签名及不可复用自动头；请重新选择鉴权。正文与URL仍可能含旧时间、对局ID或自定义凭据");
        warnings.add("历史未保存原超时、证书及代理设置，本草稿使用10秒超时、证书校验、直连且不跟随重定向");
        Long envId = null;
        if (metadata != null && metadata.path("envId").isIntegralNumber() && metadata.path("envId").canConvertToLong() && metadata.path("envId").asLong() > 0) {
            long id = metadata.path("envId").asLong();
            try { repo.getEnv(id); envId = id; } catch (IllegalStateException e) { warnings.add("原执行环境已删除，请明确选择当前环境"); }
        } else warnings.add("历史没有可靠环境来源，请明确选择本次环境");
        String body = execution.reqBody();
        var options = new ApiRequestOptions(body == null || body.isEmpty() ? "NONE" : "RAW", List.of(),
                new ApiRequestOptions.Auth("NONE", null, null, null, null, null, null), List.of(), List.of(),
                new ApiRequestOptions.Transport("NEVER", "DIRECT", null, null, "OFF", null), true);
        var spec = new ApiSpec(null, (execution.caseName() == null ? "历史请求" : execution.caseName()) + " · 历史 #" + execution.id(),
                execution.method(), execution.url(), ApiRequestEngine.stringify(headers), body,
                "从执行记录 #" + execution.id() + " 载入；这是实际报文副本，鉴权需重新配置。", "", "", 10000, false, true, null, null, options);
        return new Draft(spec, envId, List.copyOf(warnings));
    }
}
