package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.sudpanel.api.ApiExec;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PerfEvidenceRepositoryTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;
    PerfRepository perf;
    PerfEvidenceRepository evidence;
    long runId, loopId;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("perf.db")));
        perf = new PerfRepository(jdbc);
        evidence = new PerfEvidenceRepository(jdbc);
        jdbc.update("INSERT INTO perf_game(id,name) VALUES(1,'fictional-game')");
        runId = perf.insertRun("fictional-device", "AI_ROOM", "1", 1, 5, 1L, null);
        loopId = perf.insertLoop(runId, 1, 1);
    }

    static ApiExec result(String verdict, String metadata) {
        return new ApiExec(null, null, 1L, "fictional-case", 0, 0, "POST", "http://127.0.0.1/example",
                200, "0", "success", verdict, "fictional-reason", 12L,
                "{\"game_round_id\":12345678901234567890123456789012345678}", "{}", "{\"Authorization\":\"fictional\"}",
                "{\"game_round_id\":\"12345678901234567890123456789012345678\"}", null,
                "2026-10-13T00:00:00Z", "[]", metadata);
    }

    @Test void repeatedMigrationAndRestartPreserveExactSnapshotsAndAttemptOrder() {
        long first = evidence.record(runId, loopId, 1L, 1, "推人机", result("FAIL", "{}"));
        long second = evidence.record(runId, loopId, 1L, 1, "推人机", result("PASS", "{}"));
        evidence = new PerfEvidenceRepository(jdbc);
        List<Map<String, Object>> steps = evidence.runSteps(runId);
        assertThat(steps).extracting(row -> row.get("id")).containsExactly(first, second);
        assertThat(steps).extracting(row -> row.get("verdict")).containsExactly("FAIL", "PASS");
        JsonNode exec = (JsonNode) evidence.detail(second).get("exec");
        assertThat(exec.path("reqBody").asText()).contains("12345678901234567890123456789012345678");
        assertThat(exec.path("reqHeaders").asText()).contains("fictional");
        assertThat(evidence.loopEvidence(loopId).get("recorded")).isEqualTo(true);
    }

    @Test void cleanupIsStructuredAndIndependentOfLoopSamplingStatus() {
        jdbc.update("UPDATE perf_loop SET status='OK' WHERE id=?", loopId);
        evidence.cleanup(runId, loopId, 1L, "FAILED", "清房失败", true);
        evidence = new PerfEvidenceRepository(jdbc);
        Map<?, ?> cleanup = (Map<?, ?>) evidence.loopEvidence(loopId).get("cleanup");
        assertThat(cleanup.get("status")).isEqualTo("FAILED");
        assertThat(cleanup.get("evidenceMissing")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT status FROM perf_loop WHERE id=?", String.class, loopId)).isEqualTo("OK");
    }

    @Test void missingHistoryDoesNotClaimCleanupSucceeded() {
        Map<String, Object> data = evidence.loopEvidence(loopId);
        assertThat(data.get("recorded")).isEqualTo(false);
        assertThat(((Map<?, ?>) data.get("cleanup")).get("status")).isEqualTo("NOT_RECORDED");
        assertThatThrownBy(() -> evidence.detail(999)).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test void truncationAndUnreadableEvidenceAreExplicit() {
        long id = evidence.record(runId, loopId, 1L, 1, "开局",
                result("PASS", "{\"responseBodyTruncated\":true}"));
        assertThat(evidence.detail(id).get("truncated")).isEqualTo(true);
        jdbc.update("UPDATE perf_step_exec SET exec_json='broken' WHERE id=?", id);
        assertThat(evidence.runSteps(runId).getFirst().get("evidenceUnreadable")).isEqualTo(true);
        assertThatThrownBy(() -> evidence.detail(id)).isInstanceOf(IllegalStateException.class);
    }

    @Test void runDeletionCascadesEvidenceInsideExistingRepositoryTransaction() {
        evidence.record(runId, loopId, 1L, 1, "开局", result("PASS", "{}"));
        evidence.cleanup(runId, loopId, 1L, "SUCCESS", "", false);
        perf.deleteRun(runId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM perf_step_exec", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM perf_loop_cleanup", Integer.class)).isZero();
    }

    @Test void withdrawnEnvironmentAttemptRemainsAuditableUntilRunDeletion() {
        evidence.record(runId, loopId, 1L, 1, "开局", result("ERROR", "{}"));
        perf.deleteLoop(loopId);
        assertThat(evidence.runSteps(runId)).hasSize(1);
        perf.deleteRun(runId);
        assertThat(evidence.runSteps(runId)).isEmpty();
    }
}
