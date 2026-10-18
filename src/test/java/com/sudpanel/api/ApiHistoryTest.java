package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApiHistoryTest {
    @TempDir Path dir;
    ApiRepository repo;
    ApiHistoryRepository history;
    JdbcTemplate jdbc;
    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("history.db")));
        repo = new ApiRepository(jdbc); history = new ApiHistoryRepository(jdbc, repo);
    }
    private ApiExec execution(Long batch, Long caseId, String name, Integer status, String verdict, Long elapsed, String body, String metadata) {
        return repo.insertExec(new ApiExec(null, batch, caseId, name, 1, 1, "POST", "https://example.test/path", status, "0", "fixture", verdict,
                verdict.equals("FAIL") ? "fixture failed" : null, elapsed, body, "HTTP 200\nX-Fixture: one\n", "POST https://example.test/path\nContent-Type: application/json\n", "{\"fixture\":true}", null, "2026-10-14T00:00:00Z", "[]", metadata));
    }
    private ApiHistoryRepository.Query query(Long before, int limit) { return new ApiHistoryRepository.Query(before, limit, null, null, null, null, null, null, null, null, null); }
    private String metadata(long envId) { return "{\"schema\":1,\"envId\":" + envId + ",\"envName\":\"fixture env\",\"responseBodyTruncated\":false,\"requestBodyTruncated\":false}"; }

    @Test void fractionalHistoricalEnvironmentMustRemainUnknown() {
        var record = execution(null, 1L, "fractional-env", 200, "PASS", 1L, "{}", metadata(1).replace("\"envId\":1", "\"envId\":1.9"));
        assertThat(history.draft(record.id()).envId()).isNull();
    }

    @Test void paginationIsStableWhenNewRequestsArriveAndSummariesOmitAllBodies() {
        var ids = new ArrayList<Long>();
        for (int i = 0; i < 7; i++) ids.add(execution(null, 1L, "case-" + i, 200, "PASS", 1L, "{\"secret\":\"not-in-summary\"}", metadata(1)).id());
        var first = history.history(query(null, 3)); assertThat(first.items()).extracting(ApiExec::id).containsExactly(ids.get(6), ids.get(5), ids.get(4));
        execution(null, 1L, "newer", 200, "PASS", 1L, "{}", metadata(1));
        var second = history.history(query(first.nextBeforeId(), 3)); var third = history.history(query(second.nextBeforeId(), 3));
        assertThat(second.items()).extracting(ApiExec::id).containsExactly(ids.get(3), ids.get(2), ids.get(1)); assertThat(third.items()).extracting(ApiExec::id).containsExactly(ids.getFirst()); assertThat(third.nextBeforeId()).isNull();
        var summary = first.items().getFirst(); assertThat(summary.respBody()).isNull(); assertThat(summary.reqBody()).isNull(); assertThat(summary.respHeaders()).isNull(); assertThat(summary.reqHeaders()).isNull(); assertThat(summary.assertsResult()).isNull();
        assertThat(summary.metadata()).doesNotContain("responseBodyTruncated", "not-in-summary").contains("envId"); assertThat(history.detail(summary.id()).respBody()).contains("not-in-summary");
    }

    @Test void safeSummaryKeepsScenarioAndDataProvenanceButNeverValues() {
        execution(null, 1L, "scene", 200, "PASS", 1L, "{}", "{\"envId\":3,\"scenarioId\":4,\"scenarioName\":\"chain\",\"stepKey\":\"s1\",\"phase\":\"normal\",\"dataRowIndex\":2,\"dataIteration\":7,\"data\":{\"token\":\"FAKE_SECRET\"},\"extractions\":[{\"valueJson\":\"FAKE_SECRET\"}]}");
        var summary = history.history(query(null, 10)).items().getFirst(); assertThat(summary.metadata()).contains("scenarioId", "chain", "s1", "dataRowIndex", "dataIteration").doesNotContain("FAKE_SECRET", "extractions");
    }

    @Test void filtersUseBoundParametersAndSearchWildcardsAreLiteral() {
        var batch = repo.createBatch("fixture", "ONCE", List.of(1L), 0, 1);
        var target = execution(batch.id(), 5L, "literal%_fixture", 200, "FAIL", 2L, "{}", metadata(3));
        execution(null, 5L, "literalZZfixture", 200, "PASS", 2L, "{}", metadata(1));
        var result = history.history(new ApiHistoryRepository.Query(null, 10, "%_", "fail", "post", 5L, 3L, batch.id(), "2026-10-13T23:59:59+00:00", "2026-10-14T00:00:01Z", false));
        assertThat(result.items()).extracting(ApiExec::id).containsExactly(target.id());
        assertThat(history.history(new ApiHistoryRepository.Query(null, 10, "' OR 1=1 --", null, null, null, null, null, null, null, null)).items()).isEmpty();
    }

    @Test void malformedAndOldMetadataAreQueryableAndDoNotBreakEnvFilter() {
        execution(null, 1L, "old", 200, "PASS", 1L, "{}", "bad-json");
        execution(null, 1L, "null", 200, "PASS", 1L, "{}", null);
        var batch = repo.createBatch("bad-meta", "ONCE", List.of(1L), 0, 1);
        execution(batch.id(), 1L, "wrong-shape", 200, "PASS", 1L, "{}", "{\"envId\":{\"invalid\":1},\"envName\":[],\"scenarioId\":{},\"stepKey\":[]}");
        execution(batch.id(), 1L, "too-large-id", 200, "PASS", 1L, "{}", "{\"envId\":12345678901234567890123456789012345678}");
        assertThat(history.history(query(null, 10)).items()).hasSize(4);
        assertThat(history.stats(batch.id()).byEnv()).hasSize(1);
        assertThat(history.stats(batch.id()).byEnv().getFirst().key()).isEqualTo("unknown");
        assertThat(history.history(new ApiHistoryRepository.Query(null, 10, null, null, null, null, 1L, null, null, null, null)).items()).isEmpty();
    }

    @Test void invalidFiltersFailBeforeQuery() {
        assertThatThrownBy(() -> history.history(query(null, 101))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.history(new ApiHistoryRepository.Query(null, 10, null, null, null, null, null, 1L, null, null, true))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> history.history(new ApiHistoryRepository.Query(null, 10, null, null, null, null, null, null, "bad", null, null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void statsUseNearestRankAndExcludeErrorSkipNoResponseAndNegativeLatency() {
        var batch = repo.createBatch("fixture", "ONCE", List.of(1L, 2L), 0, 1);
        for (long i = 1; i <= 20; i++) execution(batch.id(), i <= 10 ? 1L : 2L, i <= 10 ? "one" : "two", 200, i == 20 ? "FAIL" : "PASS", i, "{}", metadata(i <= 10 ? 1 : 2));
        execution(batch.id(), 1L, "one", 200, "ERROR", 9999L, null, metadata(1));
        execution(batch.id(), 1L, "one", null, "PASS", 9999L, null, metadata(1));
        execution(batch.id(), 1L, "one", null, "SKIP", 0L, null, metadata(1));
        execution(batch.id(), 1L, "one", 200, "PASS", -1L, null, metadata(1));
        var stats = history.stats(batch.id()); assertThat(stats.totalCount()).isEqualTo(24); assertThat(stats.sampleCount()).isEqualTo(20);
        assertThat(stats.minMs()).isEqualTo(1); assertThat(stats.p50Ms()).isEqualTo(10); assertThat(stats.p95Ms()).isEqualTo(19); assertThat(stats.p99Ms()).isEqualTo(20); assertThat(stats.maxMs()).isEqualTo(20);
        assertThat(stats.byCase()).hasSize(2); assertThat(stats.byEnv()).hasSize(2); assertThat(stats.byCase().getFirst().sampleCount()).isEqualTo(10);
        assertThat(stats.reasons()).extracting(ApiHistoryRepository.ReasonCount::reason).contains("fixture failed");
    }

    @Test void emptyBatchHasNoInventedLatency() {
        var batch = repo.createBatch("empty", "ONCE", List.of(1L), 0, 1);
        var stats = history.stats(batch.id()); assertThat(stats.totalCount()).isZero(); assertThat(stats.sampleCount()).isZero(); assertThat(stats.p50Ms()).isNull(); assertThat(stats.byCase()).isEmpty();
    }

    @Test void comparisonKeepsHugeIntegerMissingNullTypeAndArrayOrderDistinct() {
        var l = execution(null, 1L, "left", 200, "PASS", 1L, "{\"id\":12345678901234567890123456789012345678,\"a\":null,\"type\":\"1\",\"array\":[1,2]}", metadata(1));
        var r = execution(null, 1L, "right", 200, "PASS", 1L, "{\"id\":12345678901234567890123456789012345679,\"type\":1,\"array\":[2,1],\"new\":null}", metadata(1));
        var compared = ApiHistoryCompare.compare(l, r, null); assertThat(compared.complete()).isTrue();
        assertThat(compared.differences()).anyMatch(d -> d.path().equals("/id") && d.left().equals("12345678901234567890123456789012345678") && d.right().endsWith("679"));
        assertThat(compared.differences()).anyMatch(d -> d.path().equals("/a") && d.kind().equals("REMOVED") && d.left().equals("null") && d.right() == null);
        assertThat(compared.differences()).anyMatch(d -> d.path().equals("/new") && d.kind().equals("ADDED") && d.right().equals("null"));
        assertThat(compared.differences()).anyMatch(d -> d.path().equals("/type") && d.kind().equals("TYPE"));
        assertThat(compared.differences()).anyMatch(d -> d.path().equals("/array/0"));
    }

    @Test void ignoredPointersHonorEscapesAndContainerSubtreesAndNumbersCompareByValue() {
        var l = execution(null, 1L, "left", 200, "PASS", 1L, "{\"a/b\":{\"~x\":1},\"stable\":1,\"dynamic\":{\"ts\":1}}", metadata(1));
        var r = execution(null, 1L, "right", 200, "PASS", 1L, "{\"a/b\":{\"~x\":2},\"stable\":1.0,\"dynamic\":{\"ts\":2}}", metadata(1));
        assertThat(ApiHistoryCompare.compare(l, r, List.of("/a~1b/~0x", "/dynamic")).differences()).isEmpty();
        assertThatThrownBy(() -> ApiHistoryCompare.compare(l, r, List.of("/bad~2"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiHistoryCompare.compare(l, r, java.util.Arrays.asList((String) null))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void legacyTruncationAndMissingHttpResponseCannotProveCompleteEquality() {
        var old = execution(null, 1L, "old", 200, "PASS", 1L, "{}", null);
        assertThat(ApiHistoryCompare.compare(old, old, List.of()).complete()).isFalse();
        var truncated = execution(null, 1L, "truncated", 200, "PASS", 1L, "{}\n…（已截断，原文 99999 字符）", metadata(1));
        assertThat(ApiHistoryCompare.compare(truncated, truncated, null).complete()).isFalse();
        var error = execution(null, 1L, "network", null, "ERROR", 1L, null, metadata(1));
        assertThat(ApiHistoryCompare.compare(error, error, null).complete()).isFalse();
    }

    @Test void trailingJsonIsComparedAsRawTextInsteadOfIgnoringSuffix() {
        var l = execution(null, 1L, "left", 200, "PASS", 1L, "{\"a\":1} garbage", metadata(1));
        var r = execution(null, 1L, "right", 200, "PASS", 1L, "{\"a\":1}", metadata(1));
        assertThat(ApiHistoryCompare.compare(l, r, null).differences()).anyMatch(d -> d.section().equals("BODY") && d.kind().equals("TYPE") && d.left().contains("garbage"));
    }

    @Test void historicalDraftUsesExactRawBodyAndKnownEnvironmentButDropsCredentialsAndAutomaticHeaders() {
        String body = " \n{\"id\":12345678901234567890123456789012345678}\n ";
        var e = repo.insertExec(new ApiExec(null, null, 1L, "fixture", 1, 1, "POST", "https://example.test", 200, "0", null, "PASS", null, 1L,
                "{}", "HTTP 200", "POST https://example.test\nAuthorization: Bearer FAKE_AUTH\nCookie: fake=value\nHost: wrong.test\nContent-Length: 1\nSign: old\nContent-Type: application/json\nX-Fixture: retained\n（未列出自动头）\n\n请求体（原样）：\n" + body,
                body, null, "2026-10-14T00:00:00Z", "[]", metadata(1)));
        var draft = history.draft(e.id()); assertThat(draft.envId()).isEqualTo(1); assertThat(draft.spec().id()).isNull(); assertThat(draft.spec().body()).isEqualTo(body);
        assertThat(draft.spec().headers()).contains("application/json", "retained").doesNotContain("FAKE_AUTH", "fake=value", "wrong.test", "old", "请求体");
        assertThat(draft.spec().options().auth().type()).isEqualTo("NONE"); assertThat(draft.spec().options().bodyType()).isEqualTo("RAW"); assertThat(draft.spec().options().transport().redirect()).isEqualTo("NEVER");
    }

    @Test void draftsRejectTruncatedBodyAndDoNotGuessMissingOrDeletedEnvironment() {
        var truncated = execution(null, 1L, "truncated", 200, "PASS", 1L, "{}", metadata(1).replace("\"requestBodyTruncated\":false", "\"requestBodyTruncated\":true"));
        assertThatThrownBy(() -> history.draft(truncated.id())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("截断");
        var old = execution(null, 1L, "old", 200, "PASS", 1L, "{}", null); assertThat(history.draft(old.id()).envId()).isNull();
        var deleted = execution(null, 1L, "deleted", 200, "PASS", 1L, "{}", metadata(999)); assertThat(history.draft(deleted.id()).envId()).isNull();
    }

    @Test void defaultJsonReportRedactsBodiesHeaderBodyCopyAssertionsAndKnownSecretReasonsWhileFullRetainsStoredData() {
        var batch = repo.createBatch("fixture export", "ONCE", List.of(1L), 0, 1);
        String body = "{\"password\":\"FAKE_PASSWORD\",\"id\":12345678901234567890123456789012345678}";
        var e = repo.insertExec(new ApiExec(null, batch.id(), 1L, "fixture", 1, 1, "POST", "https://example.test?access%5Ftoken=FAKE_QUERY", 200, "0", null, "FAIL", "token FAKE_RESPONSE and FAKE_PASSWORD", 2L,
                "{\"token\":\"FAKE_RESPONSE\"}", "HTTP 200\nSet-Cookie: FAKE_COOKIE\n", "POST https://example.test\nAuthorization: Bearer FAKE_AUTH\nContent-Type: application/json\n（未列出自动头）\n\n请求体：\n" + body,
                body, "error FAKE_QUERY", "2026-10-14T00:00:00Z", "[{\"path\":\"data.token\",\"op\":\"equals\",\"actual\":\"FAKE_RESPONSE\",\"expect\":\"FAKE_EXPECT\",\"why\":\"FAKE_RESPONSE\",\"ok\":false}]", metadata(1)));
        String safe = ApiRequestEngine.stringify(history.exportJson(batch.id(), false));
        assertThat(safe).doesNotContain("FAKE_PASSWORD", "FAKE_RESPONSE", "FAKE_QUERY", "FAKE_COOKIE", "FAKE_AUTH", "FAKE_EXPECT", "12345678901234567890123456789012345678").contains("REDACTED_REQUEST_BODY", "REDACTED_RESPONSE_BODY");
        String full = ApiRequestEngine.stringify(history.exportJson(batch.id(), true)); assertThat(full).contains("FAKE_PASSWORD", "FAKE_RESPONSE", "12345678901234567890123456789012345678");
        assertThat(history.detail(e.id()).reqBody()).isEqualTo(body);
    }

    @Test void junitIsParseableXmlWithCorrectFailErrorSkipCountsAndInvalidControlCharactersRemoved() throws Exception {
        var batch = repo.createBatch("fixture < & \"", "ONCE", List.of(1L), 0, 1);
        execution(batch.id(), 1L, "passed", 200, "PASS", 1250L, "{}", metadata(1));
        var fail = execution(batch.id(), 1L, "failed < &", 200, "FAIL", 20L, "{}", metadata(1));
        jdbc.update("UPDATE api_exec SET reason=? WHERE id=?", "bad < & \" \u0000 fixture", fail.id());
        execution(batch.id(), 1L, "error", null, "ERROR", 30L, null, metadata(1));
        execution(batch.id(), 1L, "skip", null, "SKIP", 0L, null, metadata(1));
        String xml = history.exportJunit(batch.id(), false);
        var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance(); factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        var doc = factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        var suite = (org.w3c.dom.Element) doc.getElementsByTagName("testsuite").item(0);
        assertThat(suite.getAttribute("tests")).isEqualTo("4"); assertThat(suite.getAttribute("failures")).isEqualTo("1"); assertThat(suite.getAttribute("errors")).isEqualTo("1"); assertThat(suite.getAttribute("skipped")).isEqualTo("1"); assertThat(suite.getAttribute("time")).isEqualTo("1.300");
        assertThat(doc.getElementsByTagName("testcase").getLength()).isEqualTo(4); assertThat(xml).doesNotContain("\u0000");
    }

    @Test void fiveHundredActualDifferencesAreCompleteButMoreAreFlaggedTruncated() {
        var left = new StringBuilder("{"); var right = new StringBuilder("{");
        for (int i = 0; i < 500; i++) { if (i > 0) { left.append(','); right.append(','); } left.append('"').append(i).append("\":0"); right.append('"').append(i).append("\":1"); }
        left.append('}'); right.append('}');
        var l = execution(null, 1L, "left", 200, "PASS", 1L, left.toString(), metadata(1)); var r = execution(null, 1L, "right", 200, "PASS", 1L, right.toString(), metadata(1));
        var exact = ApiHistoryCompare.compare(l, r, null); assertThat(exact.differences()).hasSize(500); assertThat(exact.complete()).isTrue();
        jdbc.update("UPDATE api_exec SET resp_body=? WHERE id=?", right.substring(0, right.length()-1) + ",\"extra\":1}", r.id());
        var more = ApiHistoryCompare.compare(l, history.detail(r.id()), null); assertThat(more.differences()).hasSize(500); assertThat(more.complete()).isFalse();
    }
}
