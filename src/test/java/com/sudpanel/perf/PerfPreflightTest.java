package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sudpanel.adb.AdbClient;
import com.sudpanel.adb.Device;
import com.sudpanel.api.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PerfPreflightTest {
    AdbClient adb;
    PerfRepository perf;
    ApiRepository api;
    CalibrationService calibration;
    PerfPreflightService service;
    List<ApiSpec> cases;

    @BeforeEach void setup() {
        adb = mock(AdbClient.class); perf = mock(PerfRepository.class); api = mock(ApiRepository.class);
        calibration = mock(CalibrationService.class); service = new PerfPreflightService(adb, perf, api, calibration);
        when(adb.devices()).thenReturn(List.of(new Device("fictional-device", "device", "fictional-model")));
        when(calibration.pkg()).thenReturn("com.fictional.game");
        when(adb.shell(eq("fictional-device"), eq("pm path com.fictional.game"), eq(10L)))
                .thenReturn(new AdbClient.ExecResult(0, "package:fictional.apk", ""));
        when(adb.foregroundPackage("fictional-device")).thenReturn("com.fictional.game");
        when(adb.isScreenOn("fictional-device")).thenReturn(true);
        when(perf.getGame(1)).thenReturn(Map.of("id", 1L, "humanCount", 1, "aiCount", 3,
                "mgId", "fictional-mg", "appId", "fictional-app", "roomId", "fictional-room"));
        cases = new ArrayList<>();
        String[] names = {"批量加入", "游戏开始", "游戏结束", "房间清理"};
        for (int index = 0; index < names.length; index++) cases.add(new ApiSpec((long) index + 1, names[index], "POST",
                "http://127.0.0.1/fictional", "{}", "{\"data\":{\"user_infos\":[{}]}}", null, "200", "0", 1000, false, true, null, null));
        when(api.listCases()).thenAnswer(ignored -> List.copyOf(cases));
        when(perf.getSettings(anyList())).thenReturn(Map.of());
        when(api.resolveEnvForSpec(any(), isNull())).thenReturn(1L);
        when(api.getEnv(1)).thenReturn(new ApiEnv(1, "fictional-env", true, 0));
        when(api.getVars(1)).thenReturn(ApiVariables.VarSet.empty());
        when(api.getSignConfig(1)).thenReturn(mock(ApiSigner.Config.class));
    }

    PerfPreflightService.Result check() {
        return service.check(new PerfPreflightService.Request("fictional-device", "NORMAL", List.of(1L), null));
    }

    @Test void completeConfigurationIsReadOnlyAndDoesNotExposeSensitiveIdentity() {
        var result = check(); assertThat(result.ready()).isTrue();
        assertThat(result.toString()).doesNotContain("fictional-device", "fictional-mg", "fictional-app", "fictional-room", "com.fictional.game");
        verify(adb, never()).tap(anyString(), anyInt(), anyInt());
        verify(adb, never()).logcatClear(anyString());
        verify(adb, never()).startApp(anyString(), anyString());
        verify(adb, never()).wakeScreen(anyString());
        verify(adb, never()).setStayOn(anyString(), anyBoolean());
        Set<String> allowedReads = Set.of("getGame", "getSettings", "getSetting");
        assertThat(mockingDetails(perf).getInvocations()).allMatch(invocation -> allowedReads.contains(invocation.getMethod().getName()));
    }

    @Test void unauthorizedDeviceHasActionableMessageWithoutShellActions() {
        when(adb.devices()).thenReturn(List.of(new Device("fictional-device", "unauthorized", "fictional")));
        var result = service.device("fictional-device");
        assertThat(result.ready()).isFalse();
        assertThat(result.checks().getFirst().message()).contains("授权 USB 调试");
        verify(adb, never()).shell(anyString(), anyString(), anyLong());
    }

    @Test void missingBindingAndOutOfRangeEffectiveRosterBlockReadiness() {
        cases.removeLast();
        when(perf.getGame(1)).thenReturn(Map.of("id", 1, "humanCount", 64, "aiCount", 1));
        var result = check(); assertThat(result.ready()).isFalse();
        assertThat(result.checks()).anyMatch(item -> item.key().startsWith("binding-") && item.status().equals("BLOCK"));
        assertThat(result.checks()).anyMatch(item -> item.key().endsWith("-roster") && item.status().equals("BLOCK"));
    }

    @Test void forbiddenHttpHeaderIsFoundWithoutSendingTheRequest() {
        ApiSpec old = cases.getFirst();
        cases.set(0, new ApiSpec(old.id(), old.name(), old.method(), old.url(), "{\"Host\":\"fictional\"}",
                old.body(), old.note(), old.expectStatus(), old.expectCode(), old.timeoutMs(), old.insecure(), old.enabled(), old.groupId(), old.asserts()));
        assertThat(check().checks()).anyMatch(item -> item.key().contains("request-推人机") && item.status().equals("BLOCK"));
    }

    @Test void missingAppAndAdbEnumerationFailureRemainDistinct() {
        when(adb.shell(anyString(), anyString(), anyLong())).thenReturn(new AdbClient.ExecResult(0, "", ""));
        assertThat(service.device("fictional-device").checks()).anyMatch(item -> item.message().contains("尚未安装"));
        when(adb.devices()).thenThrow(new IllegalStateException("fictional stderr must not leak"));
        var result = service.device("fictional-device");
        assertThat(result.ready()).isFalse();
        assertThat(result.toString()).doesNotContain("fictional stderr");
    }
}
