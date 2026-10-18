package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Timeout(10)
class ApiDeadlineAndLiteralTest {
    @TempDir Path temporary;
    HttpServer first, other;
    ApiRunner runner;
    ApiRepository repo;
    String endpoint;
    @BeforeEach void setup() throws Exception {
        repo = new ApiRepository(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + temporary.resolve("test.db"))));
        runner = new ApiRunner(repo, new CodeDictionary(repo), "");
        first = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        first.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        other.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        first.start(); other.start(); endpoint = "http://127.0.0.1:" + first.getAddress().getPort();
    }
    @AfterEach void close() { first.stop(0); other.stop(0); }
    ApiSpec spec(String path, String headers, int timeout) {
        return new ApiSpec(null, "review", "GET", endpoint + path, headers, null, null, "200", "", timeout,
                false, true, null, null);
    }

    @Test void deadlineIncludesSlowBodyAfterHeadersWereReceived() throws Exception {
        first.createContext("/slow", exchange -> {
            byte[] data = "{\"code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data, 0, 1); exchange.getResponseBody().flush();
            try { Thread.sleep(1500); exchange.getResponseBody().write(data, 1, data.length - 1); }
            catch (Exception ignored) { } finally { exchange.close(); }
        });
        long started = System.nanoTime();
        ApiExec result = runner.send(spec("/slow", "{}", 250), 1L, null);
        long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
        System.out.println("REVIEW slow-body elapsedMs=" + elapsed + " verdict=" + result.verdict());
        assertThat(result.verdict()).isEqualTo("ERROR");
        assertThat(result.assertsResult()).isNull();
        assertThat(elapsed).isLessThan(1000);
    }

    @Test void crossOriginRedirectDoesNotForwardAuthorizationOrManualCookie() throws Exception {
        AtomicReference<String> auth = new AtomicReference<>(), cookie = new AtomicReference<>();
        other.createContext("/sink", exchange -> {
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes()); exchange.close();
        });
        first.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/sink");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        ApiExec result = runner.send(spec("/redirect", "{\"Authorization\":\"Bearer FICTIONAL\",\"Cookie\":\"secret=FICTIONAL\"}", 2000), 1L, null);
        assertThat(result.verdict()).isEqualTo("PASS");
        System.out.println("REVIEW redirect authForwarded=" + (auth.get() != null) + " manualCookieForwarded=" + (cookie.get() != null));
        assertThat(auth.get()).isNull(); assertThat(cookie.get()).isNull();
    }

    @Test void dataValuesContainingLegacyPlaceholderSyntaxRemainLiteralUnderSudAuth() {
        var env = new ApiRequestEngine.Environment(1, "fictional", "now", ApiVariables.VarSet.empty(),
                new ApiSigner.Config(true, "fictional-app", "fictional-mg", "fictional-secret", "fictional-nonce"));
        var options = new ApiRequestOptions("JSON", null, null, null);
        var spec = new ApiSpec(null, "data", "POST", "http://127.0.0.1/example", "{}", "{\"value\":\"{{data.value}}\"}",
                null, "", "", 1000, false, true, null, null, options);
        String body = ApiRequestEngine.build(spec, env, null, Map.of(), Map.of("value", "\"{{times}}/{{nonce}}/{{app_id}}\"")).body();
        System.out.println("REVIEW dataLegacySyntaxPreserved=" + body.contains("{{times}}"));
        assertThat(body).isEqualTo("{\"value\":\"{{times}}/{{nonce}}/{{app_id}}\"}");
    }

    @Test void crossOriginRedirectDoesNotForwardConfiguredApiKeyHeader() throws Exception {
        AtomicReference<String> key = new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger hits = new java.util.concurrent.atomic.AtomicInteger();
        other.createContext("/sink-key", exchange -> {
            hits.incrementAndGet(); key.set(exchange.getRequestHeaders().getFirst("X-API-Key"));
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes()); exchange.close();
        });
        first.createContext("/redirect-key", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + other.getAddress().getPort() + "/sink-key");
            exchange.sendResponseHeaders(302, -1); exchange.close();
        });
        var auth = new ApiRequestOptions.Auth("API_KEY", null, null, null, "X-API-Key", "FICTIONAL-KEY", "HEADER");
        var request = new ApiSpec(null, "key", "GET", endpoint + "/redirect-key", "{}", null, null,
                "200", "", 2000, false, true, null, null, new ApiRequestOptions("NONE", null, auth, null));
        ApiExec result = runner.send(request, 1L, null);
        assertThat(result.verdict()).isEqualTo("FAIL");
        assertThat(result.httpStatus()).isEqualTo(302);
        assertThat(result.metadata()).contains("blockedRedirect\":true");
        assertThat(hits.get()).isZero();
        System.out.println("REVIEW redirect configuredApiKeyForwarded=" + (key.get() != null));
        assertThat(key.get()).isNull();
    }

    @Test void plainBatchStorageFailureCannotBeReportedAsDone() throws Exception {
        first.createContext("/batch", exchange -> {
            exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write("{}".getBytes()); exchange.close();
        });
        ApiSpec saved = repo.createCase(spec("/batch", "{}", 2000));
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + temporary.resolve("test.db")));
        jdbc.execute("CREATE TRIGGER reject_review_exec BEFORE INSERT ON api_exec BEGIN SELECT RAISE(ABORT, 'fictional-storage-failure'); END");
        ApiBatch batch = runner.startBatch("review-storage", List.of(saved.id()), 0, 1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (runner.isRunning(batch.id()) && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(runner.isRunning(batch.id())).isFalse();
        String status = repo.getBatch(batch.id()).status();
        System.out.println("REVIEW plain-batch-storage-failure status=" + status + " records=" + repo.listExecsByBatch(batch.id()).size());
        assertThat(status).isEqualTo(ApiBatch.FAILED);
    }
}
