package com.sudpanel.adb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.sudpanel.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class AdbProcessRunnerTest {
    @TempDir Path dir;

    Path script(String body) throws Exception {
        Path executable = Files.createTempFile(dir, "fake-adb-", ".sh");
        Files.writeString(executable, "#!/bin/sh\n" + body + "\n");
        executable.toFile().setExecutable(true);
        return executable;
    }

    String text(byte[] bytes) { return new String(bytes, StandardCharsets.UTF_8); }

    @Test void drainsBothPipesCompletelyWithoutDeadlock() throws Exception {
        Path fake = script("i=0; while [ $i -lt 8000 ]; do printf 'stderr-%05d-abcdefghijklmnop\\n' \"$i\" >&2; i=$((i+1)); done\n"
                + "i=0; while [ $i -lt 8000 ]; do printf 'stdout-%05d-abcdefghijklmnop\\n' \"$i\"; i=$((i+1)); done");
        var result = AdbProcessRunner.run(List.of(fake.toString()), 8_000);
        assertThat(result.exitCode()).isZero();
        assertThat(text(result.out()).lines().count()).isEqualTo(8000);
        assertThat(text(result.err()).lines().count()).isEqualTo(8000);
        assertThat(text(result.out())).endsWith("stdout-07999-abcdefghijklmnop\n");
        assertThat(text(result.err())).endsWith("stderr-07999-abcdefghijklmnop\n");
    }

    @Test void timeoutIsBoundedAndKillsChildrenHoldingThePipes() throws Exception {
        Path childPid = dir.resolve("child.pid");
        Path fake = script("sleep 30 &\nprintf '%s' $! > '" + childPid + "'\nprintf partial; sleep 0.2\nwait");
        long start = System.nanoTime();
        var result = AdbProcessRunner.run(List.of(fake.toString()), 1500);
        assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(3000);
        assertThat(result.exitCode()).isEqualTo(-1);
        assertThat(text(result.out())).isEqualTo("partial");
        assertThat(text(result.err())).contains("timeout");
        long child = Long.parseLong(Files.readString(childPid));
        for (int i = 0; i < 100 && ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false); i++) Thread.sleep(10);
        assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test void interruptStopsCommandAndRetainsInterruptFlag() throws Exception {
        Path pid = dir.resolve("parent.pid");
        Path fake = script("printf '%s' $$ > '" + pid + "'\nsleep 30");
        AtomicReference<AdbProcessRunner.Result> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = Thread.ofPlatform().start(() -> {
            result.set(AdbProcessRunner.run(List.of(fake.toString()), 30_000));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        for (int i = 0; i < 100 && !Files.exists(pid); i++) Thread.sleep(10);
        assertThat(Files.exists(pid)).isTrue();
        worker.interrupt();
        worker.join(2000);
        assertThat(worker.isAlive()).isFalse();
        assertThat(interrupted).isTrue();
        assertThat(result.get().exitCode()).isEqualTo(-1);
        assertThat(text(result.get().err())).contains("interrupted");
    }

    @Test void failedDeviceEnumerationDoesNotLookLikeAnEmptySuccessfulList() throws Exception {
        Path fake = script("printf 'adb failed' >&2\nexit 2");
        AppProperties props = mock(AppProperties.class);
        when(props.adbPath()).thenReturn(fake.toString());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AdbClient(props).devices())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ADB 设备查询失败");
    }

    @Test void deviceEnumerationSkipsDaemonNoiseAndKeepsOfflineAndUnauthorized() throws Exception {
        Path fake = script("printf 'daemon started successfully\\nList of devices attached\\nfake-online device model:Fake\\nfake-off offline\\nfake-auth unauthorized\\n'");
        AppProperties props = mock(AppProperties.class);
        when(props.adbPath()).thenReturn(fake.toString());
        assertThat(new AdbClient(props).devices()).extracting(Device::status)
                .containsExactly("device", "offline", "unauthorized");
    }

    @Test void binaryOutputAndStderrAreSeparated() throws Exception {
        Path fake = script("printf '\\211PNG\\r\\n\\032\\n'\nprintf warning >&2");
        var result = AdbProcessRunner.run(List.of(fake.toString()), 1000);
        assertThat(result.out()).containsExactly((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G',
                (byte) 13, (byte) 10, (byte) 26, (byte) 10);
        assertThat(text(result.err())).isEqualTo("warning");
    }

    @Test void immediatelyExitedParentCannotLeaveBlockedOutputReaders() throws Exception {
        Path pid = dir.resolve("orphan.pid");
        Path fake = script("sleep 30 &\nprintf '%s' $! > '" + pid + "'\nprintf parent-done\nexit 0");
        try {
            long start = System.nanoTime();
            var result = AdbProcessRunner.run(List.of(fake.toString()), 1500);
            assertThat(Duration.ofNanos(System.nanoTime() - start).toMillis()).isLessThan(2500);
            assertThat(result.exitCode()).isZero();
            assertThat(text(result.out())).isEqualTo("parent-done");
            Thread.sleep(100);
            assertThat(Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.isAlive() && t.getName().equals("sudpanel-adb-output")).count()).isZero();
        } finally {
            // ProcessHandle 枚举不承诺捕获瞬间成为孤儿的后代；测试显式结束自己创建的孤儿。
            if (Files.exists(pid)) ProcessHandle.of(Long.parseLong(Files.readString(pid))).ifPresent(ProcessHandle::destroyForcibly);
        }
    }
}
