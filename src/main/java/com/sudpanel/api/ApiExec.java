package com.sudpanel.api;

import java.util.Set;

/**
 * 一次接口调用的执行结果（也直接作为返回给前端的结构）。
 *
 * @param id            自增 id
 * @param batchId       所属批次 id；单条试跑时为 null
 * @param caseId        来源用例 id；临时试跑时为 null
 * @param caseName      用例名快照（用例被改名或删除后，历史记录仍可读）
 * @param roundNo       第几轮（定时重复时从 1 开始）
 * @param seq           本轮内的第几条
 * @param method        实际发出的方法
 * @param url           实际请求的地址
 * @param httpStatus    HTTP 状态码；未收到响应时为 null
 * @param respCode      从响应体里解析出的业务返回码原文；没解析到为 null
 * @param codeMeaning   业务返回码的释义（查字典得到）
 * @param verdict       判定：PASS / FAIL / ERROR
 * @param reason        判定原因（失败或报错时说明为什么）
 * @param elapsedMs     耗时毫秒
 * @param respBody      响应体（超长会截断）
 * @param respHeaders   响应头（文本）
 * @param reqHeaders    **本次实际发出的请求头**（文本快照，含自动补的 Content-Type）
 * @param reqBody       本次实际发出的请求体（与发出的字节一致）
 * @param error         异常信息；正常时为 null
 * @param ts            执行时间
 * @param assertsResult 本次执行的**响应体断言逐条判定结果**（JSON 文本，2026-10-05 需求 #58）。
 *                      内容为 {@code [{path,op,expect,ok,why}]}，通过的也在里面 ——
 *                      只有这样界面上才分得清「断言都过了」和「压根没配断言」。
 *                      为 null 表示**没有这一项**：本版本之前的历史记录，或请求根本没发出
 *                      （没拿到响应体就无从判定）。空数组 {@code []} 是另一种语义：
 *                      这次执行确实做了判定，只是该用例一条断言都没配。
 */
public record ApiExec(
        Long id,
        Long batchId,
        Long caseId,
        String caseName,
        Integer roundNo,
        Integer seq,
        String method,
        String url,
        Integer httpStatus,
        String respCode,
        String codeMeaning,
        String verdict,
        String reason,
        Long elapsedMs,
        String respBody,
        String respHeaders,
        String reqHeaders,
        String reqBody,
        String error,
        String ts,
        String assertsResult,
        String metadata
) {
    public ApiExec(Long id, Long batchId, Long caseId, String caseName, Integer roundNo, Integer seq,
                   String method, String url, Integer httpStatus, String respCode, String codeMeaning,
                   String verdict, String reason, Long elapsedMs, String respBody, String respHeaders,
                   String reqHeaders, String reqBody, String error, String ts, String assertsResult) {
        this(id, batchId, caseId, caseName, roundNo, seq, method, url, httpStatus, respCode,
                codeMeaning, verdict, reason, elapsedMs, respBody, respHeaders, reqHeaders, reqBody,
                error, ts, assertsResult, null);
    }

    public static final String SKIP = "SKIP";
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    /** 请求根本没发出去 / 超时 / 解析异常 —— 与「断言没过」区分开。 */
    public static final String ERROR = "ERROR";

    /**
     * 网络 / 环境类异常的类名白名单（需求 #73）。
     *
     * <p>判据取自 {@link #error} 的**首段**：{@code ApiRunner} 在那里存的是
     * {@code e.getClass().getSimpleName()}，所以按类名精确匹配即可 ——
     * 不去解析 {@link #reason} 里那句给人看的中文（文案随时会改，判据不能跟着漂）。
     *
     * <p><b>刻意不含 {@code IOException}</b>：它太宽（{@code SSLException}、
     * {@code HttpTimeoutException} 都是它的子类），放进来会把「请求确实发出去了、
     * 只是回包解析失败」也算成环境问题，那是错的。
     */
    private static final Set<String> NETWORK_EXCEPTIONS = Set.of(
            "ConnectException",
            "HttpConnectTimeoutException",
            "HttpTimeoutException",
            "SocketTimeoutException",
            "UnknownHostException",
            "NoRouteToHostException",
            "SSLException",
            "SSLHandshakeException");

    /**
     * 这次执行是不是**网络 / 环境类**失败（连不上、超时、域名解析不了、证书）？
     *
     * <p>区分它的用途只有一个（需求 #73）：这类失败**不能**代表用例或产品有问题，
     * 压测遇到它应当等环境恢复后重试，而不是把这圈算作无效、连累整个游戏被跳过。
     *
     * <p>只对 {@link #ERROR} 成立。{@link #FAIL} 是「服务答复了、但答案不对」——
     * 那是真正的用例失败，不该等环境。
     */
    public static boolean isNetworkFailure(ApiExec e) {
        if (e == null || !ERROR.equals(e.verdict()) || e.error() == null) {
            return false;
        }
        String err = e.error();
        int i = err.indexOf(':');
        String cls = (i > 0 ? err.substring(0, i) : err).trim();
        return NETWORK_EXCEPTIONS.contains(cls);
    }

    /** 响应体保存上限：够定位问题即可，避免库里堆大文本。 */
    public static final int BODY_LIMIT = 20_000;

    /** 截断响应体并标注。 */
    public static String truncate(String body) {
        if (body == null) {
            return null;
        }
        if (body.length() <= BODY_LIMIT) {
            return body;
        }
        return body.substring(0, BODY_LIMIT) + "\n…（已截断，原文 " + body.length() + " 字符）";
    }
}
