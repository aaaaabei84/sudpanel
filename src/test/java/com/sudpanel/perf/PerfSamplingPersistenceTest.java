package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.sudpanel.adb.AdbClient;
import com.sudpanel.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PerfSamplingPersistenceTest {
    @TempDir Path dir;JdbcTemplate jdbc;PerfRepository repo;long game,run,loop;
    @BeforeEach void setup(){jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("quality.db")));repo=new PerfRepository(jdbc);game=repo.upsertGame("fake","fake");run=repo.insertRun("SECRET-SERIAL","NORMAL",String.valueOf(game),1,5,1L);loop=repo.insertLoop(run,game,1);}
    @Test void migrationIsRepeatableLegacyRemainsUnknownAndQualityPersistsAcrossRestart(){
        jdbc.update("INSERT INTO perf_sample(loop_id,seq,ts)VALUES(?,0,'2026-10-14T00:00:00Z')",loop);
        assertThat(repo.samplesOf(loop).getFirst().get("quality")).isEqualTo(Map.of("state","NOT_RECORDED"));assertThat(repo.loopQuality(loop)).containsEntry("state","NOT_RECORDED");
        var collector=new PerfCollector(mock(AdbClient.class));var missing=collector.missing(42,"进程暂时消失",20).timed(1000,1000L);repo.insertSamples(loop,List.of(missing));
        var diagnostics=new PerfSamplingDiagnostics(null,new PerfDeviceHealth.Policy(null,null));diagnostics.sample(missing);diagnostics.completed(1020,1000,0);repo.saveLoopQuality(loop,diagnostics.summary(null));
        var reopened=new PerfRepository(jdbc);assertThat(reopened.loopQuality(loop)).containsEntry("state","RECORDED").containsEntry("sampleCount",1);assertThat(reopened.samplesOf(loop)).allMatch(sample->sample.get("wsThreadCount")==null);assertThat(reopened.samplesOf(loop).getLast().get("quality").toString()).contains("durationMs=20","offsetMs=1000");
    }
    @Test void qualityCascadesOnRunAndGameDeletionAndCannotReappearForRevokedLoop(){
        repo.saveLoopQuality(loop,Map.of("state","RECORDED"));repo.deleteRun(run);assertThat(jdbc.queryForObject("SELECT count(*) FROM perf_loop_quality",Long.class)).isZero();repo.saveLoopQuality(loop,Map.of("state","RECORDED"));assertThat(jdbc.queryForObject("SELECT count(*) FROM perf_loop_quality",Long.class)).isZero();
        run=repo.insertRun("fake","NORMAL",String.valueOf(game),1,5,1L);loop=repo.insertLoop(run,game,1);repo.saveLoopQuality(loop,Map.of("state","RECORDED"));repo.deleteGame(game);assertThat(jdbc.queryForObject("SELECT count(*) FROM perf_loop_quality",Long.class)).isZero();
    }
    @Test void exportAndHtmlIncludeConditionsButOmitPidAndUnexpectedRawMetadata() throws Exception {
        var c=new PerfCollector(mock(AdbClient.class));repo.insertSamples(loop,List.of(c.missing(42,"unavailable",600).timed(0,null)));
        jdbc.update("UPDATE perf_sample SET quality_json=? WHERE loop_id=?","{\"durationMs\":600,\"offsetMs\":0,\"pid\":42,\"secret\":\"DO-NOT-EXPORT\",\"sources\":{\"threads\":{\"status\":\"DENIED\",\"source\":\"SECRET-SERIAL\"}}}",loop);
        var health=new PerfDeviceHealth.Snapshot("2026-10-14T00:00:00Z",80,42.3,true,3,"DUMPSYS_BATTERY","DUMPSYS_THERMALSERVICE");var d=new PerfSamplingDiagnostics(health,new PerfDeviceHealth.Policy(40.0,2));d.sample(c.missing(42,"unknown",600).timed(0,null));d.completed(600,1000,0);repo.saveLoopQuality(loop,d.summary(health));
        var evidence=new PerfEvidenceRepository(jdbc);var exports=new PerfExportService(repo,evidence);var out=new ByteArrayOutputStream();exports.samples(run,game,loop,out);assertThat(out.toString(StandardCharsets.UTF_8)).contains("sample_duration_ms","threads=DENIED",",600,0,,").doesNotContain("DO-NOT-EXPORT","SECRET-SERIAL","pid");
        var report=new PerfReportService(repo,mock(com.sudpanel.config.AppProperties.class),evidence).html(run,game);assertThat(report).contains("采样条件与完整性","42.30℃","外部供电","thermal 3").doesNotContain("SECRET-SERIAL");
    }
    @Test void healthThresholdsAreCapturedAndExistingPlanNeverReadsNewThresholds() throws Exception {
        var fixture=new PerfFrozenPlanTest();fixture.dir=dir.resolve("frozen");java.nio.file.Files.createDirectory(fixture.dir);fixture.setup();fixture.repo.saveHealthPolicy(new PerfDeviceHealth.Policy(40.0,2));long run=fixture.start();var original=fixture.repo.plan(run);fixture.repo.saveHealthPolicy(new PerfDeviceHealth.Policy(50.0,5));assertThat(PerfDeviceHealth.Policy.of(original.settings())).isEqualTo(new PerfDeviceHealth.Policy(40.0,2));assertThat(fixture.repo.healthPolicy()).isEqualTo(new PerfDeviceHealth.Policy(50.0,5));
    }
    @Test void resumedPlanRestoresOriginalHealthPolicyAfterMutableSettingsChange() throws Exception {
        var fixture=new PerfFrozenPlanTest();fixture.dir=dir.resolve("resume-policy");java.nio.file.Files.createDirectory(fixture.dir);fixture.setup();
        fixture.repo.saveHealthPolicy(new PerfDeviceHealth.Policy(40.0,2));long id=fixture.start();String hash=fixture.repo.plan(id).hash();
        fixture.repo.saveHealthPolicy(new PerfDeviceHealth.Policy(55.0,5));fixture.repo.finishRun(id,"STOPPED");
        var owner=PerfRunService.class.getDeclaredField("runningRunId");owner.setAccessible(true);owner.set(fixture.service,null);
        fixture.service.resume(id);
        var states=PerfRunService.class.getDeclaredField("runs");states.setAccessible(true);var state=((Map<Long,PerfRunService.PerfRunState>)states.get(fixture.service)).get(id);
        assertThat(state.plan.hash()).isEqualTo(hash);assertThat(PerfDeviceHealth.Policy.of(state.plan.settings())).isEqualTo(new PerfDeviceHealth.Policy(40.0,2));
        assertThat(fixture.repo.healthPolicy()).isEqualTo(new PerfDeviceHealth.Policy(55.0,5));
    }
    @Test void failedHealthPolicySaveNeverLeavesOneThresholdChanged(){
        repo.saveHealthPolicy(new PerfDeviceHealth.Policy(40.0,2));
        jdbc.execute("CREATE TRIGGER reject_thermal_policy BEFORE UPDATE ON perf_setting WHEN NEW.key='perf_health_thermal_warn' BEGIN SELECT RAISE(ABORT,'fictional storage failure');END");
        assertThatThrownBy(()->repo.saveHealthPolicy(new PerfDeviceHealth.Policy(55.0,5))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(repo.healthPolicy()).isEqualTo(new PerfDeviceHealth.Policy(40.0,2));
    }
    @Test void confirmedChangeSkipsTrimEvenIfOldPidAppearsAgain() throws Exception {
        var fixture=new PerfFrozenPlanTest();fixture.dir=dir.resolve("trim");java.nio.file.Files.createDirectory(fixture.dir);fixture.setup();long id=fixture.start();var states=PerfRunService.class.getDeclaredField("runs");states.setAccessible(true);var state=((Map<Long,PerfRunService.PerfRunState>)states.get(fixture.service)).get(id);
        var d=new PerfSamplingDiagnostics(null,new PerfDeviceHealth.Policy(null,null));d.continuity().observe(new PerfCollector.ProcessProbe("DIFFERENT"),null);clearInvocations(fixture.adb);
        var method=PerfRunService.class.getDeclaredMethod("collectTrimmedBaseline",PerfRunService.PerfRunState.class,Integer.class,long.class,PerfSamplingDiagnostics.class);method.setAccessible(true);method.invoke(fixture.service,state,42,loop,d);verify(fixture.adb,never()).shell(anyString(),contains("send-trim-memory"),anyLong());
    }
    @Test void runSamplerSkipsUnknownOrChangedPidAndNeverSamplesOldTargetAfterConfirmedRestart() throws Exception {
        var fixture=new PerfFrozenPlanTest();fixture.dir=dir.resolve("sampler");java.nio.file.Files.createDirectory(fixture.dir);fixture.setup();long run=fixture.start();
        var states=PerfRunService.class.getDeclaredField("runs");states.setAccessible(true);var state=((Map<Long,PerfRunService.PerfRunState>)states.get(fixture.service)).get(run);
        var real=new PerfCollector(fixture.adb);var sample=real.missing(42,"test",1);var collector=mock(PerfCollector.class);when(collector.missing(anyInt(),anyString(),anyLong())).thenAnswer(call->real.missing(call.getArgument(0),call.getArgument(1),call.getArgument(2)));when(collector.probeProcess(anyString(),anyString(),eq(42),anyLong())).thenReturn(new PerfCollector.ProcessProbe("UNKNOWN"),new PerfCollector.ProcessProbe("PRESENT"),new PerfCollector.ProcessProbe("DIFFERENT"),new PerfCollector.ProcessProbe("PRESENT"));when(collector.sample(anyString(),eq(42),anyLong())).thenReturn(sample);
        var field=PerfRunService.class.getDeclaredField("collector");field.setAccessible(true);field.set(fixture.service,collector);var method=PerfRunService.class.getDeclaredMethod("continuousSample",PerfRunService.PerfRunState.class,int.class,PerfSamplingDiagnostics.class,long.class);method.setAccessible(true);var d=new PerfSamplingDiagnostics(null,new PerfDeviceHealth.Policy(null,null));
        var unknown=(PerfCollector.Sample)method.invoke(fixture.service,state,42,d,1000L);assertThat(unknown.pssKb()).isNull();verify(collector,never()).sample(anyString(),anyInt(),anyLong());method.invoke(fixture.service,state,42,d,1000L);verify(collector,times(1)).sample(anyString(),eq(42),anyLong());method.invoke(fixture.service,state,42,d,1000L);assertThat(d.continuity().changed()).isTrue();method.invoke(fixture.service,state,42,d,1000L);verify(collector,times(1)).sample(anyString(),eq(42),anyLong());
    }
}
