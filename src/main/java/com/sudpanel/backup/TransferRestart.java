package com.sudpanel.backup;

import com.sudpanel.config.AppProperties;
import com.sudpanel.launcher.RestartWorker;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.zip.ZipFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** 导入响应返回后的一次性原生应用重启；必须先确认独立接力进程就绪。 */
@Component
public final class TransferRestart {
    public static final long RESPONSE_GRACE_MS = 800;
    @FunctionalInterface interface WorkerStarter { AutoCloseable start(RestartWorker.Spec spec) throws Exception; }
    @FunctionalInterface interface Scheduler { void schedule(long delayMs, Runnable action); }
    private final Supplier<RestartWorker.Spec> supported;
    private final WorkerStarter starter;
    private final Scheduler scheduler;
    private final Runnable shutdown;
    private final AtomicBoolean requested = new AtomicBoolean();

    @Autowired public TransferRestart(ConfigurableApplicationContext context, AppProperties props) {
        this(() -> runtime(context, props), TransferRestart::startWorker, (delay, action) ->
                Thread.ofPlatform().name("sudpanel-transfer-restart").daemon(true).start(() -> {
                    try { Thread.sleep(delay); action.run(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }), () -> { try { context.close(); } finally { System.exit(0); } });
    }

    TransferRestart(Supplier<RestartWorker.Spec> supported, WorkerStarter starter, Scheduler scheduler, Runnable shutdown) {
        this.supported = supported; this.starter = starter; this.scheduler = scheduler; this.shutdown = shutdown;
    }

    public void requireSupported() {
        if (requested.get()) throw new IllegalStateException("应用正在重启，请等待重新连接");
        supported.get();
    }

    public void restartAfterResponse() {
        if (!requested.compareAndSet(false, true)) throw new IllegalStateException("应用正在重启，请等待重新连接");
        AutoCloseable worker = null;
        try {
            RestartWorker.Spec spec = supported.get();
            worker = starter.start(spec); // READY前绝不关闭Spring或退出当前进程。
            scheduler.schedule(RESPONSE_GRACE_MS, shutdown);
        } catch (Exception failure) {
            if (worker != null) try { worker.close(); } catch (Exception ignored) { }
            requested.set(false);
            throw new IllegalStateException("自动重启未就绪，当前应用保持运行；请重试或手动退出后重新打开", failure);
        }
    }

    private static RestartWorker.Spec runtime(ConfigurableApplicationContext context, AppProperties props) {
        try {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                    || !Boolean.parseBoolean(System.getProperty("sudpanel.packaged", "false"))) throw new IOException();
            String nativeCommand = ProcessHandle.current().info().command().orElseThrow(IOException::new);
            String portValue = context.getEnvironment().getProperty("server.port", "18790");
            Path db = Path.of(props.dbPath());
            if (!db.isAbsolute() || !"sudpanel.db".equals(db.getFileName().toString()) || db.getParent() == null
                    || !"data".equals(db.getParent().getFileName().toString())) throw new IOException();
            Path root = db.getParent().getParent().toRealPath();
            var spec = new RestartWorker.Spec(ProcessHandle.current().pid(), Path.of(nativeCommand).toRealPath().toString(), root.toString(),
                    Integer.parseInt(portValue), Boolean.getBoolean("java.awt.headless"), "/usr/bin/true".equals(props.adbPath()));
            validateRuntime(spec, System.getProperty("java.class.path", ""), Path.of(System.getProperty("java.home")), System.getenv("APPDIR"));
            return spec;
        } catch (Exception failure) {
            throw new IllegalStateException("自动重启仅支持完整原生 SudPanel.app；开发实例或不完整安装包请手动重启", failure);
        }
    }

    static Path validateRuntime(RestartWorker.Spec spec, String classpath, Path javaHome, String appDirEnvironment) throws IOException {
        Path appDir = RestartWorker.validateFiles(spec), contents = appDir.getParent();
        if (appDirEnvironment != null && !appDirEnvironment.isBlank() && !Path.of(appDirEnvironment).toRealPath().equals(appDir)) throw new IOException();
        String[] entries = classpath.split(java.io.File.pathSeparator, -1);
        if (entries.length != 1 || entries[0].isBlank()) throw new IOException();
        Path jar = Path.of(entries[0]).toRealPath();
        if (!Files.isRegularFile(jar) || !jar.getParent().equals(appDir)) throw new IOException();
        Path home = javaHome.toRealPath(), runtime = contents.resolve("runtime").toRealPath();
        Path java = home.resolve("bin/java").toRealPath();
        if (!home.startsWith(runtime) || !java.startsWith(runtime) || !Files.isExecutable(java)) throw new IOException();
        try (var zip = new ZipFile(jar.toFile())) {
            if (zip.getEntry("BOOT-INF/classes/com/sudpanel/launcher/RestartWorker.class") == null
                    || zip.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class") == null) throw new IOException();
        }
        return jar;
    }

    private static AutoCloseable startWorker(RestartWorker.Spec spec) throws Exception {
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path jar = validateRuntime(spec, System.getProperty("java.class.path", ""), javaHome, System.getenv("APPDIR"));
        var command = List.of(javaHome.resolve("bin/java").toRealPath().toString(), "-Xmx48m", "-XX:-UsePerfData", "-Djava.awt.headless=true",
                "-Dloader.main=" + RestartWorker.class.getName(), "-cp", jar.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher");
        var builder = new ProcessBuilder(command).directory(Path.of("/").toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
        RestartWorker.minimalEnvironment(builder.environment());
        Process worker = builder.start();
        boolean ready = false;
        ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor();
        try {
            try (var out = worker.getOutputStream()) { spec.write(out); }
            Future<String> line = reader.submit(() -> {
                var text = new StringBuilder();
                for (int c; (c = worker.getInputStream().read()) != -1;) {
                    if (c == '\n') return text.toString();
                    if (text.length() >= 16) throw new IOException();
                    text.append((char)c);
                }
                return text.toString();
            });
            if (!"READY".equals(line.get(5, TimeUnit.SECONDS)) || !worker.isAlive()) throw new IOException();
            ready = true;
            return () -> { if (worker.isAlive()) worker.destroyForcibly(); };
        } finally {
            reader.shutdownNow();
            try { worker.getInputStream().close(); } catch (IOException ignored) { }
            if (!ready && worker.isAlive()) worker.destroyForcibly();
        }
    }
}
