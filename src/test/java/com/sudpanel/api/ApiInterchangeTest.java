package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ApiInterchangeTest {
    private ApiInterchange.Preview curl(String value) { return ApiInterchange.preview(new ApiInterchange.Input("CURL", value, null)); }
    private ApiInterchange.Preview postman(String value) { return ApiInterchange.preview(new ApiInterchange.Input("POSTMAN", value, null)); }
    private ApiSpec only(ApiInterchange.Preview p) { assertThat(p.errors()).isEmpty(); assertThat(p.requests()).hasSize(1); return p.requests().getFirst().spec(); }
    private String collection(String items) { return "{\"info\":{\"name\":\"fixture\",\"schema\":\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"},\"item\":" + items + "}"; }

    @Test void curlPreservesRawWhitespaceQuotesAndLargeInteger() {
        String body = "  {\"id\":12345678901234567890123456789012345678,\"name\":\"O'Reilly\"}\n ";
        String quoted = "'" + body.replace("'", "'\\''") + "'";
        var s = only(curl("curl --url 'https://example.test/a?q=%2B#f' \\\n -X POST -H 'Content-Type: application/json' --data-raw " + quoted));
        assertThat(s.body()).isEqualTo(body);
        assertThat(s.url()).isEqualTo("https://example.test/a?q=%2B#f");
        assertThat(s.options().bodyType()).isEqualTo("RAW");
        assertThat(s.options().auth().type()).isEqualTo("NONE");
        assertThat(s.options().transport().redirect()).isEqualTo("NEVER");
    }

    @Test void curlRepeatsDataAndDefaultsPost() {
        var s = only(curl("curl 'https://example.test' -d 'a=1' --data='a=2'"));
        assertThat(s.method()).isEqualTo("POST"); assertThat(s.body()).isEqualTo("a=1&a=2");
        assertThat(s.headers()).contains("application/x-www-form-urlencoded");
    }

    @Test void explicitGetBodyIsRetained() {
        var s = only(curl("curl https://example.test -XGET --data-raw 'text'"));
        assertThat(s.method()).isEqualTo("GET"); assertThat(s.body()).isEqualTo("text");
    }

    @Test void explicitCurlAuthorizationWinsOverHelperAndMultipleHelpersAreRejected() {
        var p = curl("curl https://example.test -u 'fake:password' -H 'Authorization: ExplicitFixture'");
        var s = only(p); assertThat(s.options().auth().type()).isEqualTo("NONE");
        assertThat(s.headers()).contains("ExplicitFixture"); assertThat(p.warnings()).anyMatch(w -> w.contains("显式 Authorization"));
        assertThat(curl("curl https://example.test -u 'fake:password' --oauth2-bearer fake-token").errors()).isNotEmpty();
    }

    @Test void curlGetAppendsBeforeFragment() {
        var s = only(curl("curl 'https://example.test?a=1#fragment' -G --data-urlencode 'email=a@b.com' --data-urlencode 'q=中 +&'"));
        assertThat(s.method()).isEqualTo("GET"); assertThat(s.body()).isNull();
        assertThat(s.url()).isEqualTo("https://example.test?a=1&email=a%40b.com&q=%E4%B8%AD+%2B%26#fragment");
    }

    @Test void curlMapsBasicHeadTimeoutAndTransport() {
        var s = only(curl("curl https://example.test -I -k -L --compressed -u 'test:fake:password' --max-time 1.25"));
        assertThat(s.method()).isEqualTo("HEAD"); assertThat(s.timeoutMs()).isEqualTo(1250);
        assertThat(s.options().auth().username()).isEqualTo("test"); assertThat(s.options().auth().password()).isEqualTo("fake:password");
        assertThat(s.insecure()).isTrue(); assertThat(s.options().transport().redirect()).isEqualTo("NORMAL");
        assertThat(s.headers()).contains("gzip, deflate");
    }

    @Test void curlRawAtIsLiteralButFilesAndShellNeverRun() {
        assertThat(only(curl("curl https://example.test --data-raw '@literal'")).body()).isEqualTo("@literal");
        for (String command : List.of("curl https://example.test -d @secret", "curl https://example.test --data-binary @file", "curl https://example.test --data-urlencode key@file", "curl https://example.test | cat", "curl https://example.test; touch /tmp/should-not-exist", "curl $(whoami)", "curl `whoami`", "curl https://example.test > output", "curl \"$BASE_URL\"", "curl https://example.test --data-raw $'line\\nnext'"))
            assertThat(curl(command).errors()).as(command).isNotEmpty();
    }

    @Test void curlRejectsLossyOrMalformedOptions() {
        for (String command : List.of("curl https://example.test --http2", "curl https://example.test -H 'X-A: a' -H 'x-a: b'", "curl https://example.test -H 'Content-Type:'", "curl https://example.test -m 0", "curl https://example.test --url https://second.test", "curl https://example.test --request", "curl 'unclosed", "curl https://example.test -u username", "curl https://example.test -b cookiefile"))
            assertThat(curl(command).errors()).as(command).isNotEmpty();
    }

    @Test void curlTokenizerRespectsPosixDoubleQuoteBackslashes() {
        assertThat(ApiInterchange.shellWords("curl \"a\\qb\" 'x;y' '' a\\ b")).containsExactly("curl", "a\\qb", "x;y", "", "a b");
    }

    @Test void postmanStructuredUrlKeepsDuplicateAndDisabledQueryWithoutDoubleApplyingRaw() {
        var s = only(postman(collection("[{\"name\":\"query\",\"request\":{\"method\":\"GET\",\"url\":{\"raw\":\"https://example.test/p?a=stale#f\",\"query\":[{\"key\":\"a\",\"value\":\"中 +\"},{\"key\":\"a\",\"value\":\"2\"},{\"key\":\"off\",\"value\":\"x\",\"disabled\":true}]}}}]")));
        assertThat(s.url()).isEqualTo("https://example.test/p#f");
        assertThat(s.options().params()).containsExactly(new ApiRequestOptions.Param("a", "中 +", true), new ApiRequestOptions.Param("a", "2", true), new ApiRequestOptions.Param("off", "x", false));
        assertThat(s.options().auth().type()).isEqualTo("NONE");
    }

    @Test void postmanUrlWithoutRawAndPathVariableAreConverted() {
        var s = only(postman(collection("[{\"name\":\"path\",\"request\":{\"url\":{\"protocol\":\"https\",\"host\":[\"api\",\"example\",\"test\"],\"port\":\"9443\",\"path\":[\"v1\",\":id\"],\"variable\":[{\"key\":\"id\",\"value\":\"38\"}]}}}]")));
        assertThat(s.url()).isEqualTo("https://api.example.test:9443/v1/38");
    }

    @Test void postmanAuthInheritanceFolderRequestOverrideAndStringRequest() {
        String content = collection("[{\"name\":\"folder\",\"auth\":{\"type\":\"basic\",\"basic\":[{\"key\":\"username\",\"value\":\"fixture\"},{\"key\":\"password\",\"value\":\"fake-password\"}]},\"item\":[{\"name\":\"a\",\"request\":\"https://example.test/a\"},{\"name\":\"b\",\"request\":{\"url\":\"https://example.test/b\",\"auth\":{\"type\":\"noauth\"}}}]}]");
        var p = postman(content); assertThat(p.errors()).isEmpty(); assertThat(p.requests()).hasSize(2);
        assertThat(p.requests().getFirst().folder()).isEqualTo("fixture / folder");
        assertThat(p.requests().getFirst().spec().options().auth().type()).isEqualTo("BASIC");
        assertThat(p.requests().get(1).spec().options().auth().type()).isEqualTo("NONE");
    }

    @Test void postmanRawRetainsHugeNumbersAndFormatting() {
        String raw = " {\n\"id\":12345678901234567890123456789012345678\n}\n";
        var item = Map.of("name", "raw", "request", Map.of("method", "POST", "url", "https://example.test", "body", Map.of("mode", "raw", "raw", raw, "options", Map.of("raw", Map.of("language", "json")))));
        var s = only(postman(collection(ApiRequestEngine.stringify(List.of(item)))));
        assertThat(s.body()).isEqualTo(raw); assertThat(s.options().bodyType()).isEqualTo("RAW"); assertThat(s.headers()).contains("application/json");
    }

    @Test void postmanFormRetainsVariablesRowsAndDisabledValues() {
        var s = only(postman(collection("[{\"name\":\"form\",\"request\":{\"method\":\"POST\",\"url\":\"https://example.test\",\"body\":{\"mode\":\"urlencoded\",\"urlencoded\":[{\"key\":\"q\",\"value\":\"{{q}}\"},{\"key\":\"q\",\"value\":\"2\",\"disabled\":true}]}}}]")));
        assertThat(s.options().bodyType()).isEqualTo("URLENCODED");
        assertThat(s.options().fields()).containsExactly(new ApiRequestOptions.Param("q", "{{q}}", true), new ApiRequestOptions.Param("q", "2", false));
    }

    @Test void postmanEnvironmentOverridesCollectionAndMapsReferenceScopes() {
        String content = collection("[{\"name\":\"scopes\",\"request\":{\"method\":\"POST\",\"url\":\"{{endpoint}}/v1\",\"header\":[{\"key\":\"X-Auth\",\"value\":\"{{credential}}\"}],\"body\":{\"mode\":\"raw\",\"raw\":\"{{body}}\"}}}]").replace("\"item\":", "\"variable\":[{\"key\":\"credential\",\"value\":\"old\"}],\"item\":");
        String env = "{\"name\":\"new-env\",\"values\":[{\"key\":\"endpoint\",\"value\":\"https://example.test\",\"enabled\":true},{\"key\":\"credential\",\"value\":\"FAKE_SECRET_VALUE\",\"type\":\"secret\"},{\"key\":\"body\",\"value\":12345678901234567890123456789012345678},{\"key\":\"off\",\"value\":\"ignored\",\"enabled\":false}]}";
        var p = ApiInterchange.preview(new ApiInterchange.Input("POSTMAN", content, env));
        assertThat(p.errors()).isEmpty(); assertThat(p.environmentName()).isEqualTo("new-env");
        assertThat(p.variables().base()).containsExactly(new ApiVariables.Var("endpoint", "https://example.test"));
        assertThat(p.variables().header()).containsExactly(new ApiVariables.Var("credential", "••••••",true));
        assertThat(p.variables().body()).containsExactly(new ApiVariables.Var("body", "12345678901234567890123456789012345678"));
        assertThat(String.join(" ", p.warnings())).doesNotContain("FAKE_SECRET_VALUE", "ignored");
    }

    @Test void postmanStandaloneEnvironmentRetainsUnusedVariablesInAllThreeScopes() {
        var p = postman("{\"name\":\"env\",\"values\":[{\"key\":\"x\",\"value\":\"y\"}]}");
        assertThat(p.errors()).isEmpty(); assertThat(p.requests()).isEmpty(); assertThat(p.variables().base()).isEqualTo(p.variables().header()).isEqualTo(p.variables().body());
    }

    @Test void scriptsNeverExecuteAndUnsupportedSemanticsAreReported() {
        String content = collection("[{\"name\":\"scripted\",\"event\":[{\"listen\":\"test\",\"script\":{\"exec\":[\"throw new Error('fixture')\"]}}],\"request\":\"https://example.test\"}]");
        var p = postman(content); assertThat(p.errors()).isEmpty(); assertThat(p.warnings()).anyMatch(w -> w.contains("脚本未执行"));
        for (String request : List.of("{\"url\":\"https://example.test\",\"auth\":{\"type\":\"digest\"}}", "{\"url\":\"https://example.test\",\"body\":{\"mode\":\"file\"}}", "{\"url\":\"https://example.test\",\"certificate\":{}}"))
            assertThat(postman(collection("[{\"name\":\"unsupported\",\"request\":" + request + "}]")).errors()).isNotEmpty();
    }

    @Test void badJsonVersionsNestedVariablesAndReservedNamesFailExplicitly() {
        assertThat(postman(collection("[]") + " {}").errors()).isNotEmpty();
        assertThat(postman(collection("[]").replace("v2.1.0", "v2.0.0")).errors()).isNotEmpty();
        assertThat(postman("{\"values\":[{\"key\":\"x\",\"value\":\"{{other}}\"}]}").errors()).isNotEmpty();
        assertThat(postman("{\"values\":[{\"key\":\"run.token\",\"value\":\"x\"}]}").errors()).isNotEmpty();
    }

    @Test void guidMapsToProtectedUuidAndUnknownDynamicWarns() {
        var p = postman(collection("[{\"name\":\"dynamic\",\"request\":\"https://example.test/{{$guid}}/{{$randomEmail}}\"}]"));
        assertThat(p.errors()).isEmpty(); assertThat(p.requests().getFirst().spec().url()).contains("{{$uuid}}", "{{$randomEmail}}");
        assertThat(p.warnings()).anyMatch(w -> w.contains("$randomEmail"));
    }

    @Test void curlExportDefaultRedactsHeaderQueryJsonAndApiKey() {
        var s = new ApiSpec(null, "export", "POST", "https://example.test?access_token=FAKE_URL_SECRET&keep=yes", "{\"Authorization\":\"FAKE_HEADER_SECRET\",\"X-A\":\"plain\"}", "{\"nested\":{\"password\":\"FAKE_BODY_SECRET\"},\"id\":12345678901234567890123456789012345678}", "", "", "", 10000, false, true, null, null,
                new ApiRequestOptions("JSON", List.of(), new ApiRequestOptions.Auth("API_KEY", null, null, null, "X-Custom", "FAKE_AUTH_SECRET", "HEADER"), List.of()));
        var result = ApiInterchange.exportCurl(s, false);
        assertThat(result.curl()).doesNotContain("FAKE_URL_SECRET", "FAKE_HEADER_SECRET", "FAKE_BODY_SECRET", "FAKE_AUTH_SECRET").doesNotContain("12345678901234567890123456789012345678").contains("REDACTED_REQUEST_BODY", "plain", "keep=yes");
        assertThat(result.warnings()).isNotEmpty();
    }

    @Test void curlExportWithSecretsRoundTripsRawBytesAndApostrophes() {
        String body = " \nO'Reilly & \"quoted\" ";
        var s = new ApiSpec(null, "raw", "PATCH", "https://example.test", "{\"X-Fixture\":\"O'Reilly\"}", body, "", "", "", 10000, false, true, null, null, new ApiRequestOptions("RAW", List.of(), new ApiRequestOptions.Auth("NONE", null, null, null, null, null, null), List.of()));
        var exported = ApiInterchange.exportCurl(s, true);
        var back = only(curl(exported.curl())); assertThat(back.method()).isEqualTo("PATCH"); assertThat(back.body()).isEqualTo(body); assertThat(back.headers()).contains("O'Reilly");
    }

    @Test void defaultExportOmitsUnknownRawAndFormSecretsAndMasksEncodedQuery() {
        var s = new ApiSpec(null, "export", "POST", "https://example.test?access%5Ftoken=FAKE_ENCODED_SECRET", "{}", "unknown-free-text-secret", "", "", "", 10000, false, true, null, null,
                new ApiRequestOptions("URLENCODED", List.of(), new ApiRequestOptions.Auth("API_KEY", null, null, null, "customName", "FAKE_CUSTOM_KEY", "QUERY"), List.of(), List.of(new ApiRequestOptions.Param("customField", "FAKE_FORM_SECRET", true)), null, true));
        var exported = ApiInterchange.exportCurl(s, false);
        assertThat(exported.curl()).doesNotContain("FAKE_ENCODED_SECRET", "FAKE_CUSTOM_KEY", "FAKE_FORM_SECRET", "unknown-free-text-secret").contains("REDACTED_REQUEST_BODY");
        assertThat(exported.warnings()).anyMatch(w -> w.contains("不能直接复放"));
    }
}
