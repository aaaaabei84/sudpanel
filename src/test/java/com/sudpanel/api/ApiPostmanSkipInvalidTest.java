package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudpanel.web.ApiImportController;
import com.sudpanel.web.GlobalExceptionHandler;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** All text and credentials are fixtures. Parsing never executes requests or scripts. */
class ApiPostmanSkipInvalidTest {
    @TempDir Path dir;
    final ObjectMapper json = new ObjectMapper();
    final String prefix = "{\"info\":{\"name\":\"skip-fixture\",\"schema\":\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"},";
    JdbcTemplate jdbc;
    ApiRepository repo;
    ApiImportService imports;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("skip.db")));
        repo = new ApiRepository(jdbc);
        imports = new ApiImportService(repo, jdbc);
    }

    String collection(String... items) { return prefix + "\"item\":[" + String.join(",", items) + "]}"; }
    String good(String name) { return "{\"name\":\"" + name + "\",\"request\":\"https://example.invalid/" + name + "\"}"; }
    String missing(String name) { return "{\"name\":\"" + name + "\",\"request\":{\"method\":\"GET\"}}"; }
    ApiInterchange.Input input(String source, Boolean skip) { return new ApiInterchange.Input("POSTMAN", source, null, null, null, skip); }
    ApiImportService.CommitRequest commit(String source, Boolean skip) { return new ApiImportService.CommitRequest("POSTMAN", source, null, null, 1L, null, skip); }
    void nothingWritten() {
        assertThat(repo.listCases()).isEmpty();
        assertThat(repo.listCaseGroups()).isEmpty();
        assertThat(repo.listEnvs()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_var", Integer.class)).isZero();
    }

    @Test void omittedNullFalseAndLegacyConstructorsRemainStrict() throws Exception {
        String source = collection(good("valid"), missing("missing"));
        var legacy = ApiInterchange.preview(new ApiInterchange.Input("POSTMAN", source, null));
        assertThat(legacy.errors()).anyMatch(s -> s.contains("缺少 URL"));
        assertThat(legacy.skippedCount()).isZero();
        assertThat(legacy.skippedRequests()).isEmpty();
        for (Boolean skip : Arrays.asList(null, false)) {
            var result = ApiInterchange.preview(input(source, skip));
            assertThat(result.requests()).hasSize(1);
            assertThat(result.errors()).hasSize(1);
            assertThat(result.losses()).anyMatch(loss -> loss.blocking() && loss.code().equals("REQUEST_UNSUPPORTED"));
            assertThatThrownBy(() -> imports.commit(commit(source, skip))).hasMessageContaining("导入未提交");
        }
        String oldJson = json.writeValueAsString(Map.of("format", "POSTMAN", "content", source));
        assertThat(json.readValue(oldJson, ApiInterchange.Input.class).skipInvalidRequests()).isNull();
        assertThat(json.readValue(oldJson, ApiImportService.CommitRequest.class).skipInvalidRequests()).isNull();
        assertThatThrownBy(() -> imports.commit(new ApiImportService.CommitRequest("POSTMAN", source, null, null, 1L))).hasMessageContaining("未提交");
        nothingWritten();
    }

    @Test void explicitSkipKeepsOrderedRawRequestsDirectoryIdentityAndOriginalSecretValues() throws Exception {
        String raw = "{ \"id\":12345678901234567890123456789012345678,\"credential\":\"{{credential}}\" }";
        String request = json.writeValueAsString(Map.of("name", "raw", "request", Map.of("method", "POST", "url", "{{endpoint}}/raw", "body", Map.of("mode", "raw", "raw", raw)), "event", List.of(Map.of("listen", "test", "script", Map.of("exec", List.of("throw new Error('INERT_ONLY');"))))));
        String source = prefix + "\"variable\":[{\"key\":\"endpoint\",\"value\":\"https://old.invalid\"},{\"key\":\"credential\",\"value\":\"OLD_FAKE\",\"type\":\"secret\"}],\"item\":[{\"name\":\"folder\",\"item\":[" + request + "," + missing("missing") + "]},{\"name\":\"folder\",\"item\":[" + good("second") + "]},{\"name\":\"empty\",\"item\":[]}]}";
        String environment = "{\"name\":\"env-fixture\",\"values\":[{\"key\":\"endpoint\",\"value\":\"https://example.invalid\",\"enabled\":true},{\"key\":\"credential\",\"value\":\"ORIGINAL_FAKE_SECRET\",\"enabled\":true,\"type\":\"secret\"}]}";
        var scopes = Map.of("credential", List.of("BODY"));
        var preview = ApiInterchange.preview(new ApiInterchange.Input("POSTMAN", source, environment, scopes, false, true));
        assertThat(preview.errors()).isEmpty();
        assertThat(preview.requests()).extracting(r -> r.spec().name()).containsExactly("raw", "second");
        assertThat(preview.requests().getFirst().spec().body()).isEqualTo(raw);
        assertThat(preview.requests().getFirst().spec().note()).contains("INERT_ONLY", "仅保存，未执行");
        assertThat(preview.requests().getFirst().spec().asserts()).isNull();
        assertThat(preview.skippedRequests()).containsExactly(new ApiInterchange.SkippedRequest("root/item/0/item/1", "missing", "缺少 URL"));
        assertThat(preview.skippedCount()).isEqualTo(1);
        assertThat(preview.losses()).filteredOn(loss -> loss.code().equals("REQUEST_SKIPPED")).allSatisfy(loss -> assertThat(loss.blocking()).isFalse());
        assertThat(preview.folders()).hasSize(4);
        assertThat(preview.folders()).extracting(ApiInterchange.ImportedFolder::requestCount).containsExactly(0, 1, 1, 0);
        assertThat(preview.variables().body()).containsExactly(new ApiVariables.Var("credential", "••••••", true));
        nothingWritten();
        var result = imports.commit(new ApiImportService.CommitRequest("POSTMAN", source, environment, null, 1L, scopes, true));
        assertThat(result.skippedRequests()).isEqualTo(preview.skippedRequests());
        assertThat(result.skippedCount()).isEqualTo(1);
        assertThat(result.caseIds()).hasSize(2);
        assertThat(result.groupIds()).hasSize(4);
        assertThat(result.envId()).isNotEqualTo(1);
        var reopened = new ApiRepository(jdbc);
        assertThat(reopened.getVars(result.envId()).body()).containsExactly(new ApiVariables.Var("credential", "ORIGINAL_FAKE_SECRET", true));
        assertThat(reopened.getVars(result.envId()).base()).containsExactly(new ApiVariables.Var("endpoint", "https://example.invalid"));
        assertThat(reopened.getVars(1).body()).isEmpty();
        assertThat(reopened.getCase(result.caseIds().getFirst()).body()).isEqualTo(raw);
        assertThat(reopened.getCase(result.caseIds().getFirst()).groupId()).isNotEqualTo(reopened.getCase(result.caseIds().getLast()).groupId());
        assertThat(source).contains("OLD_FAKE", "INERT_ONLY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_exec", Integer.class)).isZero();
    }

    @Test void conversionFailuresHaveIndividualPathsAndDoNotClaimBlockingLosses() {
        String source = collection(good("valid"), missing("missing"),
                "{\"name\":\"method\",\"request\":{\"url\":\"https://example.invalid\",\"method\":\"CONNECT\"}}",
                "{\"name\":\"file\",\"request\":{\"url\":\"https://example.invalid\",\"body\":{\"mode\":\"file\",\"file\":{\"src\":\"/never/read\"}}}}",
                "{\"name\":\"headers\",\"request\":{\"url\":\"https://example.invalid\",\"header\":[{\"key\":\"X-Test\",\"value\":\"1\"},{\"key\":\"x-test\",\"value\":\"2\"}]}}",
                "{\"name\":\"blank\",\"request\":\"  \"}");
        var preview = ApiInterchange.preview(input(source, true));
        assertThat(preview.errors()).isEmpty();
        assertThat(preview.skippedCount()).isEqualTo(5);
        assertThat(preview.skippedRequests()).extracting(ApiInterchange.SkippedRequest::name).containsExactly("missing", "method", "file", "headers", "blank");
        assertThat(preview.skippedRequests()).extracting(ApiInterchange.SkippedRequest::path).containsExactly("root/item/1", "root/item/2", "root/item/3", "root/item/4", "root/item/5");
        assertThat(preview.losses()).noneMatch(ApiInterchange.ImportLoss::blocking);
        assertThat(imports.commit(commit(source, true)).caseIds()).hasSize(1);
    }

    @Test void skippedImportNeverSendsAndExplicitExecutionKeepsActualRawBytes() throws Exception {
        var hits = new AtomicInteger(); var wire = new AtomicReference<byte[]>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", exchange -> {
            hits.incrementAndGet(); wire.set(exchange.getRequestBody().readAllBytes());
            byte[] response = "{\"ret_code\":0}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            String raw = "{ \"id\":12345678901234567890123456789012345678, \"text\":\"中文 {{credential}}\" }";
            String request = json.writeValueAsString(Map.of("name", "wire", "event", List.of(Map.of("listen", "test", "script", Map.of("exec", "throw new Error('INERT_WIRE');"))),
                    "request", Map.of("method", "POST", "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/echo", "body", Map.of("mode", "raw", "raw", raw))));
            String source = collection(request, missing("skipped"));
            String environment = "{\"values\":[{\"key\":\"credential\",\"value\":\"FICTIONAL_WIRE_SECRET\",\"type\":\"secret\",\"enabled\":true}]}";
            assertThat(ApiInterchange.preview(new ApiInterchange.Input("POSTMAN", source, environment, null, false, true)).errors()).isEmpty();
            var result = imports.commit(new ApiImportService.CommitRequest("POSTMAN", source, environment, null, 1L, null, true));
            assertThat(hits).hasValue(0);
            var execution = new ApiRunner(repo, new CodeDictionary(repo), "").send(repo.getCase(result.caseIds().getFirst()), result.envId(), null);
            assertThat(execution.verdict()).isEqualTo("PASS");
            assertThat(hits).hasValue(1);
            assertThat(wire.get()).isEqualTo(raw.replace("{{credential}}", "FICTIONAL_WIRE_SECRET").getBytes(StandardCharsets.UTF_8));
            assertThat(repo.getCase(result.caseIds().getFirst()).body()).isEqualTo(raw);
            assertThat(repo.getCase(result.caseIds().getFirst()).note()).contains("INERT_WIRE");
        } finally { server.stop(0); }
    }

    @Test void allSkippedCannotCreateAnEmptySuccessfulCollectionButStandaloneEnvironmentStillWorks() {
        String source = prefix + "\"variable\":[{\"key\":\"x\",\"value\":\"y\"}],\"item\":[" + missing("only") + ",{\"name\":\"empty\",\"item\":[]}]}";
        var preview = ApiInterchange.preview(input(source, true));
        assertThat(preview.requests()).isEmpty();
        assertThat(preview.skippedCount()).isEqualTo(1);
        assertThat(preview.errors()).anyMatch(s -> s.contains("没有有效请求"));
        assertThatThrownBy(() -> imports.commit(commit(source, true))).hasMessageContaining("没有有效请求");
        nothingWritten();
        var environment = imports.commit(commit("{\"name\":\"env-only\",\"values\":[{\"key\":\"x\",\"value\":\"y\"}]}", true));
        assertThat(environment.caseIds()).isEmpty();
        assertThat(environment.skippedCount()).isZero();
        assertThat(repo.getVars(environment.envId()).body()).containsExactly(new ApiVariables.Var("x", "y"));
    }

    @Test void malformedDocumentsFoldersVariablesAndMetadataRemainGlobalBlockers() {
        List<String> sources = List.of("{", "[]", collection(good("valid")) + "{}", collection(good("valid")).replace("v2.1.0", "v1.0.0"),
                collection(good("valid"), "null"), collection(good("valid"), "{\"name\":\"folder\",\"item\":{}}"),
                prefix + "\"variable\":{},\"item\":[" + good("valid") + "]}",
                prefix + "\"variable\":[{\"key\":\"nested\",\"value\":\"{{another}}\"}],\"item\":[" + good("valid") + "]}",
                prefix + "\"variable\":[{\"key\":\"run.protected\",\"value\":\"x\"}],\"item\":[" + good("valid") + "]}",
                collection(good("valid"), "{\"name\":\"broken-event\",\"event\":{},\"request\":\"https://example.invalid\"}"),
                prefix + "\"certificate\":{},\"item\":[" + good("valid") + "]}");
        for (String source : sources) {
            var preview = ApiInterchange.preview(input(source, true));
            assertThat(preview.errors()).as(source).isNotEmpty();
            assertThat(preview.skippedRequests()).isEmpty();
            assertThatThrownBy(() -> imports.commit(commit(source, true))).hasMessageContaining("未提交");
            nothingWritten();
        }
    }

    @Test void invalidEnvironmentOrMappingCannotBeHiddenBySkippingBadRequests() {
        String source = prefix + "\"variable\":[{\"key\":\"x\",\"value\":\"y\"}],\"item\":[" + good("valid") + "," + missing("missing") + "]}";
        var invalidMapping = Map.of("x", List.of("UNKNOWN"));
        var mapping = ApiInterchange.preview(new ApiInterchange.Input("POSTMAN", source, null, invalidMapping, null, true));
        assertThat(mapping.skippedCount()).isEqualTo(1);
        assertThat(mapping.errors()).anyMatch(s -> s.contains("变量至少映射"));
        assertThatThrownBy(() -> imports.commit(new ApiImportService.CommitRequest("POSTMAN", source, null, null, 1L, invalidMapping, true))).hasMessageContaining("未提交");
        for (String environment : List.of("{", "{\"values\":{}}")) {
            assertThat(ApiInterchange.preview(new ApiInterchange.Input("POSTMAN", source, environment, null, null, true)).errors()).isNotEmpty();
            assertThatThrownBy(() -> imports.commit(new ApiImportService.CommitRequest("POSTMAN", source, environment, null, 1L, null, true))).hasMessageContaining("未提交");
        }
        nothingWritten();
    }

    @Test void sourceRequestCountIncludingSkippedOnesCannotBypassTheCapacityLimit() {
        var items = new ArrayList<String>(); items.add(good("valid"));
        for (int i = 0; i < 999; i++) items.add(missing("bad" + i));
        var boundary = ApiInterchange.preview(input(collection(items.toArray(String[]::new)), true));
        assertThat(boundary.errors()).isEmpty();
        assertThat(boundary.requests()).hasSize(1);
        assertThat(boundary.skippedCount()).isEqualTo(999);
        items.add(missing("over")); String oversized = collection(items.toArray(String[]::new));
        assertThat(ApiInterchange.preview(input(oversized, true)).errors()).anyMatch(s -> s.contains("1000个请求"));
        assertThatThrownBy(() -> imports.commit(commit(oversized, true))).hasMessageContaining("1000个请求");
        nothingWritten();
    }

    @Test void noteDirectoryDepthAndTextBudgetsStayGlobal() {
        String hugeNote = prefix + "\"event\":[{\"script\":{\"exec\":\"" + "x".repeat(1_000_000) + "\"}}],\"item\":[" + String.join(",", Collections.nCopies(10, good("valid"))) + "," + missing("missing") + "]}";
        var folders = new ArrayList<String>(); folders.add(good("valid"));
        for (int i = 0; i < 2000; i++) folders.add("{\"name\":\"f" + i + "\",\"item\":[]}");
        String deep = good("valid"); for (int i = 0; i < 34; i++) deep = "{\"name\":\"nested\",\"item\":[" + deep + "]}";
        var sources = List.of(hugeNote, collection(folders.toArray(String[]::new)), collection(deep), "x".repeat(2_000_001));
        var expected = List.of("备注累计", "目录最多2000", "32层", "200 万字符");
        for (int i = 0; i < sources.size(); i++) {
            String source = sources.get(i), message = expected.get(i);
            assertThat(ApiInterchange.preview(input(source, true)).errors()).anyMatch(s -> s.contains(message));
            assertThatThrownBy(() -> imports.commit(commit(source, true))).hasMessageContaining(message);
            nothingWritten();
        }
    }

    @Test void requestParameterCapacityCannotBeConvertedIntoASkippedRequest() throws Exception {
        var rows = Collections.nCopies(201, Map.of("key", "x", "value", "y"));
        var requests = List.of(Map.of("url", Map.of("raw", "https://example.invalid", "query", rows)),
                Map.of("url", "https://example.invalid", "body", Map.of("mode", "urlencoded", "urlencoded", rows)));
        for (var request : requests) {
            String source = collection(good("valid"), json.writeValueAsString(Map.of("name", "too-many-parameters", "request", request)));
            var preview = ApiInterchange.preview(input(source, true));
            assertThat(preview.errors()).anyMatch(s -> s.contains("参数最多 200 条"));
            assertThat(preview.skippedRequests()).isEmpty();
            assertThatThrownBy(() -> imports.commit(commit(source, true))).hasMessageContaining("参数最多 200 条");
            nothingWritten();
        }
    }

    @Test void commitReparsesThePolicyAndDoesNotTrustAnEarlierSuccessfulPreview() {
        String source = collection(good("valid"), missing("bad"));
        assertThat(ApiInterchange.preview(input(source, true)).errors()).isEmpty();
        assertThatThrownBy(() -> imports.commit(commit(source, false))).hasMessageContaining("缺少 URL");
        nothingWritten();
        String changed = collection(good("valid"), missing("bad"), "{\"name\":\"broken-folder\",\"item\":0}");
        assertThatThrownBy(() -> imports.commit(commit(changed, true))).hasMessageContaining("分组item需要数组");
        nothingWritten();
    }

    @Test void storageFailureStillRollsBackAllValidRequestsEnvironmentVariablesAndFolders() {
        var failing = new ApiRepository(jdbc) {
            int writes;
            @Override public ApiSpec createCase(ApiSpec spec) {
                if (++writes == 2) throw new IllegalArgumentException("fixture storage failure");
                return super.createCase(spec);
            }
        };
        var service = new ApiImportService(failing, jdbc);
        String source = prefix + "\"variable\":[{\"key\":\"x\",\"value\":\"y\"}],\"item\":[" + good("first") + "," + missing("skipped") + "," + good("second") + "]}";
        assertThatThrownBy(() -> service.commit(commit(source, true))).hasMessageContaining("fixture storage failure");
        nothingWritten();
    }

    @Test void httpBindingExposesExactSkipReceiptAndDefaultPayloadStillRejects() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new ApiImportController(imports)).setControllerAdvice(new GlobalExceptionHandler()).build();
        String source = collection(good("valid"), missing("bad"));
        mvc.perform(post("/api/apitest/import/preview").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input(source, true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errors").isEmpty()).andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.skippedCount").value(1)).andExpect(jsonPath("$.skippedRequests[0].path").value("root/item/1"));
        mvc.perform(post("/api/apitest/import/commit").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("format", "POSTMAN", "content", source))))
                .andExpect(status().isBadRequest());
        nothingWritten();
        mvc.perform(post("/api/apitest/import/commit").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(commit(source, true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.caseIds.length()").value(1)).andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.skippedRequests[0].name").value("bad")).andExpect(jsonPath("$.skippedRequests[0].reason").value("缺少 URL"));
    }

    @Test void skipOptionCannotChangeCurlOrOpenApiParsing() {
        for (var input : List.of(new ApiInterchange.Input("CURL", "curl https://example.invalid", null, null, null, true),
                new ApiInterchange.Input("OPENAPI", "{}", null, null, null, true))) {
            assertThat(ApiInterchange.preview(input).errors()).anyMatch(s -> s.contains("仅支持 Postman"));
        }
        nothingWritten();
    }
}
