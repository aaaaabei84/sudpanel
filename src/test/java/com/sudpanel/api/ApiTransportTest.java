package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.GZIPOutputStream;
import java.util.zip.DeflaterOutputStream;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApiTransportTest {
    @TempDir Path temporary;
    HttpServer server;
    ApiRunner runner;
    ApiRepository repo;
    String endpoint;
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> uris = new CopyOnWriteArrayList<>();
    @BeforeEach void setup() throws Exception {
        repo = new ApiRepository(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + temporary.resolve("test.db"))));
        runner = new ApiRunner(repo, new CodeDictionary(repo), "");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", x -> {
            uris.add(x.getRequestURI().toString());
            bodies.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = x.getRequestURI().getPath();
            String content = "{\"code\":0,\"text\":\"中文\"}";
            String cookie = x.getRequestHeaders().getFirst("Cookie");
            if (path.equals("/peek")) content = cookie == null ? "none" : cookie;
            if (path.equals("/cookie")) x.getResponseHeaders().set("Set-Cookie", "session=fake-session; Path=/; HttpOnly");
            if (path.startsWith("/large")) content = "x".repeat(8192);
            byte[] data = content.getBytes(path.equals("/gbk") ? Charset.forName("GBK") : StandardCharsets.UTF_8);
            if (path.equals("/gzip") || path.equals("/bomb")) {
                var bytes = new ByteArrayOutputStream();
                try (var zip = new GZIPOutputStream(bytes)) { zip.write((path.equals("/bomb") ? "x".repeat(10000) : content).getBytes(StandardCharsets.UTF_8)); }
                data = bytes.toByteArray(); x.getResponseHeaders().set("Content-Encoding", "gzip");
            }
            if (path.equals("/deflate")) {
                var bytes = new ByteArrayOutputStream();
                try (var zip = new DeflaterOutputStream(bytes)) { zip.write(data); }
                data = bytes.toByteArray(); x.getResponseHeaders().set("Content-Encoding", "deflate");
            }
            x.getResponseHeaders().set("Content-Type", "text/plain; charset=" + (path.equals("/gbk") ? "GBK" : "UTF-8"));
            if (path.equals("/redirect")) x.getResponseHeaders().set("Location", "/peek");
            x.sendResponseHeaders(path.equals("/redirect") ? 302 : 200, path.equals("/large-chunked") ? 0 : data.length);
            try { x.getResponseBody().write(data); } catch (java.io.IOException ignored) {} finally { x.close(); }
        });
        server.start(); endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void teardown() { if (server != null) server.stop(0); }
    ApiRequestOptions.Transport transport(String redirect, String cookie, Integer limit) {
        return new ApiRequestOptions.Transport(redirect, "DIRECT", null, null, cookie, limit);
    }
    ApiSpec spec(String path, ApiRequestOptions options) {
        return new ApiSpec(null, "transport", "POST", endpoint + path, "{}", null, null, "", "", 2000, false, true, null, null, options);
    }
    ApiRequestOptions options(ApiRequestOptions.Transport t) { return new ApiRequestOptions("NONE", null, null, null, null, t, false); }

    @Test void formEncodingPreservesDuplicatesPlusUnicodeAndDisabledRows() {
        var fields = List.of(new ApiRequestOptions.Param("a", "中文 +&%", true),
                new ApiRequestOptions.Param("a", "", true), new ApiRequestOptions.Param("skip", "bad", false));
        var request = spec("/form", new ApiRequestOptions("URLENCODED", null, null, null, fields, null, true));
        var preview = runner.prepare(request, 1L);
        assertThat(preview.valid()).isTrue();
        assertThat(preview.headers()).containsEntry("Content-Type", "application/x-www-form-urlencoded");
        assertThat(preview.body()).isEqualTo("a=%E4%B8%AD%E6%96%87+%2B%26%25&a=");
        assertThat(runner.send(request, 1L, null).verdict()).isEqualTo("PASS");
        assertThat(bodies.getLast()).isEqualTo(preview.body());
    }
    @Test void responseDecodesGzipDeflateAndGbkBeforeAssertions() {
        for (String path : List.of("/gzip", "/deflate", "/gbk")) {
            var result = runner.send(spec(path, null), 1L, null);
            assertThat(result.verdict()).isEqualTo("PASS");
            assertThat(result.respBody()).contains("中文");
            assertThat(result.metadata()).contains("decodedBytes", "charset");
        }
    }
    @Test void fixedAndChunkedAndDecompressionAreAllBounded() {
        for (String path : List.of("/large-fixed", "/large-chunked", "/bomb")) {
            var result = runner.send(spec(path, options(transport(null, null, 1024))), 1L, null);
            assertThat(result.verdict()).isEqualTo("ERROR");
            assertThat(result.reason()).contains("上限");
            assertThat(result.assertsResult()).isNull();
        }
        assertThat(runner.send(spec("/ok", null), 1L, null).verdict()).isEqualTo("PASS");
    }
    @Test void redirectNeverAndNormalHaveDifferentObservableResults() {
        var first = runner.send(spec("/redirect", options(transport("NEVER", null, null))), 1L, null);
        assertThat(first.httpStatus()).isEqualTo(302);
        var follow = runner.send(spec("/redirect", options(transport("NORMAL", null, null))), 1L, null);
        assertThat(follow.httpStatus()).isEqualTo(200);
        assertThat(follow.metadata()).contains("redirects", "/peek", "302");
    }
    @Test void cookieSessionsAreExplicitIsolatedAndClearable() {
        long second = repo.createEnv("second").id();
        var enabled = options(transport(null, "ENV", null));
        runner.send(spec("/cookie", enabled), 1L, null);
        assertThat(runner.send(spec("/peek", enabled), 1L, null).respBody()).contains("fake-session");
        assertThat(runner.send(spec("/peek", enabled), second, null).respBody()).isEqualTo("none");
        assertThat(runner.send(spec("/peek", null), 1L, null).respBody()).isEqualTo("none");
        assertThat(runner.cookies(1L).toString()).doesNotContain("fake-session");
        runner.clearCookies(1L);
        assertThat(runner.send(spec("/peek", enabled), 1L, null).respBody()).isEqualTo("none");
    }
    @Test void perRequestHttpProxyActuallyReceivesAbsoluteTarget() {
        var t = new ApiRequestOptions.Transport("NEVER", "HTTP", "127.0.0.1", server.getAddress().getPort(), "OFF", null);
        var source = spec("/proxy", options(t));
        var request = new ApiSpec(null, source.name(), source.method(), "http://example.invalid/proxy", source.headers(), null,
                null, "200", "", 2000, false, true, null, null, source.options());
        assertThat(runner.send(request, 1L, null).verdict()).isEqualTo("PASS");
        assertThat(uris.getLast()).isEqualTo("http://example.invalid/proxy");
    }
    @Test void strictVariablesPreventNetworkAndOldDefaultRetainsCompatibility() {
        var request = spec("/ok", new ApiRequestOptions("RAW", null, null, null, null, null, true));
        request = new ApiSpec(null, "strict", "POST", request.url(), "{}", "{{missing}}", null, "", "", 2000, false, true, null, null, request.options());
        assertThat(runner.prepare(request, 1L).valid()).isFalse();
        assertThat(runner.send(request, 1L, null).verdict()).isEqualTo("ERROR");
        assertThat(uris).isEmpty();
        var compatible = new ApiSpec(null, "old", "POST", request.url(), "{}", "{{missing}}", null, "", "", 2000, false, true, null, null);
        assertThat(runner.send(compatible, 1L, null).verdict()).isEqualTo("PASS");
        assertThat(bodies.getLast()).isEqualTo("{{missing}}");
    }
    @Test void responseJsonRequiresOneCompleteDocumentEverywhere() {
        for (String invalid : List.of("{\"ok\":true} trailing", "{\"ok\":true} {}")) {
            assertThat(ApiResponseTools.inspect(invalid).validJson()).isFalse();
            assertThat(ApiBodyAssert.evaluate(invalid, List.of(new ApiBodyAssert.Rule("ok", "equals", "true")))).isNotEmpty();
            assertThat(CodeDictionary.extract(invalid)).isNull();
        }
        assertThat(ApiBodyAssert.evaluate("{\"ok\":true} trailing", List.of(new ApiBodyAssert.Rule("", "contains", "trailing")))).isEmpty();
    }
    @Test void inspectionLimitsOnlyFlagActualOmission() {
        assertThat(ApiResponseTools.inspect("[" + String.join(",", java.util.Collections.nCopies(499, "0")) + "]").truncated()).isFalse();
        assertThat(ApiResponseTools.inspect("[" + String.join(",", java.util.Collections.nCopies(500, "0")) + "]").truncated()).isTrue();
        assertThat(ApiResponseTools.inspect("[".repeat(34) + "0" + "]".repeat(34)).truncated()).isTrue();
    }
    @Test void invalidAssertionConfigurationsNeverSilentlyDisappear() {
        for (String text : List.of("[null]", "[7]", "[{\"op\":\"unknown\"}]", "[{\"op\":\"regex\",\"expect\":\"[\"}]",
                "[{\"op\":\"gt\",\"expect\":\"abc\"}]", "[{\"op\":\"length\",\"expect\":\"-1\"}]"))
            assertThatThrownBy(() -> ApiBodyAssert.validateRules(text)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedTransportFailsPreviewAndDoesNotSend() {
        var request = spec("/ok", options(new ApiRequestOptions.Transport("NEVER", "HTTP", "http://bad", -1, "OFF", 5)));
        assertThat(runner.prepare(request, 1L).valid()).isFalse();
        assertThat(uris).isEmpty();
    }

    @Test void headLargeResourceLengthIsNotConfusedWithDownloadedBodyBytes() {
        server.createContext("/head-large", x -> {
            x.getResponseHeaders().set("Content-Length", "100000000");
            x.sendResponseHeaders(200, -1); x.close();
        });
        var request = new ApiSpec(null, "head", "HEAD", endpoint + "/head-large", "{}", null, null,
                "200", "", 2000, false, true, null, null, options(transport(null, null, 1024)));
        var result = runner.send(request, 1L, null);
        assertThat(result.verdict()).isEqualTo("PASS");
        assertThat(result.respBody()).isEmpty();
        assertThat(result.metadata()).contains("wireBytes\":0");
    }
}
