package com.sudpanel.api.script;

import static org.assertj.core.api.Assertions.*;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiScriptSandboxIsolationTest {
    @TempDir Path dir;
    @Test void sameOsProfileRejectsHostReadsWritesNetworkAndForkEvenForNativeJava() throws Exception {
        Path secret = dir.resolve("not-a-classpath-resource.txt"), write = dir.resolve("must-not-exist.txt");
        Files.writeString(secret, "FICTIONAL_DENY_READ");
        try (var socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            var command = ApiScriptSandbox.workerCommand(ScriptOsProbe.class.getName());
            var builder = new ProcessBuilder(command).directory(Path.of("/").toFile()); builder.environment().clear(); builder.environment().put("__CF_USER_TEXT_ENCODING", "0:0:0");
            Process process = builder.start();
            try {
                process.getOutputStream().write((secret + "\n" + write + "\n" + socket.getLocalPort() + "\n").getBytes(StandardCharsets.UTF_8)); process.getOutputStream().close();
                assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(output).contains("READ_DENIED", "WRITE_DENIED", "NETWORK_DENIED", "FORK_DENIED", "HOME_CLEARED").doesNotContain("ALLOWED", "FICTIONAL_DENY_READ");
                assertThat(process.exitValue()).isZero(); assertThat(Files.exists(write)).isFalse();
            } finally { process.destroyForcibly(); }
        }
    }
    @Test void parentInterruptionTerminatesOwnWorkerAndPreservesInterruptFlag() throws Exception {
        var result = new CompletableFuture<String>();
        Thread worker = Thread.ofPlatform().start(() -> {
            try { ApiScriptSandbox.execute("while(true){}", new ApiScriptSandboxTest().input(), 5000); result.complete("unexpected-success"); }
            catch (ApiScriptSandbox.ScriptException e) { result.complete(e.type() + ":" + Thread.currentThread().isInterrupted()); }
        });
        Thread.sleep(250); worker.interrupt();
        assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo("INTERRUPTED:true"); worker.join(1000); assertThat(worker.isAlive()).isFalse();
        assertThat(ApiScriptSandbox.execute("pm.environment.set('next','ok')", new ApiScriptSandboxTest().input(), 1000).environmentWrites()).containsEntry("next", "ok");
    }
    @Test void missingOsIsolationFailsClosed() {
        String os = System.getProperty("os.name");
        try { System.setProperty("os.name", "Unsupported fixture OS"); assertThatThrownBy(() -> ApiScriptSandbox.execute("", new ApiScriptSandboxTest().input(), 1000)).hasMessageContaining("ISOLATION_UNAVAILABLE"); }
        finally { System.setProperty("os.name", os); }
    }
    @Test void strippedJavaRuntimeFailsClosedWithAnActionableSafeType() {
        String home=System.getProperty("java.home");
        try{System.setProperty("java.home",dir.toString());assertThatThrownBy(()->ApiScriptSandbox.execute("",new ApiScriptSandboxTest().input(),1000)).hasMessageContaining("ISOLATION_UNAVAILABLE");}
        finally{System.setProperty("java.home",home);}
    }
}
