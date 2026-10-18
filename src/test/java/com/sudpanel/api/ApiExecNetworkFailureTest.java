package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 网络类失败的识别（2026-10-09，v1.9.4 #73）。
 *
 * <p>要钉住的是「环境问题」与「用例问题」不能混为一谈：前者应当等环境恢复后重试，
 * 后者才是真的无效圈。
 *
 * <p>判据来自 {@code error} 字段首段的异常类名。这条规则一旦被改坏，
 * run#84 那种「一次网络抖动吃掉整个游戏全部圈数」的事故就会回来 ——
 * 而且不会有任何报错，只会在第二天看报告时发现「怎么只有一圈」。
 */
class ApiExecNetworkFailureTest {

    /** 造一条 ERROR 结果：只有 verdict 与 error 参与判定，其余字段给 null。 */
    private static ApiExec err(String error) {
        return new ApiExec(null, null, null, null, null, null, null, null, null, null, null,
                ApiExec.ERROR, null, null, null, null, null, null, error, null, null);
    }

    @Test
    @DisplayName("★ 连不上 / 超时 / 解析不了 / 证书 —— 都算环境问题")
    void recognisesNetworkErrors() {
        assertThat(ApiExec.isNetworkFailure(err("ConnectException: Connection refused"))).isTrue();
        assertThat(ApiExec.isNetworkFailure(err("HttpTimeoutException: request timed out"))).isTrue();
        assertThat(ApiExec.isNetworkFailure(err("HttpConnectTimeoutException: HTTP connect timed out"))).isTrue();
        assertThat(ApiExec.isNetworkFailure(err("SocketTimeoutException: Read timed out"))).isTrue();
        assertThat(ApiExec.isNetworkFailure(err("UnknownHostException: 内网域名"))).isTrue();
        assertThat(ApiExec.isNetworkFailure(err("NoRouteToHostException: No route to host"))).isTrue();
        assertThat(ApiExec.isNetworkFailure(err("SSLHandshakeException: PKIX path building failed"))).isTrue();
    }

    @Test
    @DisplayName("★ FAIL 不算环境问题 —— 服务答复了，那是真正的用例失败")
    void failIsNotNetworkFailure() {
        ApiExec failed = new ApiExec(null, null, null, null, null, null, null, null, 200, "100500", null,
                ApiExec.FAIL, "业务返回码不符", null, null, null, null, null, null, null, null);

        assertThat(ApiExec.isNetworkFailure(failed)).isFalse();
    }

    @Test
    @DisplayName("PASS 不算，ERROR 也不是每一条都算")
    void passAndUnknownErrorAreNot() {
        ApiExec passed = new ApiExec(null, null, null, null, null, null, null, null, 200, "0", null,
                ApiExec.PASS, null, null, null, null, null, null, null, null, null);
        assertThat(ApiExec.isNetworkFailure(passed)).isFalse();

        // 解析/构造类的异常：请求本身可能压根没发出去，但这不是「环境不通」，等环境也没用
        assertThat(ApiExec.isNetworkFailure(err("IllegalArgumentException: 地址不合法"))).isFalse();
        assertThat(ApiExec.isNetworkFailure(err("NullPointerException"))).isFalse();
    }

    @Test
    @DisplayName("宽泛的 IOException 不算 —— 它会把「回包读失败」（SSL/超时的父类）一并吞进来")
    void genericIoExceptionIsNotNetworkFailure() {
        assertThat(ApiExec.isNetworkFailure(err("IOException: 响应体读取失败"))).isFalse();
    }

    @Test
    @DisplayName("边界：null 结果、error 为空串都不能误判成环境问题")
    void boundaries() {
        assertThat(ApiExec.isNetworkFailure(null)).isFalse();
        assertThat(ApiExec.isNetworkFailure(err(null))).isFalse();
        assertThat(ApiExec.isNetworkFailure(err(""))).isFalse();
    }

    @Test
    @DisplayName("error 里没有「类名: 信息」的分隔冒号时，整串当类名匹配（对格式变化宽容）")
    void toleratesMissingColon() {
        assertThat(ApiExec.isNetworkFailure(err("ConnectException"))).isTrue();
        assertThat(ApiExec.isNetworkFailure(err("Connection refused"))).isFalse();
    }
}
