package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ApiActualCurlLegacyTest {
    ApiExec old(String body,String metadata){return new ApiExec(1L,null,null,"legacy",1,1,"POST","http://127.0.0.1/fixture",200,null,null,"PASS",null,1L,"{}",null,"POST http://127.0.0.1/fixture\nX-Fixture: value\n（自动头未列出）",body,null,"2026-10-14T00:00:00Z",null,metadata);}
    @Test void metadataMissingOrInvalidIsUnknownWithoutFabricatingCompletion() {
        for(String metadata:new String[]{null,"bad-json","{}"}){var exported=ApiActualCurl.history(old("literal",metadata),true);assertThat(exported.complete()).isFalse();assertThat(exported.curl()).contains("literal");assertThat(exported.warnings()).anyMatch(w->w.contains("完整性"));}
        assertThatThrownBy(()->ApiActualCurl.history(old("x\n…（已截断，原文 99999 字符）",null),true)).hasMessageContaining("截断");
    }
    @Test void knownCompleteRawBodyMayContainTheLegacyMarkerAsLiteralText() {
        var exported=ApiActualCurl.history(old("用户文本：…（已截断，原文 无数字）","{\"requestBodyTruncated\":false}"),true);
        assertThat(exported.complete()).isTrue();assertThat(exported.curl()).contains("用户文本：…（已截断，原文 无数字）");
    }
}
