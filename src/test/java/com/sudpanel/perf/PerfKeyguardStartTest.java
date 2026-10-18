package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sudpanel.adb.AdbClient;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PerfKeyguardStartTest {
    @TempDir Path dir;
    PerfFrozenPlanTest fixture;

    @BeforeEach void setup() throws Exception {
        fixture = new PerfFrozenPlanTest();
        fixture.dir = dir;
        fixture.setup();
    }

    @Test void lockedStartRejectsBeforeRunPersistenceOrWorkerDeviceActions() {
        when(fixture.adb.keyguardState("fake")).thenReturn(AdbClient.KeyguardState.LOCKED);
        assertThatThrownBy(fixture::start).hasMessageContaining("预检").hasMessageContaining("手动解锁");
        assertThat(fixture.repo.listRuns()).isEmpty();
        verifyNoInteractions(fixture.executor);
        verify(fixture.adb, never()).tap(anyString(), anyInt(), anyInt());
        verify(fixture.adb, never()).wakeScreen(anyString());
        verify(fixture.adb, never()).startApp(anyString(), anyString());
        verify(fixture.adb, never()).setStayOn(anyString(), anyBoolean());
        assertThat(mockingDetails(fixture.runner).getInvocations()).allMatch(call -> call.getMethod().getName().equals("snapshotEnvironment"));
        verify(fixture.calibration, never()).ensureLayoutReady(anyString(), any());
    }

    @Test void unlockedStartStillFreezesNormalPlanWithoutCoordinateCalibration() {
        when(fixture.adb.keyguardState("fake")).thenReturn(AdbClient.KeyguardState.UNLOCKED);
        long run = fixture.start();
        assertThat(fixture.repo.plan(run).mode()).isEqualTo("NORMAL");
        assertThat(fixture.repo.getRun(run)).containsEntry("status", "RUNNING");
        verify(fixture.calibration, never()).ensureLayoutReady(anyString(), any());
        verify(fixture.calibration, never()).ensureGroupReady(any());
    }

    @Test void unknownPolicyDoesNotRejectNormalStart() {
        when(fixture.adb.keyguardState("fake")).thenReturn(AdbClient.KeyguardState.UNKNOWN);
        long run = fixture.start();
        assertThat(fixture.repo.plan(run)).isNotNull();
        assertThat(fixture.repo.getRun(run)).containsEntry("status", "RUNNING");
    }

    @Test void frozenPlanPreflightUsesFrozenPackageAndStillBlocksExplicitLock() {
        when(fixture.adb.keyguardState("fake")).thenReturn(AdbClient.KeyguardState.UNLOCKED);
        long run = fixture.start();
        var plan = fixture.repo.plan(run);
        when(fixture.adb.keyguardState("fake")).thenReturn(AdbClient.KeyguardState.LOCKED);
        when(fixture.calibration.pkg()).thenThrow(new AssertionError("must not read changed package"));
        var result = new PerfPreflightService(fixture.adb, fixture.repo, fixture.api, fixture.calibration)
                .check(new PerfPreflightService.Request("fake", "NORMAL", List.of(fixture.game), null), plan);
        assertThat(result.ready()).isFalse();
        assertThat(result.checks()).anyMatch(item -> item.key().equals("keyguard") && item.status().equals("BLOCK"));
        assertThat(result.checks()).anyMatch(item -> item.key().equals("package") && item.status().equals("PASS"));
        verify(fixture.calibration, never()).ensureLayoutReady(anyString(), any());
    }
}
