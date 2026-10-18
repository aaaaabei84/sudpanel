package com.sudpanel.backup;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.sudpanel.perf.PerfRepository;

class MaintenanceQualityArchiveTest {
    @TempDir Path dir;
    JdbcTemplate jdbc;
    MaintenanceService service;

    @BeforeEach void setup() {
        Path db = dir.resolve("quality.db");
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + db));
        new PerfRepository(jdbc);
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_loop_quality(loop_id INTEGER PRIMARY KEY,diagnostics_json TEXT NOT NULL)");
        jdbc.update("INSERT INTO perf_run(id,mode,status,start_time) VALUES(101,'NORMAL','COMPLETED','2020-01-01T00:00:00Z')");
        jdbc.update("INSERT INTO perf_loop(id,run_id,game_id,loop_no,status) VALUES(201,101,1,1,'OK')");
        jdbc.update("INSERT INTO perf_loop_quality VALUES(201,?)", "{\"status\":\"NOT_RECORDED\",\"fixture\":\"quality evidence\"}");
        service = new MaintenanceService(jdbc, db, new MaintenanceGate(), () -> false);
    }

    MaintenanceService.CleanupPreview preview() {
        return service.cleanupPreview(new MaintenanceService.CleanupRequest("PERF", "2025-01-01T00:00:00Z", true));
    }

    @Test void qualityEvidenceIsArchivedBeforeDeletionUsingItsLoopPrimaryKey() throws Exception {
        var preview = preview();
        assertThat(preview.counts().get("perf_loop_quality")).isEqualTo(1L);
        var cleaned = service.cleanupCommit(preview.token());
        String archive = Files.readString(service.archiveFile(cleaned.archiveId()));
        assertThat(archive).contains("perf_loop_quality", "quality evidence", "NOT_RECORDED");
        for (String table : new String[] {"perf_run", "perf_loop", "perf_loop_quality"}) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class)).isZero();
        }
    }

    @Test void changedQualityEvidenceInvalidatesThePreviewAndKeepsTheRun() {
        var preview = preview();
        jdbc.update("UPDATE perf_loop_quality SET diagnostics_json='{}' WHERE loop_id=201");
        assertThatThrownBy(() -> service.cleanupCommit(preview.token())).hasMessageContaining("已有变化");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perf_run", Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perf_loop_quality", Long.class)).isEqualTo(1L);
    }

    @Test void failedParentDeletionRollsBackQualityDeletion() {
        jdbc.execute("CREATE TRIGGER fixture_reject_run BEFORE DELETE ON perf_run BEGIN SELECT RAISE(ABORT,'fixture'); END");
        var preview = preview();
        assertThatThrownBy(() -> service.cleanupCommit(preview.token())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT diagnostics_json FROM perf_loop_quality WHERE loop_id=201", String.class))
            .contains("quality evidence");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perf_loop", Long.class)).isEqualTo(1L);
    }
    @Test void timelineAndConditionsArchiveUseTheirOwnKeysAndRollbackTogether() throws Exception {
        jdbc.update("INSERT INTO perf_run_event(run_id,type,stage,created_at,reason) VALUES(101,'WAIT_STARTED','ENV_WAIT','2020-01-01T00:00:00Z','ENV_UNREACHABLE')");
        jdbc.update("INSERT INTO perf_run_conditions(run_id,conditions_json) VALUES(101,'fixture-encrypted-placeholder')");
        var before=preview();assertThat(before.counts()).containsEntry("perf_run_event",1L).containsEntry("perf_run_conditions",1L);
        jdbc.execute("CREATE TRIGGER reject_facts_parent BEFORE DELETE ON perf_run BEGIN SELECT RAISE(ABORT,'fixture');END");
        assertThatThrownBy(()->service.cleanupCommit(before.token())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perf_run_event",Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perf_run_conditions",Long.class)).isEqualTo(1L);
        jdbc.execute("DROP TRIGGER reject_facts_parent");var clean=service.cleanupCommit(before.token());
        assertThat(Files.readString(service.archiveFile(clean.archiveId()))).contains("perf_run_event","ENV_UNREACHABLE","perf_run_conditions","fixture-encrypted-placeholder");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perf_run_event",Long.class)).isZero();assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perf_run_conditions",Long.class)).isZero();
    }
    @Test void newTimelineAfterCleanupPreviewInvalidatesDestructiveCommit(){
        var before=preview();jdbc.update("INSERT INTO perf_run_event(run_id,type,stage,created_at,reason) VALUES(101,'STOP_REQUESTED','USER','2020-01-01T00:00:00Z','USER_REQUEST')");
        assertThatThrownBy(()->service.cleanupCommit(before.token())).hasMessageContaining("已有变化");assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perf_run",Long.class)).isEqualTo(1L);
    }
}
