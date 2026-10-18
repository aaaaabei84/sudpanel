package com.sudpanel.backup;

import com.sudpanel.launcher.RestartWorker;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TransferRestartTest {
    @TempDir Path temp;
    RestartWorker.Spec spec;
    Path home, jar, appDir;
    void fixture() throws Exception {
        Path contents = temp.resolve("SudPanel.app/Contents"); appDir = contents.resolve("app");
        home = contents.resolve("runtime/Contents/Home");
        Files.createDirectories(appDir); Files.createDirectories(home.resolve("bin"));
        Path executable = contents.resolve("MacOS/SudPanel"); Files.createDirectories(executable.getParent());
        Files.writeString(executable, "fixture"); assertTrue(executable.toFile().setExecutable(true));
        Files.writeString(home.resolve("bin/java"), "fixture"); assertTrue(home.resolve("bin/java").toFile().setExecutable(true));
        Files.writeString(appDir.resolve("packaged.marker"), "fixture");
        Path root = temp.resolve("private-data"); Files.createDirectories(root.resolve("data"));
        spec = new RestartWorker.Spec(42, executable.toString(), root.toString(), 18829, true, true);
        jar = appDir.resolve("fixture.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : new String[]{"BOOT-INF/classes/com/sudpanel/launcher/RestartWorker.class", "org/springframework/boot/loader/launch/PropertiesLauncher.class"}) {
                zip.putNextEntry(new ZipEntry(name)); zip.write(1); zip.closeEntry();
            }
        }
    }

    @Test void acceptsOnlyMatchingNativeBundleAndRuntime() throws Exception {
        fixture(); assertEquals(jar.toRealPath(), TransferRestart.validateRuntime(spec, jar.toString(), home, appDir.toString()));
        assertThrows(IOException.class, () -> TransferRestart.validateRuntime(spec, jar.toString(), home, temp.toString()));
        assertThrows(IOException.class, () -> TransferRestart.validateRuntime(spec, jar + java.io.File.pathSeparator + jar, home, null));
        assertThrows(IOException.class, () -> TransferRestart.validateRuntime(spec, jar.toString(), Path.of(System.getProperty("java.home")), null));
    }

    @Test void refusesIncompletePackageBeforeShutdown() throws Exception {
        fixture(); Files.delete(home.resolve("bin/java"));
        assertThrows(IOException.class, () -> TransferRestart.validateRuntime(spec, jar.toString(), home, null));
        Files.delete(appDir.resolve("packaged.marker"));
        assertThrows(IOException.class, () -> TransferRestart.validateRuntime(spec, jar.toString(), home, null));
    }

    @Test void refusesForeignClasspathAndJarWithoutWorker() throws Exception {
        fixture(); Path foreign = temp.resolve("foreign.jar"); Files.copy(jar, foreign);
        assertThrows(IOException.class, () -> TransferRestart.validateRuntime(spec, foreign.toString(), home, null));
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) { zip.putNextEntry(new ZipEntry("old-package")); zip.closeEntry(); }
        assertThrows(IOException.class, () -> TransferRestart.validateRuntime(spec, jar.toString(), home, null));
    }

    @Test void readinessPrecedesDelayedShutdownAndDuplicateIsRejected() throws Exception {
        fixture(); var ready = new AtomicBoolean(); var exited = new AtomicBoolean(); var scheduled = new AtomicReference<Runnable>();
        TransferRestart restart = new TransferRestart(() -> spec, value -> { ready.set(true); return () -> fail("成功路径不取消worker"); },
                (delay, action) -> { assertTrue(ready.get()); assertEquals(800, delay); assertFalse(exited.get()); scheduled.set(action); }, () -> exited.set(true));
        restart.requireSupported(); assertFalse(ready.get());
        restart.restartAfterResponse(); assertFalse(exited.get()); assertNotNull(scheduled.get());
        assertThrows(IllegalStateException.class, restart::restartAfterResponse);
        assertThrows(IllegalStateException.class, restart::requireSupported);
        scheduled.get().run(); assertTrue(exited.get());
    }

    @Test void unsupportedOrFailedReadyKeepsCurrentServiceAndAllowsRetry() throws Exception {
        fixture(); var scheduled = new AtomicInteger(); var calls = new AtomicInteger();
        TransferRestart restart = new TransferRestart(() -> spec, value -> { if (calls.incrementAndGet() == 1) throw new IOException("fixture readiness failed"); return () -> {}; },
                (delay, action) -> scheduled.incrementAndGet(), () -> fail("不能退出JUnit"));
        assertThrows(IllegalStateException.class, restart::restartAfterResponse); assertEquals(0, scheduled.get());
        restart.restartAfterResponse(); assertEquals(1, scheduled.get());
        var unsupported = new TransferRestart(() -> { throw new IllegalStateException("仅原生"); }, value -> { fail("不能启动worker"); return () -> {}; },
                (delay, action) -> fail("不能关闭服务"), () -> fail("不能退出"));
        assertThrows(IllegalStateException.class, unsupported::requireSupported);
        assertThrows(IllegalStateException.class, unsupported::restartAfterResponse);
    }

    @Test void schedulerFailureCancelsOnlyPreparedWorkerAndCanRetry() throws Exception {
        fixture(); var cancelled = new AtomicInteger(); var attempt = new AtomicInteger();
        TransferRestart restart = new TransferRestart(() -> spec, value -> cancelled::incrementAndGet,
                (delay, action) -> { if (attempt.getAndIncrement() == 0) throw new IllegalStateException("fixture schedule failed"); }, () -> fail("不能退出"));
        assertThrows(IllegalStateException.class, restart::restartAfterResponse); assertEquals(1, cancelled.get());
        restart.requireSupported(); restart.restartAfterResponse(); assertEquals(1, cancelled.get());
    }
}
