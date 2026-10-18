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
import org.junit.jupiter.api.Test;

class PerfPreflightAssertionVariableTest {
    @Test void unresolvedAssertionVariableBlocksOtherwiseReadyConfiguration() {
        AdbClient adb = mock(AdbClient.class); PerfRepository perf = mock(PerfRepository.class);
        ApiRepository api = mock(ApiRepository.class); CalibrationService calibration = mock(CalibrationService.class);
        PerfPreflightService service = new PerfPreflightService(adb, perf, api, calibration);
        when(adb.devices()).thenReturn(List.of(new Device("fictional", "device", "fictional")));
        when(calibration.pkg()).thenReturn("com.fictional.game");
        when(adb.shell(anyString(), anyString(), anyLong())).thenReturn(new AdbClient.ExecResult(0, "package:fictional.apk", ""));
        when(adb.foregroundPackage("fictional")).thenReturn("com.fictional.game");
        when(adb.isScreenOn("fictional")).thenReturn(true);
        when(perf.getGame(1)).thenReturn(Map.of("id", 1L, "humanCount", 1, "aiCount", 3,
                "mgId", "fictional", "appId", "fictional", "roomId", "fictional"));
        when(perf.getSettings(anyList())).thenReturn(Map.of());
        var cases = new ArrayList<ApiSpec>(); String[] names = {"批量加入", "游戏开始", "游戏结束", "房间清理"};
        for (int i = 0; i < names.length; i++) cases.add(new ApiSpec((long) i + 1, names[i], "POST",
                "http://127.0.0.1/fictional", "{}", "{\"data\":{\"user_infos\":[{}]}}", null, "200", "", 1000,
                false, true, null, null));
        when(api.listCases()).thenAnswer(ignored -> List.copyOf(cases));
        when(api.resolveEnvForSpec(any(), isNull())).thenReturn(1L);
        when(api.getEnv(1)).thenReturn(new ApiEnv(1, "fictional", true, 0));
        when(api.getVars(1)).thenReturn(ApiVariables.VarSet.empty());
        when(api.getSignConfig(1)).thenReturn(mock(ApiSigner.Config.class));
        var request = new PerfPreflightService.Request("fictional", "NORMAL", List.of(1L), null);
        assertThat(service.check(request).ready()).isTrue();
        ApiSpec old = cases.getFirst();
        cases.set(0, new ApiSpec(old.id(), old.name(), old.method(), old.url(), old.headers(), old.body(), old.note(),
                old.expectStatus(), old.expectCode(), old.timeoutMs(), old.insecure(), old.enabled(), old.groupId(),
                "[{\"path\":\"value\",\"op\":\"gt\",\"expect\":\"{{run.minimum}}\"}]"));
        var result = service.check(request);
        assertThat(result.ready()).isFalse();
        assertThat(result.checks()).anyMatch(item -> item.key().contains("request-") && item.status().equals("BLOCK"));
    }
}
