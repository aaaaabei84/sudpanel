package com.sudpanel.adb;

import com.sudpanel.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdbDisabledTest {
    @Test void disabledRunnerDoesNotAttemptAProcess() {
        var result = AdbProcessRunner.run(List.of("disabled", "devices", "-l"), 1000);
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.out()).isEmpty();
        assertThat(new String(result.err(), StandardCharsets.UTF_8))
                .startsWith("ADB is disabled.");
    }

    @Test void disabledDeviceDiscoveryIsEmpty() {
        var properties = mock(AppProperties.class);
        when(properties.adbPath()).thenReturn("disabled");
        assertThat(new AdbClient(properties).devices()).isEmpty();
    }

    @Test void disabledScreenCaptureCannotReachDevice() {
        var result = AdbProcessRunner.run(
                List.of("DISABLED", "-s", "synthetic-device", "exec-out", "screencap", "-p"), 1000);
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.out()).isEmpty();
        assertThat(new String(result.err(), StandardCharsets.UTF_8)).contains("ADB is disabled.");
    }
}
