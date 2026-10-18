package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.sudpanel.adb.AdbClient;
import com.sudpanel.config.AppProperties;
import com.sudpanel.locator.UiLocator;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;

class PerfStayOnRestoreTest {
    @Test void completionRestoresExactOriginalBitmask() throws Exception {
        for (int original : new int[] {0, 2, 7}) {
            AdbClient adb = mock(AdbClient.class);
            when(adb.stayOnSetting("fake-device")).thenReturn(original);
            when(adb.isScreenOn("fake-device")).thenReturn(true);
            when(adb.restoreStayOnSetting("fake-device", original)).thenReturn(true);
            PerfRepository repo = mock(PerfRepository.class);
            PerfRunService service = new PerfRunService(adb, mock(UiLocator.class), mock(PerfCollector.class),
                    repo, mock(PerfJudge.class), mock(AppProperties.class), mock(CalibrationService.class));
            var state = new PerfRunService.PerfRunState(1, "fake-device", PerfRunService.Mode.NORMAL, List.of());
            Method run = PerfRunService.class.getDeclaredMethod("runAll", PerfRunService.PerfRunState.class,
                    int.class, int.class, long.class);
            run.setAccessible(true);
            run.invoke(service, state, 1, 5, 1L);
            verify(adb).setStayOn("fake-device", true);
            verify(adb).restoreStayOnSetting("fake-device", original);
            verify(adb, never()).setStayOn("fake-device", false);
            assertThat(state.status).isEqualTo("COMPLETED");
        }
    }

    @Test void unknownOriginalSettingIsNeverOverwritten() throws Exception {
        AdbClient adb = mock(AdbClient.class);
        when(adb.stayOnSetting("fake-device")).thenReturn(null);
        when(adb.isScreenOn("fake-device")).thenReturn(true);
        PerfRunService service = new PerfRunService(adb, mock(UiLocator.class), mock(PerfCollector.class),
                mock(PerfRepository.class), mock(PerfJudge.class), mock(AppProperties.class), mock(CalibrationService.class));
        Method run = PerfRunService.class.getDeclaredMethod("runAll", PerfRunService.PerfRunState.class,
                int.class, int.class, long.class);
        run.setAccessible(true);
        run.invoke(service, new PerfRunService.PerfRunState(1, "fake-device", PerfRunService.Mode.NORMAL, List.of()), 1, 5, 1L);
        verify(adb, never()).setStayOn(anyString(), anyBoolean());
        verify(adb, never()).restoreStayOnSetting(anyString(), anyInt());
    }

    @Test void stoppedRunAndDatabaseFinalizationFailureStillRestoreOriginal() throws Exception {
        for (boolean databaseFails : new boolean[] {false, true}) {
            AdbClient adb = mock(AdbClient.class);
            when(adb.stayOnSetting("fake-device")).thenReturn(7);
            when(adb.isScreenOn("fake-device")).thenReturn(true);
            when(adb.restoreStayOnSetting("fake-device", 7)).thenReturn(true);
            PerfRepository repo = mock(PerfRepository.class);
            if (databaseFails) doThrow(new IllegalStateException("fake database failure")).when(repo).finishRun(1, "STOPPED");
            PerfRunService service = new PerfRunService(adb, mock(UiLocator.class), mock(PerfCollector.class),
                    repo, mock(PerfJudge.class), mock(AppProperties.class), mock(CalibrationService.class));
            var state = new PerfRunService.PerfRunState(1, "fake-device", PerfRunService.Mode.NORMAL, List.of());
            state.status = "STOPPED";
            Method run = PerfRunService.class.getDeclaredMethod("runAll", PerfRunService.PerfRunState.class,
                    int.class, int.class, long.class);
            run.setAccessible(true);
            java.lang.reflect.Field owner = PerfRunService.class.getDeclaredField("runningRunId");
            owner.setAccessible(true);
            owner.set(service, 1L);
            try { run.invoke(service, state, 1, 5, 1L); }
            catch (java.lang.reflect.InvocationTargetException expected) {
                assertThat(databaseFails).isTrue();
            }
            verify(adb).restoreStayOnSetting("fake-device", 7);
            assertThat(owner.get(service)).isNull();
        }
    }
}
