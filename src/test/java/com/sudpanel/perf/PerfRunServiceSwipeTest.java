package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sudpanel.adb.AdbClient;
import com.sudpanel.adb.Device;
import com.sudpanel.config.AppProperties;
import com.sudpanel.locator.UiLocator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

/** All display geometry and device identifiers are synthetic; no ADB process is created. */
class PerfRunServiceSwipeTest {
    private static final String SERIAL = "fixture-swipe-device";
    private AdbClient adb;
    private PerfRunService service;

    @BeforeEach void setup() {
        adb = mock(AdbClient.class);
        when(adb.devices()).thenReturn(List.of(new Device(SERIAL, "device", "Fixture")));
        service = new PerfRunService(adb, mock(UiLocator.class), mock(PerfCollector.class),
                mock(PerfRepository.class), mock(PerfJudge.class), mock(AppProperties.class),
                mock(CalibrationService.class));
    }

    @ParameterizedTest
    @CsvSource({
        "720,1280,0,360,256,1024",
        "1080,1920,0,540,384,1536",
        "720,1280,1,640,144,576",
        "720,1280,3,640,144,576",
        "1080,1920,2,540,384,1536"
    })
    void bothDirectionsStayWithinCurrentRotatedDisplay(int width, int height, int rotation,
                                                       int x, int top, int bottom) {
        layout("Fixture\nPhysical size: " + width + "x" + height + "\nSurfaceOrientation: " + rotation);
        ReflectionTestUtils.invokeMethod(service, "swipeDown", SERIAL);
        ReflectionTestUtils.invokeMethod(service, "swipeUp", SERIAL);
        verify(adb).shell(SERIAL, "input swipe " + x + " " + bottom + " " + x + " " + top + " 300", 10);
        verify(adb).shell(SERIAL, "input swipe " + x + " " + top + " " + x + " " + bottom + " 300", 10);
    }

    @Test void overrideSizeAndNewRotationAreReadForEachSwipe() {
        when(adb.shell(eq(SERIAL), contains("wm size"), eq(10L))).thenReturn(
                new AdbClient.ExecResult(0, "Fixture\nPhysical size: 1080x1920\nOverride size: 600x1000\nSurfaceOrientation: 0", ""),
                new AdbClient.ExecResult(0, "Fixture\nPhysical size: 1080x1920\nOverride size: 600x1000\nSurfaceOrientation: 1", ""));
        ReflectionTestUtils.invokeMethod(service, "swipeDown", SERIAL);
        ReflectionTestUtils.invokeMethod(service, "swipeDown", SERIAL);
        verify(adb).shell(SERIAL, "input swipe 300 800 300 200 300", 10);
        verify(adb).shell(SERIAL, "input swipe 500 480 500 120 300", 10);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "Fixture\nSurfaceOrientation: 0",
        "Fixture\nPhysical size: 720x1280",
        "Fixture\nPhysical size: 0x1280\nSurfaceOrientation: 0",
        "Fixture\nPhysical size: 1x1\nSurfaceOrientation: 0"
    })
    void unknownOrInvalidLayoutNeverFallsBackToFixedCoordinates(String response) {
        layout(response);
        for (String direction : List.of("swipeDown", "swipeUp")) {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, direction, SERIAL))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("已取消滑动");
        }
        verify(adb, never()).shell(anyString(), startsWith("input swipe"), anyLong());
    }

    @Test void disconnectedDeviceOrReadFailureDoesNotSwipeOrExposeRawError() {
        when(adb.devices()).thenReturn(List.of());
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "swipeDown", SERIAL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已取消滑动");
        verify(adb, never()).shell(anyString(), anyString(), anyLong());
        when(adb.devices()).thenReturn(List.of(new Device(SERIAL, "device", "Fixture")));
        when(adb.shell(eq(SERIAL), contains("wm size"), eq(10L)))
                .thenThrow(new IllegalStateException("fixture-private-device-error"));
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(service, "swipeUp", SERIAL))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已取消滑动")
                .hasMessageNotContaining("fixture-private-device-error");
        verify(adb, never()).shell(anyString(), startsWith("input swipe"), anyLong());
    }

    private void layout(String response) {
        when(adb.shell(eq(SERIAL), contains("wm size"), eq(10L)))
                .thenReturn(new AdbClient.ExecResult(0, response, ""));
    }
}
