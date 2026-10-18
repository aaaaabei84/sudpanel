package com.sudpanel.api;

/**
 * 一条接口用例（也是「执行一次请求」的入参）。
 *
 * <p>字段全部由使用者在界面上填写，平台侧不预置任何地址、请求头或凭证 ——
 * 被测环境信息属于内部资料，不落代码、不进 git。
 *
 * @param id           用例 id（未入库时为 null）
 * @param name         用例名称（列表里显示的名字）
 * @param method       HTTP 方法，GET/POST/PUT/PATCH/DELETE
 * @param url          完整请求地址
 * @param headers      请求头，JSON 对象文本，例如 {"Content-Type":"application/json"}
 * @param body         请求体原文（GET 可不填）
 * @param note         备注（测什么、前置条件等）
 * @param expectStatus 期望的 HTTP 状态码，逗号分隔可写多个；留空表示不校验
 * @param expectCode   期望的业务返回码，逗号分隔可写多个；留空表示不校验
 * @param timeoutMs    超时毫秒，<=0 时用默认值
 * @param insecure     是否跳过 HTTPS 证书校验（内部自签证书的环境需要）
 * @param enabled      是否启用；批量执行时跳过未启用的用例
 * @param groupId      所属分组 id；null 表示未分组（2026-10-04 新增，需求 #48）
 * @param asserts      响应体断言，规则数组的 JSON 文本（2026-10-05 新增，需求 #51）；
 *                     null/空表示不校验响应体。格式见 {@link ApiBodyAssert}
 */
public record ApiSpec(
        Long id,
        String name,
        String method,
        String url,
        String headers,
        String body,
        String note,
        String expectStatus,
        String expectCode,
        Integer timeoutMs,
        boolean insecure,
        boolean enabled,
        Long groupId,
        String asserts,
        ApiRequestOptions options
) {
    public ApiSpec(Long id, String name, String method, String url, String headers, String body,
                   String note, String expectStatus, String expectCode, Integer timeoutMs,
                   boolean insecure, boolean enabled, Long groupId, String asserts) {
        this(id, name, method, url, headers, body, note, expectStatus, expectCode, timeoutMs,
                insecure, enabled, groupId, asserts, null);
    }

    /** 默认超时：10 秒。太短会把慢接口误判成失败，太长会让批量跑不起来。 */
    public static final int DEFAULT_TIMEOUT_MS = 10_000;

    /** 生效的超时值。 */
    public int timeoutOrDefault() {
        return timeoutMs == null || timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : timeoutMs;
    }

    /** 用例名兜底，避免列表里出现空名字。 */
    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return method + " " + url;
    }

    /** 是否设置了业务返回码断言。 */
    public boolean expectCodeIsBlank() {
        return expectCode == null || expectCode.isBlank();
    }
}
