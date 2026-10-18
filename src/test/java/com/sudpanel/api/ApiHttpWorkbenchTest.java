package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApiHttpWorkbenchTest {
    @Test void dataRowsHaveIndependentVariablesAndAlwaysAttemptTheirCleanup() throws Exception {
        response = wire -> wire.path().equals("/produce") && wire.body().contains("2") ? "{}"
                : wire.body().isBlank() ? "{}" : wire.body();
        var producer = repo.createCase(request("/produce", "{\"id\":{{data.id}}}",
                options("JSON", null, List.of(extraction("token", "id")))));
        var consumerBase = request("/consume", "{\"id\":{{run.token}}}", options("JSON", null, null));
        var consumer = repo.createCase(new ApiSpec(null, "consume", consumerBase.method(), consumerBase.url(), "{}", consumerBase.body(), null,
                "200", "", 2000, false, true, null, "[{\"path\":\"id\",\"op\":\"equals\",\"expect\":\"{{data.id}}\"}]", consumerBase.options()));
        var cleanup = repo.createCase(request("/cleanup", "{\"row\":{{data.id}}}", options("JSON", null, null)));
        var scenario = repo.saveScenario(null, new ApiScenario(null, "data-chain", List.of(
                step("produce", producer, false, false, null), step("consume", consumer, false, true, null), step("cleanup", cleanup, true, false, null))));
        var results = waitFor(runner.startDataBatch("rows", null, scenario.id(), new ApiDataSet.Input("JSON", "[{\"id\":1},{\"id\":2}]"), 0, 1));
        assertThat(results).extracting(ApiExec::verdict).containsExactly("PASS", "PASS", "PASS", "FAIL", "ERROR", "PASS");
        assertThat(wires.stream().filter(w -> w.path().equals("/consume")).count()).isEqualTo(1);
        assertThat(wires.stream().filter(w -> w.path().equals("/cleanup")).count()).isEqualTo(2);
        assertThat(results.get(4).reason()).contains("未解析变量", "run.token");
        assertThat(results.getLast().metadata()).contains("dataRowIndex\":2", "CLEANUP");
    }
    @TempDir Path temporary;
    ApiRepository repo;
    ApiRunner runner;
    JdbcTemplate jdbc;
    HttpServer server;
    String endpoint;
    final ObjectMapper json = new ObjectMapper();
    final List<Wire> wires = new CopyOnWriteArrayList<>();
    Function<Wire, String> response = request -> "{\"code\":0}";
    record Wire(String path, String query, String method, String body, Map<String, List<String>> headers) {}

    @BeforeEach void setUp() throws Exception {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + temporary.resolve("api.db")));
        repo = new ApiRepository(jdbc);
        runner = new ApiRunner(repo, new CodeDictionary(repo), "");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Wire wire = new Wire(exchange.getRequestURI().getPath(), exchange.getRequestURI().getRawQuery(),
                    exchange.getRequestMethod(), new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                    new LinkedHashMap<>(exchange.getRequestHeaders()));
            wires.add(wire);
            byte[] body = response.apply(wire).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("X-Reply", "reply-123");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start(); endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void tearDown() { if (server != null) server.stop(0); }

    ApiSpec request(String path, String body, ApiRequestOptions options) {
        return new ApiSpec(null, path, "POST", endpoint + path, "{}", body, null, "200", null,
                2000, false, true, null, null, options);
    }
    ApiRequestOptions options(String type, ApiRequestOptions.Auth auth, List<ApiExtractor> extractors) {
        return new ApiRequestOptions(type, List.of(), auth, extractors);
    }
    ApiRequestOptions.Auth auth(String type, String token) {
        return new ApiRequestOptions.Auth(type, token, null, null, null, null, null);
    }
    ApiExtractor extraction(String name, String path) { return new ApiExtractor(name, "JSON", path, true); }
    ApiScenario.Step step(String key, ApiSpec spec, boolean cleanup, boolean independent, List<ApiExtractor> rules) {
        return new ApiScenario.Step(key, key, spec.id(), null, "STOP", cleanup, rules, independent);
    }
    List<ApiExec> waitFor(ApiBatch batch) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (runner.isRunning(batch.id()) && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(runner.isRunning(batch.id())).isFalse();
        return repo.listExecsByBatch(batch.id());
    }

    @Test void prepareDoesNotSendAndSendPreservesDraftEnvironmentAndQueryEncoding() throws Exception {
        long env = repo.createEnv("draft-env").id();
        repo.saveVars(env, List.of(new ApiVariables.Var("host", endpoint)), List.of(), List.of());
        var spec = new ApiSpec(null, "draft", "POST", "{{host}}/draft#ignored", "{}", "{\"id\":\"{{$uuid}}\"}",
                null, "200", null, 2000, false, true, null, null,
                new ApiRequestOptions("JSON", List.of(new ApiRequestOptions.Param("名字", "空 格&", true)), auth("NONE", null), null));
        var preview = runner.prepare(spec, env);
        assertThat(preview.valid()).isTrue(); assertThat(wires).isEmpty();
        assertThat(preview.url()).contains("%E5%90%8D%E5%AD%97=%E7%A9%BA%20%E6%A0%BC%26#ignored");
        var sent = runner.send(spec, env, Map.of("runId", 12, "roundId", "12345678901234567890123456789012345678", "ignore", "drop"));
        assertThat(sent.verdict()).isEqualTo(ApiExec.PASS);
        assertThat(sent.caseId()).isNull(); assertThat(wires).hasSize(1);
        assertThat(json.readTree(sent.metadata()).path("envName").asText()).isEqualTo("draft-env");
        assertThat(json.readTree(sent.metadata()).path("sourceContext").has("ignore")).isFalse();
        assertThat(json.readTree(preview.body()).path("id").asText()).isNotEqualTo(json.readTree(wires.getFirst().body()).path("id").asText());
    }

    @Test void protectedVariablesCannotBeShadowedAndUseOneValuePerRequest() throws Exception {
        repo.saveVars(1, List.of(), List.of(), List.of(new ApiVariables.Var("$uuid", "shadow")));
        var sent = runner.send(request("/dynamic", "{\"a\":\"{{$uuid}}\",\"b\":\"{{$uuid}}\",\"r\":{{$randomInt:4:4}}}", options("JSON", null, null)), null, null);
        JsonNode body = json.readTree(wires.getFirst().body());
        assertThat(sent.verdict()).isEqualTo(ApiExec.PASS);
        assertThat(body.path("a").asText()).isEqualTo(body.path("b").asText()).isNotEqualTo("shadow");
        assertThat(body.path("r").asInt()).isEqualTo(4);
        var invalid = runner.send(request("/never", "{{run.missing}}", options("RAW", null, null)), null, null);
        assertThat(invalid.verdict()).isEqualTo(ApiExec.ERROR); assertThat(wires).hasSize(1);
        var legacy = runner.send(request("/legacy", "{{unconfigured}}", options("RAW", null, null)), null, null);
        assertThat(legacy.verdict()).isEqualTo(ApiExec.PASS);
        assertThat(legacy.metadata()).contains("未解析变量");
    }

    @Test void signingClockNonceAndBodyAreTheActualWireValuesAndOverrideIsNotSaved() throws Exception {
        repo.saveSignConfig(1, true, "test-app", "test-only-key", "stable-nonce", "game");
        repo.saveVars(1, List.of(), List.of(), List.of(new ApiVariables.Var("mg_id", "original")));
        ApiSpec spec = request("/signed", "{\"t\":{{$timestamp}},\"n\":\"{{$auth.nonce}}\",\"g\":\"{{mg_id}}\"}", null);
        ApiExec result = runner.execute(spec, Map.of("mg_id", "override"), null, 0, 0);
        Wire wire = wires.getFirst();
        JsonNode body = json.readTree(wire.body());
        long timestamp = body.path("t").asLong();
        String expected = ApiSigner.authorizationValue("test-app", timestamp, "stable-nonce",
                ApiSigner.sign("test-only-key", "test-app", timestamp, "stable-nonce", wire.body()));
        assertThat(wire.headers().get("Authorization")).containsExactly(expected);
        assertThat(result.reqBody()).isEqualTo(wire.body());
        assertThat(body.path("g").asText()).isEqualTo("override");
        assertThat(repo.getVars(1).body().getFirst().value()).isEqualTo("original");
        assertThat(repo.listRecentAdhoc(10)).isEmpty();
    }

    @Test void authModesAndStrictJsonHaveUnambiguousWireBehavior() throws Exception {
        repo.saveSignConfig(1, true, "app", "test-key", "nonce", "game");
        var raw = new ApiSpec(null, "manual", "POST", endpoint + "/manual", "{\"authorization\":\"Manual token\"}", "raw",
                null, "200", null, 2000, false, true, null, null, options("RAW", auth("NONE", null), null));
        assertThat(runner.send(raw, null, null).verdict()).isEqualTo(ApiExec.PASS);
        assertThat(wires.getLast().headers().get("Authorization")).containsExactly("Manual token");
        var bearer = new ApiSpec(null, "bearer", "POST", endpoint + "/bearer", "{\"authorization\":\"old\",\"Authorization\":\"old2\"}", "{}",
                null, "200", null, 2000, false, true, null, null, options("JSON", auth("BEARER", "new-token"), null));
        assertThat(runner.send(bearer, null, null).verdict()).isEqualTo(ApiExec.PASS);
        assertThat(wires.getLast().headers().get("Authorization")).containsExactly("Bearer new-token");
        assertThat(runner.send(request("/invalid", "{bad}", options("JSON", auth("NONE", null), null)), null, null).verdict()).isEqualTo(ApiExec.ERROR);
        assertThat(wires).hasSize(2);
    }

    @Test void scenarioExtractsBeforeTruncationAndKeepsNumericAndStringTypes() throws Exception {
        String id = "12345678901234567890123456789012345678";
        response = wire -> wire.path().equals("/issue") ? "{\"padding\":\"" + "x".repeat(22000)
                + "\",\"id\":" + id + ",\"text\":\"123\",\"literal\":\"{{run.missing}} \\\" \\\\ line\\n\"}" : "{}";
        var first = repo.createCase(request("/issue", "{}", null));
        var second = repo.createCase(request("/consume", "{\"id\":{{run.id}},\"text\":{{run.text}},\"literal\":\"{{run.literal}}\"}", options("JSON", null, null)));
        var scene = repo.saveScenario(null, new ApiScenario(null, "typed", List.of(
                step("issue", first, false, false, List.of(extraction("id", "id"), extraction("text", "text"), extraction("literal", "literal"))),
                step("consume", second, false, false, List.of()))));
        List<ApiExec> results = waitFor(runner.startScenario(scene.id(), null, 0, 1));
        assertThat(results).allMatch(e -> ApiExec.PASS.equals(e.verdict()));
        JsonNode consumed = json.readTree(wires.getLast().body());
        assertThat(consumed.path("id").asText()).isEqualTo(id);
        assertThat(consumed.path("text").isTextual()).isTrue();
        assertThat(consumed.path("literal").asText()).startsWith("{{run.missing}}");
        assertThat(json.readTree(results.getFirst().metadata()).path("responseBodyTruncated").asBoolean()).isTrue();
        assertThat(results.getFirst().respBody()).contains("已截断");
        assertThat(json.readTree(results.getFirst().metadata()).path("capturedAt").asText())
                .isEqualTo(json.readTree(results.getLast().metadata()).path("capturedAt").asText());
    }

    @Test void failedExtractionDoesNotReusePreviousRoundAndIndependentAndCleanupStillRun() throws Exception {
        AtomicInteger rounds = new AtomicInteger();
        response = wire -> wire.path().equals("/issue") && rounds.incrementAndGet() == 1 ? "{\"token\":\"first\"}" : "{}";
        var issue = repo.createCase(request("/issue", "{}", null));
        var consume = repo.createCase(request("/consume", "{{run.token}}", options("RAW", null, null)));
        var independent = repo.createCase(request("/independent", "{}", null));
        var cleanup = repo.createCase(request("/cleanup", "{}", null));
        var scenario = repo.saveScenario(null, new ApiScenario(null, "rounds", List.of(
                step("issue", issue, false, false, List.of(extraction("token", "token"))),
                step("consume", consume, false, false, List.of()),
                step("independent", independent, false, true, List.of()),
                step("cleanup", cleanup, true, false, List.of()))));
        var batch = runner.startScenario(scenario.id(), null, 0, 2);
        var results = waitFor(batch);
        assertThat(results.stream().map(ApiExec::verdict)).containsExactly("PASS", "PASS", "PASS", "PASS", "FAIL", "SKIP", "PASS", "PASS");
        assertThat(wires.stream().filter(w -> w.path().equals("/consume"))).hasSize(1);
        ApiBatch done = repo.getBatch(batch.id());
        assertThat(done.skippedExecs()).isEqualTo(1);
        assertThat(done.totalExecs()).isEqualTo(done.passCount() + done.failCount() + done.errorCount() + done.skippedExecs());
    }

    @Test void manualStopFinishesCurrentRequestThenRunsCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        response = wire -> {
            if (wire.path().equals("/slow")) {
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            return "{}";
        };
        var slow = repo.createCase(request("/slow", "{}", null));
        var skipped = repo.createCase(request("/skipped", "{}", null));
        var cleanup = repo.createCase(request("/cleanup", "{}", null));
        var scenario = repo.saveScenario(null, new ApiScenario(null, "stop", List.of(
                step("slow", slow, false, false, null), step("skipped", skipped, false, false, null), step("cleanup", cleanup, true, false, null))));
        var batch = runner.startScenario(scenario.id(), null, 0, 3);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        runner.stop(batch.id()); release.countDown();
        assertThat(waitFor(batch).stream().map(ApiExec::verdict)).containsExactly("PASS", "SKIP", "PASS");
        assertThat(wires.stream().map(Wire::path)).containsExactly("/slow", "/cleanup");
        assertThat(repo.getBatch(batch.id()).status()).isEqualTo(ApiBatch.STOPPED);
    }

    @Test void environmentSnapshotSurvivesConfigEditsBetweenStepsAndEnvironmentVariablesStayIsolated() throws Exception {
        long secondEnv = repo.createEnv("other").id();
        repo.saveVars(1, List.of(), List.of(), List.of(new ApiVariables.Var("value", "before")));
        response = wire -> {
            if (wire.path().equals("/first")) repo.saveVars(1, List.of(), List.of(), List.of(new ApiVariables.Var("value", "after")));
            return "{\"token\":\"one\"}";
        };
        var first = repo.createCase(request("/first", "{}", null));
        var second = repo.createCase(request("/second", "{{value}}", options("RAW", null, null)));
        var foreign = repo.createCase(request("/foreign", "{{run.token}}", options("RAW", null, null)));
        var scenario = repo.saveScenario(null, new ApiScenario(null, "snapshot", List.of(
                step("first", first, false, false, List.of(extraction("token", "token"))),
                step("second", second, false, false, List.of()),
                new ApiScenario.Step("foreign", "foreign", foreign.id(), secondEnv, "STOP", false, List.of(), false))));
        var results = waitFor(runner.startScenario(scenario.id(), null, 0, 1));
        assertThat(wires.stream().map(Wire::body)).containsExactly("{}", "before");
        assertThat(results.getLast().verdict()).isEqualTo(ApiExec.ERROR);
        assertThat(results.getLast().reason()).contains("run.token");
        assertThat(repo.getVars(1).body().getFirst().value()).isEqualTo("after");
    }

    @Test void persistenceIsIdempotentAndCopyPreservesOptions() {
        var original = repo.createCase(request("/persist", "plain", options("RAW", auth("BEARER", "test-token"), List.of(extraction("value", "data.value")))));
        var copied = repo.duplicateCase(original.id());
        assertThat(copied.options()).isEqualTo(original.options());
        repo = new ApiRepository(jdbc); repo = new ApiRepository(jdbc);
        assertThat(repo.getCase(original.id()).options()).isEqualTo(original.options());
        assertThatThrownBy(() -> repo.createCase(request("/bad", "{}", options("INVALID", null, null))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void storageFailureStillAttemptsEveryCleanupAndBatchCountersStayAtomic() throws Exception {
        var normal = repo.createCase(request("/normal", "{}", null));
        var cleanupOne = repo.createCase(request("/cleanup-one", "{}", null));
        var cleanupTwo = repo.createCase(request("/cleanup-two", "{}", null));
        jdbc.execute("CREATE TRIGGER reject_some BEFORE INSERT ON api_exec WHEN NEW.case_id IN ("
                + normal.id() + "," + cleanupOne.id() + ") BEGIN SELECT RAISE(ABORT, 'test-write-error'); END");
        var scene = repo.saveScenario(null, new ApiScenario(null, "db-error", List.of(
                step("normal", normal, false, false, null), step("one", cleanupOne, true, false, null),
                step("two", cleanupTwo, true, false, null))));
        var batch = runner.startScenario(scene.id(), null, 0, 1);
        List<ApiExec> results = waitFor(batch);
        assertThat(wires.stream().map(Wire::path)).containsExactly("/normal", "/cleanup-one", "/cleanup-two");
        assertThat(results).hasSize(1);
        assertThat(repo.getBatch(batch.id()).status()).isEqualTo(ApiBatch.FAILED);
        assertThat(repo.getBatch(batch.id()).totalExecs()).isEqualTo(1);
    }

    @Test void basicApiKeyNoneBodyAndHeadOptionsUseTheSelectedRequestBehavior() {
        var basic = new ApiRequestOptions.Auth("BASIC", null, "user", "p:ass", null, null, null);
        assertThat(runner.send(request("/basic", "{}", options("JSON", basic, null)), null, null).verdict()).isEqualTo(ApiExec.PASS);
        assertThat(wires.getLast().headers().get("Authorization")).containsExactly("Basic dXNlcjpwOmFzcw==");
        var key = new ApiRequestOptions.Auth("API_KEY", null, null, null, "k", "x y&", "QUERY");
        var queried = new ApiSpec(null, "key", "POST", endpoint + "/key?k=old&keep=yes", "{}", "discarded",
                null, "200", null, 2000, false, true, null, null, options("NONE", key, null));
        assertThat(runner.send(queried, null, null).verdict()).isEqualTo(ApiExec.PASS);
        assertThat(wires.getLast().query()).isEqualTo("keep=yes&k=x%20y%26");
        assertThat(wires.getLast().body()).isEmpty();
        for (String method : List.of("HEAD", "OPTIONS")) {
            var spec = new ApiSpec(null, method, method, endpoint + "/methods", "{}", null, null,
                    "200", null, 2000, false, true, null, null, null);
            assertThat(runner.send(spec, null, null).verdict()).isEqualTo(ApiExec.PASS);
            assertThat(wires.getLast().method()).isEqualTo(method);
        }
    }

    @Test void signedLiteralExtractionDataIsNotReinterpretedAsLegacySignerTemplates() throws Exception {
        repo.saveSignConfig(1, true, "test-app", "test-only-key", "nonce", "game");
        response = wire -> "{\"literal\":\"{{times}} {{run.missing}}\"}";
        var first = repo.createCase(request("/issue", "{}", null));
        var second = repo.createCase(request("/consume", "{\"literal\":\"{{run.literal}}\"}", options("JSON", null, null)));
        var scene = repo.saveScenario(null, new ApiScenario(null, "literal-sign", List.of(
                step("issue", first, false, false, List.of(extraction("literal", "literal"))),
                step("consume", second, false, false, List.of()))));
        assertThat(waitFor(runner.startScenario(scene.id(), null, 0, 1))).allMatch(e -> ApiExec.PASS.equals(e.verdict()));
        Wire wire = wires.getLast();
        assertThat(json.readTree(wire.body()).path("literal").asText()).isEqualTo("{{times}} {{run.missing}}");
        String header = wire.headers().get("Authorization").getFirst();
        var match = java.util.regex.Pattern.compile("timestamp=\"(\\d+)\"").matcher(header);
        assertThat(match.find()).isTrue();
        String expected = ApiSigner.authorizationValue("test-app", Long.parseLong(match.group(1)), "nonce",
                ApiSigner.sign("test-only-key", "test-app", Long.parseLong(match.group(1)), "nonce", wire.body()));
        assertThat(header).isEqualTo(expected);
    }

    @Test void literalRunValuesSurviveBasicAndBothQueryParameterPaths() throws Exception {
        String literal = "{{times}} {{run.missing}} 中文";
        response = wire -> "{\"literal\":\"" + literal + "\"}";
        var issue = repo.createCase(request("/issue", "{}", null));
        var basicAuth = new ApiRequestOptions.Auth("BASIC", null, "{{run.literal}}", "{{run.literal}}", null, null, null);
        var basic = repo.createCase(request("/basic-literal", "{}", options("JSON", basicAuth, null)));
        var params = repo.createCase(request("/params-literal", "{}", new ApiRequestOptions("JSON",
                List.of(new ApiRequestOptions.Param("q", "{{run.literal}}", true)), null, null)));
        var keyAuth = new ApiRequestOptions.Auth("API_KEY", null, null, null, "key", "{{run.literal}}", "QUERY");
        var key = repo.createCase(request("/key-literal", "{}", options("JSON", keyAuth, null)));
        var scene = repo.saveScenario(null, new ApiScenario(null, "literal-encodings", List.of(
                step("issue", issue, false, false, List.of(extraction("literal", "literal"))),
                step("basic", basic, false, false, null), step("params", params, false, false, null),
                step("key", key, false, false, null))));
        assertThat(waitFor(runner.startScenario(scene.id(), null, 0, 1))).allMatch(e -> ApiExec.PASS.equals(e.verdict()));
        String authorization = wires.get(1).headers().get("Authorization").getFirst();
        assertThat(new String(java.util.Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8))
                .isEqualTo(literal + ":" + literal);
        assertThat(java.net.URLDecoder.decode(wires.get(2).query(), StandardCharsets.UTF_8)).isEqualTo("q=" + literal);
        assertThat(java.net.URLDecoder.decode(wires.get(3).query(), StandardCharsets.UTF_8)).isEqualTo("key=" + literal);
        assertThat(wires.get(2).query()).doesNotContain("{{", "__sud_literal");
    }

    @Test void protectedSignatureQueryIsResolvedAfterParameterEncoding() throws Exception {
        repo.saveSignConfig(1, true, "test-app", "test-only-key", "stable-nonce", "game");
        var spec = request("/signature-param", "{\"t\":{{$timestamp}}}", new ApiRequestOptions("JSON", List.of(
                new ApiRequestOptions.Param("sig", "{{$auth.sign}}", true),
                new ApiRequestOptions.Param("mixed", "p-{{$auth.sign}}-s", true)), null, null));
        assertThat(runner.send(spec, null, null).verdict()).isEqualTo(ApiExec.PASS);
        Wire wire = wires.getFirst();
        long timestamp = json.readTree(wire.body()).path("t").asLong();
        String signature = ApiSigner.sign("test-only-key", "test-app", timestamp, "stable-nonce", wire.body());
        assertThat(wire.query()).isEqualTo("sig=" + signature + "&mixed=p-" + signature + "-s");
        assertThat(wire.headers().get("Authorization")).containsExactly(
                ApiSigner.authorizationValue("test-app", timestamp, "stable-nonce", signature));
    }

    @Test void nestedObjectRunValueIsValidatedAfterLiteralRestoration() throws Exception {
        response = wire -> "{\"obj\":{\"nested\":{\"x\":1},\"literal\":\"{{run.missing}}\"}}";
        var issue = repo.createCase(request("/issue-object", "{}", null));
        var consume = repo.createCase(request("/consume-object", "{\"payload\":{{run.obj}}}", options("JSON", auth("NONE", null), null)));
        var scene = repo.saveScenario(null, new ApiScenario(null, "nested-json", List.of(
                step("issue", issue, false, false, List.of(extraction("obj", "obj"))),
                step("consume", consume, false, false, null))));
        assertThat(waitFor(runner.startScenario(scene.id(), null, 0, 1))).allMatch(e -> ApiExec.PASS.equals(e.verdict()));
        assertThat(wires).hasSize(2);
        JsonNode payload = json.readTree(wires.getLast().body()).path("payload");
        assertThat(payload.path("nested").path("x").asInt()).isEqualTo(1);
        assertThat(payload.path("literal").asText()).isEqualTo("{{run.missing}}");
    }

    @Test void blankBodyNormalizationPrecedesSigningWhileRawWhitespaceIsPreserved() {
        repo.saveSignConfig(1, true, "test-app", "test-only-key", "stable-nonce", "game");
        for (String type : List.of("LEGACY", "JSON", "RAW")) {
            var options = "LEGACY".equals(type) ? null : options(type, null, null);
            assertThat(runner.send(request("/blank-" + type, " \n", options), null, null).verdict()).isEqualTo(ApiExec.PASS);
            Wire wire = wires.getLast();
            assertThat(wire.body()).isEqualTo("RAW".equals(type) ? " \n" : "");
            String header = wire.headers().get("Authorization").getFirst();
            var match = java.util.regex.Pattern.compile("timestamp=\"(\\d+)\"").matcher(header);
            assertThat(match.find()).isTrue();
            long timestamp = Long.parseLong(match.group(1));
            assertThat(header).isEqualTo(ApiSigner.authorizationValue("test-app", timestamp, "stable-nonce",
                    ApiSigner.sign("test-only-key", "test-app", timestamp, "stable-nonce", wire.body())));
        }
    }

    @Test void responseInspectionIncludesHeadersWithTheSameValueAsExtraction() {
        var headers = Map.of("X-Token", List.of("first", "second"), "Date", List.of("Tue, 13 Oct 2026 00:00:00 GMT"));
        var inspection = ApiResponseTools.inspect("not-json", headers);
        assertThat(inspection.validJson()).isFalse();
        var token = inspection.fields().stream().filter(f -> f.path().equals("header:X-Token")).findFirst().orElseThrow();
        assertThat(token.selectable()).isTrue();
        assertThat(token.type()).isEqualTo("string");
        var extracted = ApiResponseTools.extract("not-json", headers,
                List.of(new ApiExtractor("token", "HEADER", "x-token", true)), new LinkedHashMap<>());
        assertThat(token.valueJson()).isEqualTo(extracted.getFirst().valueJson()).isEqualTo("\"first, second\"");
        assertThat(inspection.fields().stream().filter(f -> f.path().equals("header:Date")).findFirst().orElseThrow().valueJson())
                .isEqualTo("\"Tue, 13 Oct 2026 00:00:00 GMT\"");
    }

    @Test void strictJsonRejectsMultipleRootValuesBeforeAnyNetworkRequest() {
        var spec = request("/never", "{\"a\":1} {\"b\":2}", options("JSON", null, null));
        assertThat(runner.prepare(spec, null).valid()).isFalse();
        assertThat(runner.send(spec, null, null).verdict()).isEqualTo(ApiExec.ERROR);
        assertThat(wires).isEmpty();
    }

    @Test void skippedProducerClearsItsInheritedExtractionBeforeIndependentConsumer() throws Exception {
        response = wire -> "{\"token\":\"old-token\"}";
        var issue = repo.createCase(request("/issue", "{}", null));
        ApiSpec disabledTemplate = request("/disabled", "{}", options("JSON", null, List.of(extraction("token", "token"))));
        var disabled = repo.createCase(new ApiSpec(null, disabledTemplate.name(), disabledTemplate.method(), disabledTemplate.url(),
                disabledTemplate.headers(), disabledTemplate.body(), null, "200", null, 2000, false, false,
                null, null, disabledTemplate.options()));
        var independent = repo.createCase(request("/independent", "{{run.token}}", options("RAW", null, null)));
        var scene = repo.saveScenario(null, new ApiScenario(null, "skip-clear", List.of(
                step("issue", issue, false, false, List.of(extraction("token", "token"))),
                step("disabled", disabled, false, false, null), step("independent", independent, false, true, null))));
        assertThat(waitFor(runner.startScenario(scene.id(), null, 0, 1)).stream().map(ApiExec::verdict))
                .containsExactly("PASS", "SKIP", "ERROR");
        assertThat(wires.stream().map(Wire::path)).containsExactly("/issue");
    }

    @Test void previewUsesTheActualRequestValidationAndKeepsMissingVariableDiagnostics() {
        for (String headers : List.of("{\"Host\":\"example.invalid\"}", "{\"X-Invalid\":\"line1\\nline2\"}")) {
            var invalid = new ApiSpec(null, "invalid-header", "GET", endpoint + "/never", headers, null,
                    null, null, null, 2000, false, true, null, null, null);
            assertThat(runner.prepare(invalid, null).valid()).isFalse();
        }
        var invalidUri = new ApiSpec(null, "invalid-uri", "GET", "http:///no-host", "{}", null,
                null, null, null, 2000, false, true, null, null, null);
        assertThat(runner.prepare(invalidUri, null).valid()).isFalse();
        repo.saveVars(1, List.of(), List.of(), List.of(new ApiVariables.Var("known", "resolved")));
        var preview = runner.prepare(request("/{{run.missing}}", "{{known}}", options("RAW", null, null)), null);
        assertThat(preview.valid()).isFalse();
        assertThat(preview.unresolved()).containsExactly("run.missing");
        assertThat(preview.warnings()).anyMatch(w -> w.contains("run.missing"));
        assertThat(preview.variables()).anyMatch(v -> "known".equals(v.get("name")));
        assertThat(preview.error()).contains("run.missing");
        assertThat(wires).isEmpty(); assertThat(repo.listRecentAdhoc(100)).isEmpty();
    }

    @Test void ordinaryBatchRollsBackExecutionWhenItsCounterUpdateFails() throws Exception {
        var spec = repo.createCase(request("/ordinary", "{}", null));
        jdbc.execute("CREATE TRIGGER reject_counter BEFORE UPDATE OF total_execs ON api_batch "
                + "BEGIN SELECT RAISE(ABORT, 'test-counter-error'); END");
        var batch = runner.startBatch("counter", List.of(spec.id()), 0, 1);
        assertThat(waitFor(batch)).isEmpty();
        assertThat(wires).hasSize(1);
        assertThat(repo.getBatch(batch.id()).totalExecs()).isZero();
        assertThat(repo.getBatch(batch.id()).passCount()).isZero();
    }

    @Test void responseInspectorAndAllAssertionSourcesExposePreciseActualValues() throws Exception {
        String id = "12345678901234567890123456789012345678";
        var inspection = ApiResponseTools.inspect("{\"id\":" + id + ",\"a.b\":\"quoted\",\"large\":\"" + "x".repeat(5000) + "\"}");
        assertThat(inspection.fields().getFirst().selectable()).isFalse();
        assertThat(inspection.fields().stream().filter(f -> f.path().equals("id")).findFirst().orElseThrow().valueJson()).isEqualTo(id);
        assertThat(ApiBodyAssert.readValues("{\"a.b\":\"quoted\"}", "/a.b").getFirst().asText()).isEqualTo("quoted");
        var rules = List.of(new ApiBodyAssert.Rule("id", "regex", "^123.*678$"),
                new ApiBodyAssert.Rule("header:X-Reply", "regex", "reply-\\d+"), new ApiBodyAssert.Rule("$elapsedMs", "gte", "0"));
        var judged = ApiBodyAssert.judgeAll("{\"id\":" + id + "}", rules, Map.of("x-reply", List.of("reply-123")), 5L);
        assertThat(judged).allMatch(ApiBodyAssert.Judged::ok);
        JsonNode saved = json.readTree(ApiBodyAssert.toJson(judged));
        assertThat(saved.get(0).path("actual").asText()).isEqualTo(id);
        assertThat(saved.get(1).path("source").asText()).isEqualTo("HEADER");
        assertThat(saved.get(2).path("actual").asText()).isEqualTo("5");
        assertThat(ApiBodyAssert.judgeAll("{}", List.of(new ApiBodyAssert.Rule("", "regex", "["))).getFirst().ok()).isFalse();
    }
}
