package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sudpanel.adb.AdbClient;
import com.sudpanel.config.AppProperties;
import com.sudpanel.locator.UiLocator;
import com.sudpanel.web.PerfController;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PerfRunDeletionTest {
    private PerfRunService service(AdbClient adb, PerfRepository repo) {
        return new PerfRunService(adb, mock(UiLocator.class), mock(PerfCollector.class),
                repo, mock(PerfJudge.class), mock(AppProperties.class), mock(CalibrationService.class));
    }

    @Test void allActiveStatesRefuseDeletionWhileUnownedStoppedRunCanBeDeleted() {
        PerfRepository repo = mock(PerfRepository.class);
        AdbClient adb = mock(AdbClient.class);
        PerfController controller = new PerfController(service(adb, repo), repo,
                mock(PerfReportService.class), mock(AppProperties.class));
        for (String status : new String[] {"RUNNING", "WAIT_DEVICE", "WAIT_ENV"}) {
            when(repo.getRun(1L)).thenReturn(Map.of("id", 1L, "status", status));
            assertThatThrownBy(() -> controller.deleteRun(1L)).as(status)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("先停止");
        }
        when(repo.getRun(99L)).thenReturn(null);
        assertThatThrownBy(() -> controller.deleteRun(99L)).hasMessageContaining("不存在");
        verify(repo, never()).deleteRun(anyLong());
        when(repo.getRun(2L)).thenReturn(Map.of("id", 2L, "status", "STOPPED"));
        controller.deleteRun(2L);
        verify(repo).deleteRun(2L);
        verifyNoInteractions(adb);
    }

    @Test void terminalRunCannotBeDeletedUntilDeviceRestorationAndFinalRelease() throws Exception {
        for (String status : List.of("STOPPED", "COMPLETED")) {
            PerfRepository repo = mock(PerfRepository.class);
            AdbClient adb = mock(AdbClient.class);
            PerfRunService service = service(adb, repo);
            var owner = PerfRunService.class.getDeclaredField("runningRunId");
            owner.setAccessible(true);
            owner.set(service, 1L); // 与 start/resume 已取得执行权的状态相同
            when(repo.getRun(1L)).thenReturn(Map.of("id", 1L, "status", status));
            when(adb.stayOnSetting("fake-device")).thenReturn(7);
            when(adb.isScreenOn("fake-device")).thenReturn(true);
            CountDownLatch restoring = new CountDownLatch(1), allowRestore = new CountDownLatch(1);
            when(adb.restoreStayOnSetting("fake-device", 7)).thenAnswer(call -> {
                restoring.countDown();
                assertThat(allowRestore.await(3, TimeUnit.SECONDS)).isTrue();
                return true;
            });
            var state = new PerfRunService.PerfRunState(1, "fake-device", PerfRunService.Mode.NORMAL, List.of());
            state.status = status;
            Method run = PerfRunService.class.getDeclaredMethod("runAll", PerfRunService.PerfRunState.class,
                    int.class, int.class, long.class);
            run.setAccessible(true);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> finished = worker.submit(() -> {
                    try { run.invoke(service, state, 1, 5, 1L); }
                    catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
                });
                assertThat(restoring.await(3, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.deleteRun(1L)).hasMessageContaining("收尾");
                verify(repo, never()).deleteRun(anyLong());
                allowRestore.countDown();
                finished.get(3, TimeUnit.SECONDS);
                service.deleteRun(1L);
                verify(repo).deleteRun(1L);
                assertThat(owner.get(service)).isNull();
            } finally {
                allowRestore.countDown();
                worker.shutdownNow();
            }
        }
    }

    @Test void resumeCannotReadOrReviveRunBetweenDeletionCheckAndDelete() throws Exception {
        PerfRepository repo = mock(PerfRepository.class);
        PerfRunService service = service(mock(AdbClient.class), repo);
        AtomicReference<Map<String, Object>> record = new AtomicReference<>(Map.of("id", 1L, "status", "STOPPED"));
        CountDownLatch deleting = new CountDownLatch(1), allowDelete = new CountDownLatch(1);
        when(repo.getRun(1L)).thenAnswer(call -> record.get());
        doAnswer(call -> {
            deleting.countDown();
            assertThat(allowDelete.await(3, TimeUnit.SECONDS)).isTrue();
            record.set(null);
            return null;
        }).when(repo).deleteRun(1L);
        CompletableFuture<Void> delete = CompletableFuture.runAsync(() -> service.deleteRun(1L));
        AtomicReference<Throwable> resumeError = new AtomicReference<>();
        Thread resume = new Thread(() -> {
            try { service.resume(1L); } catch (Throwable e) { resumeError.set(e); }
        });
        try {
            assertThat(deleting.await(3, TimeUnit.SECONDS)).isTrue();
            resume.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (resume.getState() != Thread.State.BLOCKED && resume.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(resume.getState()).isEqualTo(Thread.State.BLOCKED);
            verify(repo, times(1)).getRun(1L);
            allowDelete.countDown();
            delete.get(3, TimeUnit.SECONDS);
            resume.join(3000);
            assertThat(resume.isAlive()).isFalse();
            assertThat(resumeError.get()).isInstanceOf(IllegalStateException.class).hasMessageContaining("不存在");
            verify(repo, never()).resumeRun(anyLong());
        } finally {
            allowDelete.countDown();
            resume.join(3000);
        }
    }
}
