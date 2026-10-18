package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sudpanel.adb.AdbClient;
import com.sudpanel.api.ApiExec;
import com.sudpanel.api.ApiRepository;
import com.sudpanel.api.ApiRunner;
import com.sudpanel.api.ApiSpec;
import com.sudpanel.config.AppProperties;
import com.sudpanel.locator.UiLocator;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PerfEvidenceFlowTest {
    ApiRepository api;
    ApiRunner runner;
    PerfEvidenceRepository evidence;
    PerfRunService service;
    PerfRunService.PerfRunState state;

    @BeforeEach void setup() {
        api = mock(ApiRepository.class); runner = mock(ApiRunner.class); evidence = mock(PerfEvidenceRepository.class);
        service = new PerfRunService(mock(AdbClient.class), mock(UiLocator.class), mock(PerfCollector.class),
                mock(PerfRepository.class), mock(PerfJudge.class), mock(AppProperties.class), mock(CalibrationService.class),
                runner, api, evidence);
        state = new PerfRunService.PerfRunState(1, "fictional", PerfRunService.Mode.AI_ROOM, List.of(1L),
                null, Map.of(), new PerfRunService.AiCaseBinding(1, 2, 3, 4), null);
        state.evidenceLoopId = 9L; state.currentLoop = 1;
        when(api.getCase(anyLong())).thenAnswer(call -> new ApiSpec(call.getArgument(0), "fictional", "POST",
                "http://127.0.0.1/fake", "{}", "{}", null, "200", "0", 1000, false, true, null, null));
    }

    String close() throws Exception {
        Method method = PerfRunService.class.getDeclaredMethod("closeRoom", PerfRunService.PerfRunState.class, Map.class);
        method.setAccessible(true); return (String) method.invoke(service, state, Map.of());
    }

    @Test void evidenceStorageFailureDoesNotPreventBothBusinessCleanupSteps() throws Exception {
        when(runner.execute(any(), anyMap(), isNull(), eq(0), eq(0))).thenReturn(PerfEvidenceRepositoryTest.result("PASS", "{}"));
        when(evidence.record(anyLong(), any(), any(), anyInt(), anyString(), any())).thenThrow(new IllegalStateException("fake full disk"));
        assertThat(close()).isEmpty();
        verify(runner, times(2)).execute(any(), anyMap(), isNull(), eq(0), eq(0));
        verify(evidence).cleanup(1, 9, 1L, "SUCCESS", "", true);
        assertThat(state.status).isEqualTo("RUNNING");
        assertThat(state.evidenceFailureCount).isEqualTo(2);
    }

    @Test void endExceptionIsRecordedAndClearIsStillAttempted() throws Exception {
        when(runner.execute(any(), anyMap(), isNull(), eq(0), eq(0))).thenAnswer(call -> {
            ApiSpec spec = call.getArgument(0);
            if (spec.id() == 3) throw new IllegalStateException("fictional end exception");
            return PerfEvidenceRepositoryTest.result("PASS", "{}");
        });
        assertThat(close()).contains("fictional end exception");
        verify(runner, times(2)).execute(any(), anyMap(), isNull(), eq(0), eq(0));
        verify(evidence).record(eq(1L), eq(9L), eq(1L), eq(1), eq("结束游戏"), argThat(exec -> ApiExec.ERROR.equals(exec.verdict())));
        verify(evidence).cleanup(eq(1L), eq(9L), eq(1L), eq("FAILED"), contains("fictional end exception"), eq(false));
    }

    @Test void cleanupPersistenceFailureDoesNotRewriteBusinessSuccess() throws Exception {
        when(runner.execute(any(), anyMap(), isNull(), eq(0), eq(0))).thenReturn(PerfEvidenceRepositoryTest.result("PASS", "{}"));
        doThrow(new IllegalStateException("fake full disk")).when(evidence).cleanup(anyLong(), anyLong(), any(), anyString(), anyString(), anyBoolean());
        assertThat(close()).isEmpty();
        assertThat(state.loopEvidenceMissing).isTrue();
        assertThat(state.status).isEqualTo("RUNNING");
    }
}
