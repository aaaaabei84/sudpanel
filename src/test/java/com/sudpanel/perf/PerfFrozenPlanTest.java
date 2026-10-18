package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sudpanel.adb.*;
import com.sudpanel.api.*;
import com.sudpanel.config.AppProperties;
import com.sudpanel.locator.UiLocator;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PerfFrozenPlanTest {
    @TempDir Path dir;
    JdbcTemplate jdbc;PerfRepository repo;ApiRepository api;ApiRunner runner;AdbClient adb;
    CalibrationService calibration;PerfRunService service;Map<Long,ApiSpec> cases;ExecutorService executor;
    long game;
    @BeforeEach void setup() throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("perf.db")));repo=new PerfRepository(jdbc);new PerfEvidenceRepository(jdbc);
        game=repo.upsertGame("fictional game","fictional");repo.updateGameIds(game,"fictional-mg","fictional-app","fictional-room",1,3,null,15,null,null);
        api=mock(ApiRepository.class);runner=mock(ApiRunner.class);adb=mock(AdbClient.class);calibration=mock(CalibrationService.class);
        cases=new LinkedHashMap<>();String[] names={"批量加入","游戏开始","游戏结束","房间清理"};
        for(int i=0;i<4;i++)cases.put((long)i+1,new ApiSpec((long)i+1,names[i],"POST","http://127.0.0.1/fake","{}","{\"data\":{\"user_infos\":[{}]}}",null,"200","",1000,false,true,null,null));
        when(api.listCases()).thenAnswer(call->List.copyOf(cases.values()));when(api.getCase(anyLong())).thenAnswer(call->cases.get(call.getArgument(0)));
        when(api.readSnapshot(any())).thenAnswer(call->((Supplier<?>)call.getArgument(0)).get());
        var env=new ApiRequestEngine.Environment(4,"fictional env","2026-10-14T00:00:00Z",ApiVariables.VarSet.empty(),new ApiSigner.Config(false,"fictional-app","secret-do-not-export",null,null),true,"DIRECT");
        when(runner.snapshotEnvironment(any(),isNull())).thenReturn(env);
        when(adb.devices()).thenReturn(List.of(new Device("fake","device","fake")));
        when(calibration.snapshotResources()).thenReturn(Map.of("perf_package","com.fictional.game"));
        when(calibration.snapshotCoords("fake")).thenReturn(Map.of("fictional",new int[]{1,2}));
        when(adb.shell(anyString(),anyString(),anyLong())).thenReturn(new AdbClient.ExecResult(0,"package:fictional.apk",""));
        when(adb.foregroundPackage("fake")).thenReturn("com.fictional.game");when(adb.isScreenOn("fake")).thenReturn(true);
        var props=mock(AppProperties.class);when(props.perfSampleIntervalMs()).thenReturn(1000L);when(props.perfMaxConsecutiveInvalid()).thenReturn(3);
        service=new PerfRunService(adb,mock(UiLocator.class),mock(PerfCollector.class),repo,mock(PerfJudge.class),props,calibration,runner,api,new PerfEvidenceRepository(jdbc));
        var field=PerfRunService.class.getDeclaredField("executor");field.setAccessible(true);executor=mock(ExecutorService.class);field.set(service,executor);
    }
    long start(){return ((Number)service.start("fake","NORMAL",List.of(game),2,5,1L,null).get("runId")).longValue();}
    @Test void encryptedPersistentSnapshotIsExactAndPublicSummaryHasNoCredentials() throws Exception {
        long run=start();var plan=repo.plan(run);
        String stored=jdbc.queryForObject("SELECT plan_json FROM perf_run_plan WHERE run_id=?",String.class,run);
        assertThat(stored).doesNotContain("secret-do-not-export","user_infos","fictional-room");
        var restarted=new PerfRepository(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("perf.db"))));
        assertThat(restarted.plan(run).hash()).isEqualTo(plan.hash());
        assertThat(repo.planSummary(run).toString()).doesNotContain("secret-do-not-export","user_infos","fictional-room","com.fictional.game");
        Files.delete(dir.resolve(ApiPlanVault.KEY_FILE_NAME));
        assertThatThrownBy(()->restarted.plan(run)).hasMessageContaining("密钥");
    }
    @Test void preflightBlocksBeforeCreatingRunOrChangingDevice() {
        when(adb.devices()).thenReturn(List.of(new Device("fake","unauthorized","fake")));
        assertThatThrownBy(this::start).hasMessageContaining("预检");
        assertThat(repo.listRuns()).isEmpty();verifyNoInteractions(executor);verify(adb,never()).tap(anyString(),anyInt(),anyInt());verify(adb,never()).setStayOn(anyString(),anyBoolean());
    }
    @Test void executingAndRestartResumingUseOriginalPlanAndSeparateCookieScope() throws Exception {
        long run=start();PerfExecutionPlan original=repo.plan(run);
        var runsField=PerfRunService.class.getDeclaredField("runs");runsField.setAccessible(true);
        var state=((Map<Long,PerfRunService.PerfRunState>)runsField.get(service)).get(run);
        repo.updateGameIds(game,"changed","changed","changed",64,0,null,7200,null,"{\"changed\":true}");repo.putSetting(PerfRepository.SETTING_AI_END_KEYWORD,"changed");
        when(api.getCase(anyLong())).thenThrow(new AssertionError("must not reread mutable case"));
        when(runner.executeWithEnvironment(any(),any(),anyMap(),isNull(),eq(0),eq(0))).thenReturn(PerfEvidenceRepositoryTest.result("PASS","{}"));
        var push=PerfRunService.class.getDeclaredMethod("pushCase",PerfRunService.PerfRunState.class,long.class,Map.class,String.class,Class.forName("com.sudpanel.perf.PerfRunService$GamePushPlan"));push.setAccessible(true);
        push.invoke(service,state,2L,Map.of(),"开局",null);
        verify(runner).executeWithEnvironment(eq(original.requests().get("开局").spec()),argThat(env->("perf-run-"+run).equals(env.cookieScope())&&env.sign().appSecret().equals("secret-do-not-export")),anyMap(),isNull(),eq(0),eq(0));
        assertThat(state.plan.games().get(game).get("roomId")).isEqualTo("fictional-room");assertThat(state.plan.coordinate("fictional")).containsExactly(1,2);
        var owner=PerfRunService.class.getDeclaredField("runningRunId");owner.setAccessible(true);owner.set(service,null);repo.finishRun(run,"STOPPED");
        service.resume(run);assertThat(((Map<Long,PerfRunService.PerfRunState>)runsField.get(service)).get(run).plan.hash()).isEqualTo(original.hash());
        verify(runner,times(2)).resetCookieScope("perf-run-"+run);
    }
    @Test void legacyResumeRequiresExplicitCurrentSnapshotAndDoesNotInventOldLoopSource() {
        long run=repo.insertRun("fake","NORMAL",String.valueOf(game),2,5,1L);long loop=repo.insertLoop(run,game,1);repo.finishRun(run,"STOPPED");
        assertThatThrownBy(()->service.resume(run)).hasMessageContaining("旧运行");assertThat(repo.getRun(run).get("status")).isEqualTo("STOPPED");
        service.resume(run,"CAPTURE_CURRENT");assertThat(repo.plan(run).origin()).isEqualTo("RESUME_CURRENT");assertThat(repo.getLoop(loop).get("planHash")).isNull();
    }
    @Test void environmentStrictPreflightUsesRequestOverrideAndFrozenJudgeLimitsSurviveChanges() throws Exception {
        var old=cases.get(1L);cases.put(1L,new ApiSpec(old.id(),old.name(),old.method(),old.url(),old.headers(),"{\"missing\":\"{{typo}}\"}",null,"200","",1000,false,true,null,null));
        assertThatThrownBy(this::start).hasMessageContaining("预检");assertThat(repo.listRuns()).isEmpty();
        var loose=new ApiRequestOptions(null,null,null,null,null,null,false);
        cases.put(1L,new ApiSpec(old.id(),old.name(),old.method(),old.url(),old.headers(),"{\"missing\":\"{{typo}}\"}",null,"200","",1000,false,true,null,null,loose));
        var propsField=PerfRunService.class.getDeclaredField("props");propsField.setAccessible(true);var props=(AppProperties)propsField.get(service);when(props.perfCpuThreshold()).thenReturn(12.0);
        long run=start();when(props.perfCpuThreshold()).thenReturn(99.0);assertThat(repo.plan(run).judgeProperties(props).perfCpuThreshold()).isEqualTo(12.0);
    }
    @Test void failedExplicitResumeRollsBackSnapshotDeletionAndRunStatus() {
        long run=repo.insertRun("fake","NORMAL",String.valueOf(game),2,5,1L);long loop=repo.insertLoop(run,game,1);jdbc.update("UPDATE perf_loop SET status='INTERRUPTED' WHERE id=?",loop);repo.finishRun(run,"STOPPED");
        jdbc.execute("CREATE TRIGGER reject_resume BEFORE UPDATE OF status ON perf_run WHEN NEW.status='RUNNING' BEGIN SELECT RAISE(ABORT,'fictional write failure');END");
        assertThatThrownBy(()->service.resume(run,"CAPTURE_CURRENT")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(repo.plan(run)).isNull();assertThat(repo.getLoop(loop)).containsEntry("status","INTERRUPTED");assertThat(repo.getRun(run)).containsEntry("status","STOPPED");verify(runner,never()).resetCookieScope(anyString());
    }
    @Test void settlementWithoutActualRosterRemainsUnknownAndUsesCapturedEnvironmentAndPersistsSummary() {
        long run=start(),loop=repo.insertLoop(run,game,1);var plan=repo.plan(run);repo.markLoopPlan(loop,plan.hash());
        String round="12345678901234567890123456789012345678";var window=new PerfLoopLogWindow("boundary");window.accept("boundary\nnew gameRoundId="+round);repo.markLoopRoundEvidence(loop,window);
        jdbc.update("UPDATE perf_loop SET start_time='2026-10-14T00:00:00Z',end_time='2026-10-14T00:01:00Z' WHERE id=?",loop);
        var evidence=new PerfEvidenceRepository(jdbc);
        var push=new ApiExec(null,null,1L,"push",1,1,"POST","http://127.0.0.1/fake",200,"0","ok","PASS",null,1L,"{}","{}","{}","{\"data\":{\"user_infos\":[{\"uid\":\"A\"},{\"uid\":\"B\"}]}}",null,"2026-10-14T00:00:00Z",null,"{}");evidence.record(run,loop,game,1,"推人机",push);
        var query=new ApiSpec(99L,"query","POST","http://127.0.0.1/fake","{}","{\"game_round_id\":0}",null,"200","",1000,false,true,null,null);when(api.getCase(99L)).thenReturn(query);
        String body="{\"code\":0,\"data\":{\"id\":\""+round+"\",\"room\":\"fictional-room\",\"game\":\"fictional-mg\",\"players\":[{\"uid\":\"A\"},{\"uid\":\"B\"}],\"time\":\"2026-10-14T00:00:30Z\"}}";
        var response=new ApiExec(null,null,99L,"query",1,1,"POST","http://127.0.0.1/fake",200,"0","ok","PASS",null,1L,body,"{}","{}","{}",null,"2026-10-14T00:00:30Z",null,"{}");
        when(runner.executeWithEnvironment(any(),any(),anyMap(),isNull(),eq(0),eq(0))).thenReturn(response);
        var rules=new PerfSettlementService.Rules("data","id","room","game","players","uid","time","ISO","code",List.of("0"),List.of("202"),List.of("404"),"EXACT",0L);
        var settlement=new PerfSettlementService(repo,evidence,api,runner);var checked=settlement.check(loop,new PerfSettlementService.Request(99,rules));assertThat(checked).containsEntry("status","UNKNOWN").containsEntry("evidenceMissing",false);
        assertThat(repo.settlementChecks(loop)).hasSize(1);assertThat(evidence.runSteps(run)).hasSize(2);
        verify(runner).executeWithEnvironment(argThat(spec->spec.id()==99&&spec.body().contains(round)),argThat(env->env.envId()==4&&("perf-run-"+run).equals(env.cookieScope())),anyMap(),isNull(),eq(0),eq(0));
        window.accept("boundary\nnew gameRoundId="+round+"\nnew gameRoundId=OTHER");repo.markLoopRoundEvidence(loop,window);assertThatThrownBy(()->settlement.check(loop,new PerfSettlementService.Request(99,rules))).hasMessageContaining("冲突");
    }

}
