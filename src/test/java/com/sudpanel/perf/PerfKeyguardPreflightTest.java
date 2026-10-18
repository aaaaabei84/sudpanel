package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sudpanel.adb.AdbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PerfKeyguardPreflightTest {
    PerfPreflightTest fixture;

    @BeforeEach void setup() {
        fixture = new PerfPreflightTest();
        fixture.setup();
    }

    void noActionsOrCalibration() {
        verify(fixture.adb, never()).tap(anyString(), anyInt(), anyInt());
        verify(fixture.adb, never()).wakeScreen(anyString());
        verify(fixture.adb, never()).startApp(anyString(), anyString());
        verify(fixture.adb, never()).logcatClear(anyString());
        verify(fixture.adb, never()).setStayOn(anyString(), anyBoolean());
        verify(fixture.calibration, never()).ensureLayoutReady(anyString(), any());
        verify(fixture.calibration, never()).ensureGroupReady(any());
    }

    @Test void explicitLockBlocksNormalEvenWhenScreenIsOnAndAppIsForeground() {
        when(fixture.adb.keyguardState("fictional-device")).thenReturn(AdbClient.KeyguardState.LOCKED);
        var result = fixture.check();
        assertThat(result.ready()).isFalse();
        assertThat(result.checks()).anySatisfy(item -> {
            assertThat(item.key()).isEqualTo("keyguard");
            assertThat(item.status()).isEqualTo("BLOCK");
            assertThat(item.message()).contains("手动解锁");
        });
        assertThat(result.checks()).anyMatch(item -> item.key().equals("screen") && item.status().equals("PASS"));
        assertThat(result.checks()).anyMatch(item -> item.key().equals("foreground") && item.status().equals("PASS"));
        noActionsOrCalibration();
    }

    @Test void explicitUnlockedKeepsExistingReadinessAndNormalDoesNotRequireCalibration() {
        when(fixture.adb.keyguardState("fictional-device")).thenReturn(AdbClient.KeyguardState.UNLOCKED);
        var result = fixture.check();
        assertThat(result.ready()).isTrue();
        assertThat(result.checks()).anyMatch(item -> item.key().equals("keyguard") && item.status().equals("PASS"));
        noActionsOrCalibration();
    }

    @Test void unknownOrAbsentStateWarnsAndDoesNotBlock() {
        when(fixture.adb.keyguardState("fictional-device")).thenReturn(AdbClient.KeyguardState.UNKNOWN).thenReturn((AdbClient.KeyguardState) null);
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = fixture.check();
            assertThat(result.ready()).isTrue();
            assertThat(result.checks()).anyMatch(item -> item.key().equals("keyguard") && item.status().equals("WARN"));
        }
        noActionsOrCalibration();
    }

    @Test void readExceptionIsSanitizedUnknownWarning() {
        when(fixture.adb.keyguardState("fictional-device")).thenThrow(new IllegalStateException("private-serial-and-device-stderr"));
        var result = fixture.check();
        assertThat(result.ready()).isTrue();
        assertThat(result.toString()).doesNotContain("private-serial", "device-stderr");
        assertThat(result.checks()).anyMatch(item -> item.key().equals("keyguard") && item.status().equals("WARN"));
        noActionsOrCalibration();
    }

    @Test void disconnectedDeviceDoesNotIssuePolicyQuery() {
        when(fixture.adb.devices()).thenReturn(java.util.List.of());
        assertThat(fixture.service.device("fictional-device").ready()).isFalse();
        verify(fixture.adb, never()).keyguardState(anyString());
        verify(fixture.adb, never()).shell(anyString(), anyString(), anyLong());
    }
}
