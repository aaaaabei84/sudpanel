package com.sudpanel.launcher;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RestartWorkerTest {
    @TempDir Path temp;
    RestartWorker.Spec fixture() throws Exception {
        Path contents = temp.resolve("SudPanel.app/Contents"); Files.createDirectories(contents.resolve("app")); Files.createDirectories(contents.resolve("MacOS"));
        Path executable = contents.resolve("MacOS/SudPanel"); Files.writeString(executable, "fixture"); assertTrue(executable.toFile().setExecutable(true));
        Files.writeString(contents.resolve("app/packaged.marker"), "fixture");
        Path root = temp.resolve("data-root"); Files.createDirectories(root.resolve("data"));
        return new RestartWorker.Spec(42, executable.toString(), root.toString(), 18829, true, true);
    }
    static class Host implements RestartWorker.Host {
        final List<String> calls = new ArrayList<>(); boolean exited = true, invalidParent, launchFailure;
        public void validateParent(RestartWorker.Spec spec) throws IOException { calls.add("validate"); if (invalidParent) throw new IOException(); }
        public void ready() { calls.add("ready"); }
        public boolean awaitParentExit(long pid, Duration limit) { calls.add("wait"); assertEquals(42, pid); assertEquals(Duration.ofSeconds(45), limit); return exited; }
        public void launch(RestartWorker.Spec spec) throws IOException { calls.add("launch"); if (launchFailure) throw new IOException(); }
    }
    @Test void exactStdinProtocolRoundTripsWithoutJvmArgsOrSecrets() throws Exception {
        var spec = fixture(); var out = new ByteArrayOutputStream(); spec.write(out);
        assertEquals(spec, RestartWorker.Spec.read(new ByteArrayInputStream(out.toByteArray())));
        assertThrows(IOException.class, () -> RestartWorker.Spec.read(new ByteArrayInputStream(new byte[4])));
        assertThrows(IllegalArgumentException.class, () -> new RestartWorker.Spec(0, "a", "b", 1, false, false));
        assertThrows(IllegalArgumentException.class, () -> new RestartWorker.Spec(42, "a", "b", 65536, false, false));
    }
    @Test void waitsForActualExitAfterReadyAndLaunchesOnlyOnce() throws Exception {
        Host host = new Host(); assertEquals(0, RestartWorker.run(fixture(), host));
        assertEquals(List.of("validate", "ready", "wait", "launch"), host.calls);
    }
    @Test void parentTimeoutNeverKillsOrStartsAnything() throws Exception {
        Host host = new Host(); host.exited = false; assertEquals(2, RestartWorker.run(fixture(), host));
        assertEquals(List.of("validate", "ready", "wait"), host.calls);
    }
    @Test void wrongParentRefusesBeforeReady() throws Exception {
        Host host = new Host(); host.invalidParent = true; assertEquals(2, RestartWorker.run(fixture(), host)); assertEquals(List.of("validate"), host.calls);
    }
    @Test void incompleteBundleRefusesBeforeParentOrReady() throws Exception {
        var spec = fixture(); Files.delete(Path.of(spec.executable())); Host host = new Host();
        assertEquals(2, RestartWorker.run(spec, host)); assertTrue(host.calls.isEmpty());
    }
    @Test void nativeLaunchFailureDoesNotRetry() throws Exception {
        Host host = new Host(); host.launchFailure = true; assertEquals(2, RestartWorker.run(fixture(), host));
        assertEquals(1, host.calls.stream().filter("launch"::equals).count());
    }
    @Test void cleanEnvironmentPreservesOnlySameRootPortAndExplicitIsolation() throws Exception {
        var spec = fixture(); var builder = RestartWorker.nativeCommand(spec);
        assertEquals(List.of(spec.executable(), "--sudpanel.adb-path=/usr/bin/true"), builder.command());
        assertEquals(spec.dataRoot(), builder.environment().get("SUDPANEL_DATA_DIR")); assertEquals("18829", builder.environment().get("SUDPANEL_PORT"));
        assertEquals("-Djava.awt.headless=true", builder.environment().get("JAVA_TOOL_OPTIONS")); assertFalse(builder.environment().containsKey("JDK_JAVA_OPTIONS"));
        var real = RestartWorker.nativeCommand(new RestartWorker.Spec(42, spec.executable(), spec.dataRoot(), 18790, false, false));
        assertEquals(List.of(spec.executable()), real.command()); assertFalse(real.environment().containsKey("JAVA_TOOL_OPTIONS"));
        Map<String,String> env = new HashMap<>(Map.of("API_SECRET", "fixture-private", "JAVA_TOOL_OPTIONS", "fixture-injection"));
        RestartWorker.minimalEnvironment(env); assertFalse(env.containsKey("API_SECRET")); assertFalse(env.containsKey("JAVA_TOOL_OPTIONS"));
    }
}
