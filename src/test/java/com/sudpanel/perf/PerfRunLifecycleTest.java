package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sudpanel.adb.AdbClient;
import com.sudpanel.api.ApiRepository;
import com.sudpanel.config.AppProperties;
import com.sudpanel.locator.UiLocator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PerfRunLifecycleTest {
    private Map<String, Object> record(String mode) {
        return Map.of("id", 1L, "status", "STOPPED", "gameQueue", "1", "mode", mode,
                "loopCount", 1, "sampleSeconds", 5, "earlyTapDelayMs", 1L, "device", "fake-device");
    }

    @Test void invalidInterfaceBindingsLeaveStoppedRunAndInterruptedLoopsUntouched() {
        PerfRepository repo = mock(PerfRepository.class);
        ApiRepository api = mock(ApiRepository.class);
        when(repo.getRun(1L)).thenReturn(record("NORMAL"));
        when(api.listCases()).thenReturn(List.of());
        when(api.readSnapshot(any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get());
        when(repo.getSettings(any())).thenReturn(Map.of());
        PerfRunService service = new PerfRunService(mock(AdbClient.class), mock(UiLocator.class),
                mock(PerfCollector.class), repo, mock(PerfJudge.class), mock(AppProperties.class),
                mock(CalibrationService.class), null, api);
        assertThatThrownBy(() -> service.resume(1L,"CAPTURE_CURRENT")).hasMessageContaining("缺接口用例绑定");
        verify(repo, never()).deleteInterruptedLoops(anyLong());
        verify(repo, never()).prepareResume(anyLong(),any());
    }

    @Test void pendingStopDatabaseWriteFinishesBeforeSameRunCanResume() throws Exception {
        PerfRepository repo = mock(PerfRepository.class);
        AdbClient adb = mock(AdbClient.class);
        PerfRunService service = new PerfRunService(adb, mock(UiLocator.class), mock(PerfCollector.class),
                repo, mock(PerfJudge.class), mock(AppProperties.class), mock(CalibrationService.class));
        // 免坐标模式隔离控制请求交错；EARLY布局失配由PerfLifecycleContextTest单独覆盖。
        // 阻止实际提交执行；这里检验控制请求状态交错，不操作设备。
        var executorField = PerfRunService.class.getDeclaredField("executor");
        executorField.setAccessible(true);
        ExecutorService executor = mock(ExecutorService.class);
        executorField.set(service, executor);
        var runsField = PerfRunService.class.getDeclaredField("runs");
        runsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var runs = (Map<Long, PerfRunService.PerfRunState>) runsField.get(service);
        var oldState = new PerfRunService.PerfRunState(1, "fake-device", PerfRunService.Mode.NORMAL, List.of(1L));
        oldState.status = "STOPPED";
        runs.put(1L, oldState);
        when(repo.getRun(1L)).thenReturn(record("NORMAL"));
        when(repo.plan(1L)).thenReturn(new PerfExecutionPlan(1,"START","2026-10-14T00:00:00Z","NORMAL",1,5,1L,null,null,List.of(1L),Map.of(1L,Map.of()),Map.of(),Map.of(),Map.of(),Map.of(),1000,1,1,3));
        AtomicReference<String> persisted = new AtomicReference<>("STOPPED");
        CountDownLatch writingStop = new CountDownLatch(1), allowStop = new CountDownLatch(1);
        doAnswer(call -> {
            writingStop.countDown();
            assertThat(allowStop.await(3, TimeUnit.SECONDS)).isTrue();
            persisted.set("STOPPED");
            return null;
        }).when(repo).finishRun(1L, "STOPPED");
        when(repo.prepareResume(eq(1L),isNull())).thenAnswer(call -> {persisted.set("RUNNING");return 0;});
        CompletableFuture<Void> stopping = CompletableFuture.runAsync(() -> service.stop(1L));
        AtomicReference<Throwable> resumeError = new AtomicReference<>();
        Thread resuming = new Thread(() -> {
            try { service.resume(1L); } catch (Throwable e) { resumeError.set(e); }
        });
        try {
            assertThat(writingStop.await(3, TimeUnit.SECONDS)).isTrue();
            resuming.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (resuming.getState() != Thread.State.BLOCKED && resuming.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(resuming.getState()).isEqualTo(Thread.State.BLOCKED);
            verify(repo, never()).prepareResume(anyLong(),any());
            allowStop.countDown();
            stopping.get(3, TimeUnit.SECONDS);
            resuming.join(3000);
            assertThat(resuming.isAlive()).isFalse();
            assertThat(resumeError.get()).isNull();
            assertThat(persisted.get()).isEqualTo("RUNNING");
            assertThat(runs.get(1L)).isNotSameAs(oldState);
            verify(executor).submit(any(Runnable.class));
            verifyNoInteractions(adb);
        } finally {
            allowStop.countDown();
            resuming.join(3000);
        }
    }
}
