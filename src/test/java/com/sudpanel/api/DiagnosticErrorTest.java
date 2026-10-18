package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import com.sudpanel.web.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;

class DiagnosticErrorTest {
    @Test void unexpectedFailureReturnsTraceIdAndKeepsPrivateDetailsOutOfResponse() {
        var response = new GlobalExceptionHandler().handleOther(new RuntimeException("fake-private-path/fake-sensitive-value"));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).containsKeys("message", "status", "requestId");
        assertThat(response.getHeaders().getFirst("X-Request-Id")).isEqualTo(response.getBody().get("requestId"));
        assertThat(response.getBody().toString()).doesNotContain("fake-sensitive-value", "fake-private-path");
    }
    @Test void actionableValidationMessageRemainsReadable() {
        var response = new GlobalExceptionHandler().handleBadRequest(new IllegalArgumentException("人数超出上限"));
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).containsEntry("message", "人数超出上限");
    }
}
