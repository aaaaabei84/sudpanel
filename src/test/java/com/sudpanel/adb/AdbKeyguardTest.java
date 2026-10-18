package com.sudpanel.adb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sudpanel.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(15)
class AdbKeyguardTest {
    @TempDir Path dir;

    static Stream<Arguments> policyFields() {
        return Stream.of(
                Arguments.of("mInputRestricted=true", AdbClient.KeyguardState.LOCKED),
                Arguments.of("mInputRestricted=false", AdbClient.KeyguardState.UNLOCKED),
                Arguments.of("  isKeyguardShowing = true\n", AdbClient.KeyguardState.LOCKED),
                Arguments.of("isKeyguardShowing()=false", AdbClient.KeyguardState.UNLOCKED),
                Arguments.of("mKeyguardShowing: true", AdbClient.KeyguardState.LOCKED),
                Arguments.of("mKeyguardShowing=false\nmInputRestricted=false", AdbClient.KeyguardState.UNLOCKED),
                Arguments.of("mKeyguardShowing=false\nmInputRestricted=true", AdbClient.KeyguardState.LOCKED),
                Arguments.of("mInputRestricted=false\nisKeyguardShowing=true", AdbClient.KeyguardState.LOCKED),
                Arguments.of("mInputRestricted=true\nmInputRestricted=false", AdbClient.KeyguardState.LOCKED),
                Arguments.of("mCurrentFocus=Window{abc u0 com.fictional.game/MainActivity}", AdbClient.KeyguardState.UNKNOWN),
                Arguments.of("showing=false\nmShowing=false\nmIsShowing=false", AdbClient.KeyguardState.UNKNOWN),
                Arguments.of("not_mInputRestricted=false\nisKeyguardShowingCached=false", AdbClient.KeyguardState.UNKNOWN),
                Arguments.of("mInputRestricted=falseish\nmKeyguardShowing=0", AdbClient.KeyguardState.UNKNOWN),
                Arguments.of("mInputRestricted=unknown", AdbClient.KeyguardState.UNKNOWN),
                Arguments.of("", AdbClient.KeyguardState.UNKNOWN));
    }

    @ParameterizedTest @MethodSource("policyFields")
    void onlyExplicitPolicyFieldsDecideStatus(String output, AdbClient.KeyguardState expected) {
        assertThat(AdbClient.parseKeyguardState(new AdbClient.ExecResult(0, output, ""))).isEqualTo(expected);
    }

    @Test void missingDeniedFailedAndOversizedOutputNeverClaimsUnlocked() {
        assertThat(AdbClient.parseKeyguardState(null)).isEqualTo(AdbClient.KeyguardState.UNKNOWN);
        for (var result : List.of(
                new AdbClient.ExecResult(-1, "mInputRestricted=false", "timeout"),
                new AdbClient.ExecResult(0, null, ""),
                new AdbClient.ExecResult(0, "mInputRestricted=false\nPermission Denial: not allowed", ""),
                new AdbClient.ExecResult(0, "mInputRestricted=false", "java.lang.SecurityException"),
                new AdbClient.ExecResult(0, "mInputRestricted=false", "permission denied"),
                new AdbClient.ExecResult(0, "mInputRestricted=false", "Not allowed to dump"),
                new AdbClient.ExecResult(0, "mInputRestricted=false\n" + "x".repeat(65_536), ""),
                new AdbClient.ExecResult(0, "mInputRestricted=false", "x".repeat(65_537))))
            assertThat(AdbClient.parseKeyguardState(result)).isEqualTo(AdbClient.KeyguardState.UNKNOWN);
    }

    Path fake(String body) throws Exception {
        Path executable = Files.createTempFile(dir, "fake-adb-", ".sh");
        Files.writeString(executable, "#!/bin/sh\n" + body + "\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        return executable;
    }

    AdbClient client(Path executable) {
        AppProperties props = mock(AppProperties.class);
        when(props.adbPath()).thenReturn(executable.toString());
        return new AdbClient(props);
    }

    @Test void executableReceivesOnlyOneReadOnlyPolicyCommand() throws Exception {
        Path calls = dir.resolve("calls.txt");
        Path executable = fake("[ \"$#\" -eq 4 ] || exit 71\n"
                + "[ \"$1\" = '-s' ] && [ \"$2\" = 'fictional-device' ] && [ \"$3\" = 'shell' ] && [ \"$4\" = 'dumpsys window policy' ] || exit 72\n"
                + "printf '%s\\n' \"$@\" > '" + calls + "'\n"
                + "printf 'mInputRestricted=true\\n'");
        assertThat(client(executable).keyguardState("fictional-device")).isEqualTo(AdbClient.KeyguardState.LOCKED);
        assertThat(Files.readAllLines(calls)).containsExactly("-s", "fictional-device", "shell", "dumpsys window policy");
    }

    @Test void failedOrPermissionDeniedFakeExecutableRemainsUnknown() throws Exception {
        assertThat(client(fake("printf 'mInputRestricted=false\\n'; exit 2")).keyguardState("fictional-device"))
                .isEqualTo(AdbClient.KeyguardState.UNKNOWN);
        assertThat(client(fake("printf 'mInputRestricted=false\\n'; printf 'Permission Denial' >&2")).keyguardState("fictional-device"))
                .isEqualTo(AdbClient.KeyguardState.UNKNOWN);
        assertThat(client(dir.resolve("missing-fake-adb")).keyguardState("fictional-device"))
                .isEqualTo(AdbClient.KeyguardState.UNKNOWN);
    }

    @Test void blankSerialDoesNotSpawnAnyProcess() throws Exception {
        Path marker = dir.resolve("must-not-exist");
        var adb = client(fake("touch '" + marker + "'\nprintf 'mInputRestricted=false\\n'"));
        assertThat(adb.keyguardState(null)).isEqualTo(AdbClient.KeyguardState.UNKNOWN);
        assertThat(adb.keyguardState("  ")).isEqualTo(AdbClient.KeyguardState.UNKNOWN);
        assertThat(Files.exists(marker)).isFalse();
    }

    @Test void keyguardOutputBeyond64KiBIsUnknownEvenWithUnlockedPrefix() throws Exception {
        Path executable = fake("printf 'mInputRestricted=false\\n'; printf '%066000d' 0");
        assertThat(client(executable).keyguardState("fictional-device")).isEqualTo(AdbClient.KeyguardState.UNKNOWN);
    }

    @Test void boundedRunnerStopsFloodAndRetainsAtMostConfiguredBytesPerPipe() throws Exception {
        Path executable = fake("while :; do printf 'abcdefghijklmnopqrstuvwxyz0123456789\\n'; done");
        long started = System.nanoTime();
        var result = AdbProcessRunner.run(List.of(executable.toString()), 10_000, 1024);
        assertThat(Duration.ofNanos(System.nanoTime() - started).toMillis()).isLessThan(2500);
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(result.out().length).isLessThanOrEqualTo(1024);
        assertThat(new String(result.err(), StandardCharsets.UTF_8)).contains("output exceeded 1024 bytes");
    }

    @Test void stderrFloodIsBoundedAndExactLimitDoesNotFail() throws Exception {
        var flooded = AdbProcessRunner.run(List.of(fake("printf '%02048d' 0 >&2").toString()), 2000, 1024);
        assertThat(flooded.exitCode()).isEqualTo(-1);
        // The fixed diagnostic is appended to retained stderr, not arbitrary device output.
        assertThat(flooded.err().length).isLessThan(1150);
        var exact = AdbProcessRunner.run(List.of(fake("printf '%01024d' 0").toString()), 2000, 1024);
        assertThat(exact.exitCode()).isZero();
        assertThat(exact.out()).hasSize(1024);
    }
}
