package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sudpanel.adb.AdbClient;
import com.sudpanel.api.ApiPlanVault;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PerfLifecycleContextTest {
    @TempDir Path dir;
    PerfFrozenPlanTest f;
    @BeforeEach void setup()throws Exception{
        f=new PerfFrozenPlanTest();f.dir=dir;f.setup();
        when(f.adb.shell(anyString(),anyString(),anyLong())).thenAnswer(call->{String command=call.getArgument(1);String text=command.contains("wm size")?"Fixture Model\nPhysical size: 1080x2400\nSurfaceOrientation: 0":command.contains("ro.build.version.release")?"16\n2026-10-01\nfixture-build":command.startsWith("dumpsys package")?"versionName=1.0\nversionCode=7":"package:fixture";return new AdbClient.ExecResult(0,text,"");});
    }
    @Test void effectiveOverrideAndRotationAreRequiredAndUnknownNeverMatches(){
        var layout=PerfDeviceLayout.parse("Model\nPhysical size: 1080x2400\nOverride size: 720x1600\nSurfaceOrientation: 1");
        assertThat(layout).isEqualTo(new PerfDeviceLayout("Model",720,1600,1));assertThat(layout.known()).isTrue();
        assertThat(layout.matches(new PerfDeviceLayout("Model",720,1600,0))).isFalse();
        assertThat(layout.matches(new PerfDeviceLayout("Other",720,1600,1))).isFalse();
        assertThat(PerfDeviceLayout.parse("Model\nPhysical size: 720x1600").known()).isFalse();
        assertThat(PerfDeviceLayout.unknown().matches(PerfDeviceLayout.unknown())).isFalse();
    }
    @Test void legacyCoordinatesAreUnknownAndRebindingClearsOtherLayoutCoordinatesTransactionally(){
        var groups=new ButtonGroupRepository(f.jdbc);long group=groups.createGroup("fixture",null);groups.upsertCoord(group,"app_common_self_in",10,20,"manual");
        assertThat(groups.layout("group:"+group).known()).isFalse();
        var layout=new PerfDeviceLayout("Model",720,1600,0);groups.bindLayout("group:"+group,layout);assertThat(groups.coordsOf(group)).isEmpty();
        groups.upsertCoord(group,"app_common_self_in",30,40,"manual");
        f.jdbc.execute("CREATE TRIGGER reject_layout BEFORE UPDATE ON perf_coordinate_profile BEGIN SELECT RAISE(ABORT,'fixture');END");
        assertThatThrownBy(()->groups.bindLayout("group:"+group,new PerfDeviceLayout("Model",720,1600,1))).isInstanceOf(RuntimeException.class);
        assertThat(groups.coordsOf(group).get("app_common_self_in")).containsExactly(30,40);assertThat(new ButtonGroupRepository(f.jdbc).layout("group:"+group)).isEqualTo(layout);
        f.jdbc.execute("DROP TRIGGER reject_layout");groups.deleteGroup(group);assertThat(groups.layout("group:"+group).known()).isFalse();
    }
    @Test void conditionsStayEncryptedAcrossRestartAndUnknownHistoryIsNotComparable()throws Exception{
        long run=f.start();String text=f.jdbc.queryForObject("SELECT conditions_json FROM perf_run_conditions WHERE run_id=?",String.class,run);
        assertThat(text).doesNotContain("Fixture Model","fixture-build","fake","rosterIdentity");
        var reopened=new PerfRepository(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("perf.db"))));
        assertThat(reopened.facts().summary(run)).containsEntry("state","KNOWN").containsEntry("appVersion","1.0");
        assertThat(reopened.facts().summary(run).toString()).doesNotContain("deviceIdentity","rosterIdentity","fictional-room","secret-do-not-export");
        long legacy=reopened.insertRun("other","NORMAL",String.valueOf(f.game),1,5,1L);
        assertThat(reopened.facts().summary(legacy)).containsEntry("state","NOT_RECORDED");assertThat(reopened.facts().compare(legacy,legacy)).containsEntry("comparable",false);
        Files.delete(dir.resolve(ApiPlanVault.KEY_FILE_NAME));assertThatThrownBy(()->reopened.facts().summary(run)).hasMessageContaining("无法读取");
    }
    @Test void unknownCurrentDeviceMarksResumeConditionsMixedWithoutOverwritingOriginal(){
        long run=f.start();String before=f.jdbc.queryForObject("SELECT conditions_json FROM perf_run_conditions WHERE run_id=?",String.class,run);
        assertThat(f.repo.facts().changedDevice(run,Map.of("deviceIdentity","changed"))).isTrue();
        assertThat(f.repo.facts().summary(run)).containsEntry("state","MIXED").containsEntry("appVersion","1.0").containsEntry("comparable",false);
        assertThat(f.jdbc.queryForObject("SELECT conditions_json FROM perf_run_conditions WHERE run_id=?",String.class,run)).isEqualTo(before);
    }
    @Test void failedConditionsWriteRollsBackRunAndPlanTogether(){
        f.jdbc.execute("CREATE TRIGGER reject_conditions BEFORE INSERT ON perf_run_conditions BEGIN SELECT RAISE(ABORT,'fixture');END");
        assertThatThrownBy(f::start).isInstanceOf(RuntimeException.class);
        for(String table:List.of("perf_run","perf_run_plan","perf_run_conditions"))assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
    }
    @Test void timelinePersistsOrderDurationsAndInterruptedRunsDoNotRevive(){
        long run=f.start();var facts=f.repo.facts();facts.event(run,PerfRunFacts.Type.WAIT_STARTED,"ENV_WAIT",1,2,PerfRunFacts.Reason.ENV_UNREACHABLE,null);facts.event(run,PerfRunFacts.Type.WAIT_FINISHED,"ENV_WAIT",1,2,PerfRunFacts.Reason.ENV_REACHABLE,1234L);
        var restarted=new PerfRepository(f.jdbc);var events=(List<Map<String,Object>>)restarted.facts().timeline(run).get("items");
        assertThat(events).hasSize(3);assertThat(events.get(2)).containsEntry("durationMs",1234).containsEntry("loopNo",2);
        clearInvocations(f.executor);f.service.reconcileOnStartup();assertThat(restarted.getRun(run)).containsEntry("status","INTERRUPTED");
        assertThat(restarted.facts().timeline(run).toString()).contains("PROCESS_RESTART");verifyNoInteractions(f.executor);
    }
    @Test void stopWhileDeviceReadIsInFlightCannotRestoreRunningState()throws Exception{
        long run=f.start();var states=PerfRunService.class.getDeclaredField("runs");states.setAccessible(true);var state=((Map<Long,PerfRunService.PerfRunState>)states.get(f.service)).get(run);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);when(f.adb.getState("fake")).thenAnswer(call->{entered.countDown();assertThat(release.await(3,TimeUnit.SECONDS)).isTrue();return "device";});
        var method=PerfRunService.class.getDeclaredMethod("ensureDevice",PerfRunService.PerfRunState.class);method.setAccessible(true);
        var result=new CompletableFuture<Object>();Thread thread=Thread.ofPlatform().start(()->{try{result.complete(method.invoke(f.service,state));}catch(Exception failure){result.completeExceptionally(failure);}});
        assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();f.service.stop(run);release.countDown();assertThat(result.get(3,TimeUnit.SECONDS)).isEqualTo(false);thread.join();
        assertThat(state.status).isEqualTo("STOPPED");assertThat(f.repo.facts().timeline(run).toString()).contains("STOP_REQUESTED").doesNotContain("DEVICE_RECONNECTED");verify(f.adb,never()).tap(anyString(),anyInt(),anyInt());
    }
    @Test void conditionsDifferencesAndComparableOnlyTrendsAreExplicit(){
        long reference=f.start();var plan=f.repo.plan(reference);long same=f.repo.insertPlannedRun("fake",plan);long different=f.repo.insertPlannedRun("other",plan);
        f.repo.facts().changedDevice(different,Map.of("deviceIdentity","changed"));
        for(long run:List.of(reference,same,different)){long loop=f.repo.insertLoop(run,f.game,1);f.jdbc.update("UPDATE perf_loop SET status='OK',pss_avg_kb=1000,cpu_avg=2 WHERE id=?",loop);}
        assertThat(f.repo.facts().compare(reference,same)).containsEntry("comparable",true);assertThat(f.repo.facts().compare(reference,different)).containsEntry("comparable",false);
        var filtered=f.repo.facts().trend(f.repo,reference,f.game,true);assertThat((List<?>)filtered.get("items")).hasSize(2);assertThat(filtered).containsEntry("excluded",1);
        assertThat((List<?>)f.repo.facts().trend(f.repo,reference,f.game,false).get("items")).hasSize(3);
    }
    @Test void evidenceZipHasExactHashesAndDefaultOmitsRawIdentityAndPrivateFiles()throws Exception{
        long run=f.start();long loop=f.repo.insertLoop(run,f.game,1);f.repo.markLoopGameRoundId(loop,"BIZ-ROUND-ID");
        Files.createDirectories(dir.resolve("screenshots"));Path shot=dir.resolve("screenshots/run"+run+"-game"+f.game+"-loop1.png");Files.writeString(shot,"SECRET-SERIAL BIZ-ROUND-ID secret-do-not-export");
        var exporter=new PerfEvidencePackage(f.repo,new PerfEvidenceRepository(f.jdbc),dir);var files=unzip(exporter.create(run,false));
        assertThat(files.keySet()).contains("manifest.json","loops.json","timeline.json","logs/redacted.txt","request-summaries.json").noneMatch(key->key.startsWith("private/"));
        assertThat(files.values().stream().map(String::new).reduce("",String::concat)).doesNotContain("SECRET-SERIAL","BIZ-ROUND-ID","secret-do-not-export","fictional-room","fake");
        var json=new com.fasterxml.jackson.databind.ObjectMapper();var manifest=json.readTree(files.get("manifest.json"));
        manifest.path("files").fields().forEachRemaining(entry->{assertThat(PerfRunFacts.hash(new String(files.get(entry.getKey()),java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(entry.getValue().path("sha256").asText());});
        assertThat(manifest.toString()).contains("MISSING","原始内容可能含身份或凭据");
        var privatePack=unzip(exporter.create(run,true));assertThat(new String(privatePack.get("private/loop-1.png"))).contains("SECRET-SERIAL");assertThat(json.readTree(privatePack.get("manifest.json")).path("private").asBoolean()).isTrue();
    }
    @Test void evidenceFilesCannotEscapeThroughSymlinksAndMissingIsExplicit()throws Exception{
        var exporter=new PerfEvidencePackage(f.repo,new PerfEvidenceRepository(f.jdbc),dir);
        assertThat(exporter.attachment(dir.resolve("../outside.png")).state()).isEqualTo("UNSAFE_PATH");
        Files.createDirectories(dir.resolve("screenshots"));Files.writeString(dir.resolve("outside.png"),"private");Files.createSymbolicLink(dir.resolve("screenshots/link.png"),dir.resolve("outside.png"));
        assertThat(exporter.attachment(dir.resolve("screenshots/link.png")).state()).isEqualTo("UNSAFE_PATH");assertThat(exporter.attachment(dir.resolve("screenshots/missing.png")).state()).isEqualTo("MISSING");
    }
    @Test void deletionCascadesAndFailureRollsBackBothNewRunTables(){
        long run=f.start();f.repo.finishRun(run,"STOPPED");f.jdbc.execute("CREATE TRIGGER reject_parent BEFORE DELETE ON perf_run BEGIN SELECT RAISE(ABORT,'fixture');END");
        assertThatThrownBy(()->f.repo.deleteRun(run)).isInstanceOf(RuntimeException.class);assertThat(f.repo.facts().summary(run)).containsEntry("state","KNOWN");assertThat(f.repo.facts().timeline(run)).containsEntry("total",1L);
        f.jdbc.execute("DROP TRIGGER reject_parent");f.repo.deleteRun(run);for(String table:List.of("perf_run_conditions","perf_run_event"))assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class)).isZero();
    }
    @Test void cachedCoordinatesCannotBeRelabeledAsCurrentWhenOnlyOneButtonIsRelearned(){
        var groups=new ButtonGroupRepository(f.jdbc);var props=mock(com.sudpanel.config.AppProperties.class);when(props.resourceIdFile()).thenReturn(dir.resolve("fixture.properties").toString());
        var calibration=new CalibrationService(f.adb,mock(com.sudpanel.locator.UiLocator.class),groups,props);
        calibration.learnCoord("fake","A",new int[]{1,2});calibration.learnCoord("fake","B",new int[]{3,4});
        when(f.adb.shell(eq("fake"),contains("wm size"),anyLong())).thenReturn(new AdbClient.ExecResult(0,"Fixture Model\nPhysical size: 1080x2400\nSurfaceOrientation: 1",""));
        assertThat(calibration.getCachedCoord("fake","B")).isNull();calibration.learnCoord("fake","A",new int[]{5,6});
        assertThat(calibration.getCachedCoord("fake","B")).isNull();assertThat(calibration.snapshotCoords("fake")).containsOnlyKeys("A");
    }
    @Test void privateZipRequiresActualLoopbackAndExplicitConfirmationWhileDefaultIsReadOnly()throws Exception{
        long run=f.start();clearInvocations(f.adb);
        var controller=new com.sudpanel.web.PerfContextController(f.repo,f.calibration,new PerfEvidencePackage(f.repo,new PerfEvidenceRepository(f.jdbc),dir));
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new com.sudpanel.web.GlobalExceptionHandler()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/perf/runs/"+run+"/evidence.zip")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/perf/runs/"+run+"/evidence.zip?includePrivateFiles=true")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/perf/runs/"+run+"/evidence.zip?includePrivateFiles=true&confirmation=PRIVATE_LOCAL_ONLY").header("X-Forwarded-For","127.0.0.1").with(request->{request.setRemoteAddr("198.51.100.9");return request;})).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        verifyNoInteractions(f.adb);
    }
    @Test void earlyResumeRejectsUnknownOrRotatedLayoutBeforeMutatingHistory()throws Exception{
        for(String captured:List.of("null","{\"model\":\"Fixture Model\",\"width\":1080,\"height\":2400,\"rotation\":1}")){
            var plan=new PerfExecutionPlan(1,"START","2026-10-15T00:00:00Z","EARLY_SETTLE",1,5,1L,null,null,List.of(f.game),Map.of(f.game,Map.of()),Map.of(),Map.of("coordinate.layout",captured),Map.of(),Map.of(),1000,1,1,3);
            long run=f.repo.insertPlannedRun("fake",plan);long loop=f.repo.insertLoop(run,f.game,1);f.jdbc.update("UPDATE perf_loop SET status='INTERRUPTED' WHERE id=?",loop);f.repo.finishRun(run,"STOPPED");
            assertThatThrownBy(()->f.service.resume(run)).hasMessageContaining("坐标来源未知或设备布局已变化");
            assertThat(f.repo.getRun(run)).containsEntry("status","STOPPED");assertThat(f.repo.getLoop(loop)).containsEntry("status","INTERRUPTED");
        }
        verifyNoInteractions(f.executor);verify(f.adb,never()).tap(anyString(),anyInt(),anyInt());
    }
    @Test void unreadableResumeConditionsBecomeUnknownWithoutInventingADeviceChange(){
        long run=f.start();assertThat(f.repo.facts().changedDevice(run,Map.of())).isTrue();
        assertThat(f.repo.facts().summary(run)).containsEntry("state","UNKNOWN").containsEntry("comparable",false).containsEntry("message","续跑条件未能完整读取，不能确认可比");
        long legacy=f.repo.insertRun("old","NORMAL",String.valueOf(f.game),1,5,1L);assertThat(f.repo.facts().changedDevice(legacy,Map.of("model","new"))).isFalse();assertThat(f.repo.facts().summary(legacy)).containsEntry("state","NOT_RECORDED");
    }
    @Test void differentLoopCountsCannotBeMergedAsTheSameAverageConditions(){
        long run=f.start();var old=f.repo.plan(run);var other=new PerfExecutionPlan(old.version(),old.origin(),old.capturedAt(),old.mode(),old.loopCount()+1,old.sampleSeconds(),old.earlyTapDelayMs(),old.aiCount(),old.coordinateGroupId(),old.gameIds(),old.games(),old.requests(),old.settings(),old.resources(),old.coordinates(),old.sampleIntervalMs(),old.tapSettleMs(),old.playingWaitMs(),old.maxConsecutiveInvalid());
        long candidate=f.repo.insertPlannedRun("fake",other);var comparison=f.repo.facts().compare(run,candidate);
        assertThat(comparison).containsEntry("comparable",false);assertThat((List<String>)comparison.get("differences")).contains("loopCount");
    }
    @Test void failedResumeConditionMarkCannotReviveOrDeleteInterruptedHistory()throws Exception{
        long run=f.start();long loop=f.repo.insertLoop(run,f.game,1);f.jdbc.update("UPDATE perf_loop SET status='INTERRUPTED' WHERE id=?",loop);f.repo.finishRun(run,"STOPPED");
        var owner=PerfRunService.class.getDeclaredField("runningRunId");owner.setAccessible(true);owner.set(f.service,null);clearInvocations(f.executor,f.runner);
        when(f.adb.devices()).thenReturn(List.of());f.jdbc.execute("CREATE TRIGGER reject_mixed BEFORE UPDATE ON perf_run_conditions BEGIN SELECT RAISE(ABORT,'fixture');END");
        assertThatThrownBy(()->f.service.resume(run)).hasMessageContaining("可比条件核验失败");
        assertThat(f.repo.getRun(run)).containsEntry("status","STOPPED");assertThat(f.repo.getLoop(loop)).containsEntry("status","INTERRUPTED");
        assertThat(f.repo.facts().summary(run)).containsEntry("state","KNOWN").containsEntry("comparable",true);
        assertThat(owner.get(f.service)).isNull();verifyNoInteractions(f.executor);verify(f.runner,never()).resetCookieScope(anyString());verify(f.adb,never()).tap(anyString(),anyInt(),anyInt());
        f.jdbc.execute("DROP TRIGGER reject_mixed");f.service.resume(run);assertThat(f.repo.facts().summary(run)).containsEntry("state","UNKNOWN").containsEntry("comparable",false);
    }
    @Test void explicitStopOfOldRunWithoutMemoryStillPersistsUserEvent(){
        long run=f.repo.insertRun("fake","NORMAL",String.valueOf(f.game),1,5,1L);f.jdbc.update("UPDATE perf_run SET status='INTERRUPTED' WHERE id=?",run);
        f.service.stop(run);assertThat(f.repo.getRun(run)).containsEntry("status","STOPPED");assertThat(f.repo.facts().timeline(run).toString()).contains("STOP_REQUESTED","USER_REQUEST");
    }
    @Test void environmentTimeoutIsNotMisreportedAsUserStop()throws Exception{
        long run=f.start();var state=new PerfRunService.PerfRunState(run,"fake",PerfRunService.Mode.NORMAL,List.of(f.game));
        var method=PerfRunService.class.getDeclaredMethod("waitForEnvironment",PerfRunService.PerfRunState.class);method.setAccessible(true);var outcome=new CompletableFuture<Object>();
        // 只在自有worker保留中断标记使旧sleep立即返回，走完真实有限探活分支；不等待墙钟，也不改生产常量。
        var worker=Thread.ofPlatform().start(()->{Thread.currentThread().interrupt();try{outcome.complete(method.invoke(f.service,state));}catch(Exception failure){outcome.completeExceptionally(failure);}finally{Thread.interrupted();}});
        assertThat(outcome.get(10,TimeUnit.SECONDS)).isEqualTo(false);worker.join();assertThat(state.status).isEqualTo("ABORTED");
        var items=(List<Map<String,Object>>)f.repo.facts().timeline(run).get("items");assertThat(items.getLast()).containsEntry("reason","WAIT_TIMEOUT");assertThat(items.getLast()).doesNotContainEntry("reason","STOPPED");
    }
    private Map<String,byte[]> unzip(byte[] zip)throws Exception{var files=new LinkedHashMap<String,byte[]>();try(var in=new ZipInputStream(new java.io.ByteArrayInputStream(zip))){for(var entry=in.getNextEntry();entry!=null;entry=in.getNextEntry())files.put(entry.getName(),in.readAllBytes());}return files;}
}
