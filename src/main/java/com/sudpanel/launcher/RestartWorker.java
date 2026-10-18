package com.sudpanel.launcher;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** 一次性重启接力：只等指定父进程退出，再启动同一原生应用；不杀进程、不循环守护。 */
public final class RestartWorker {
    private static final int MAGIC = 0x53505231;
    public static final Duration PARENT_EXIT_LIMIT = Duration.ofSeconds(45);
    private RestartWorker() { }

    /** stdin 的有限协议只传重启位置，不携带数据库、配置、密钥或任意 JVM 参数。 */
    public record Spec(long parentPid, String executable, String dataRoot, int port, boolean headless, boolean disableAdb) {
        public Spec {
            if (parentPid <= 0 || port < 1 || port > 65535 || executable == null || dataRoot == null
                    || executable.length() > 4096 || dataRoot.length() > 4096)
                throw new IllegalArgumentException("重启参数无效");
        }
        public void write(OutputStream stream) throws IOException {
            var out = new DataOutputStream(stream);
            out.writeInt(MAGIC); out.writeLong(parentPid); out.writeUTF(executable); out.writeUTF(dataRoot);
            out.writeInt(port); out.writeBoolean(headless); out.writeBoolean(disableAdb); out.flush();
        }
        public static Spec read(InputStream stream) throws IOException {
            var in = new DataInputStream(stream);
            if (in.readInt() != MAGIC) throw new IOException("重启协议无效");
            return new Spec(in.readLong(), in.readUTF(), in.readUTF(), in.readInt(), in.readBoolean(), in.readBoolean());
        }
    }

    interface Host {
        void validateParent(Spec spec) throws Exception;
        void ready() throws Exception;
        boolean awaitParentExit(long pid, Duration limit) throws Exception;
        void launch(Spec spec) throws Exception;
    }

    static int run(Spec spec, Host host) {
        try {
            validateFiles(spec);
            host.validateParent(spec);
            host.ready();
            if (!host.awaitParentExit(spec.parentPid(), PARENT_EXIT_LIMIT)) return 2;
            // 等待期间应用可能被移动或删除；不改为启动另一个同名应用。
            validateFiles(spec);
            host.launch(spec);
            return 0;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); return 2;
        } catch (Exception failure) {
            return 2;
        }
    }

    public static Path validateFiles(Spec spec) throws IOException {
        Path executable = Path.of(spec.executable());
        if (!executable.isAbsolute() || !Files.isRegularFile(executable) || !Files.isExecutable(executable)) throw new IOException();
        executable = executable.toRealPath();
        Path macos = executable.getParent(), contents = macos == null ? null : macos.getParent();
        if (!"SudPanel".equals(executable.getFileName().toString()) || macos == null || !"MacOS".equals(macos.getFileName().toString())
                || contents == null || !"Contents".equals(contents.getFileName().toString()) || contents.getParent() == null
                || !contents.getParent().getFileName().toString().endsWith(".app")
                || !Files.isRegularFile(contents.resolve("app/packaged.marker"))) throw new IOException();
        Path root = Path.of(spec.dataRoot());
        if (!root.isAbsolute() || !Files.isDirectory(root) || !Files.isDirectory(root.resolve("data"))) throw new IOException();
        return contents.resolve("app").toRealPath();
    }

    static ProcessBuilder nativeCommand(Spec spec) {
        var command = new ArrayList<String>(); command.add(spec.executable());
        // 显式命令行保持隔离验证的禁ADB设置，不能靠继承任意JAVA_TOOL_OPTIONS。
        if (spec.disableAdb()) command.add("--sudpanel.adb-path=/usr/bin/true");
        var builder = new ProcessBuilder(command).directory(Path.of(spec.dataRoot()).toFile());
        minimalEnvironment(builder.environment());
        builder.environment().put("SUDPANEL_DATA_DIR", spec.dataRoot());
        builder.environment().put("SUDPANEL_PORT", Integer.toString(spec.port()));
        if (spec.headless()) builder.environment().put("JAVA_TOOL_OPTIONS", "-Djava.awt.headless=true");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder;
    }

    public static void minimalEnvironment(Map<String, String> env) {
        env.clear(); env.put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"); env.put("LANG", "en_US.UTF-8");
        env.put("__CF_USER_TEXT_ENCODING", "0:0:0");
    }

    public static void main(String[] args) {
        int status;
        try {
            if (args.length != 0) throw new IOException();
            Spec spec = Spec.read(System.in);
            status = run(spec, new Host() {
                private ProcessHandle parent;
                public void validateParent(Spec s) throws Exception {
                    if (s.parentPid() == ProcessHandle.current().pid()) throw new IOException();
                    parent = ProcessHandle.of(s.parentPid()).filter(ProcessHandle::isAlive).orElseThrow(IOException::new);
                    String command = parent.info().command().orElseThrow(IOException::new);
                    if (!Path.of(command).toRealPath().equals(Path.of(s.executable()).toRealPath())) throw new IOException();
                }
                public void ready() { System.out.println("READY"); System.out.flush(); }
                public boolean awaitParentExit(long pid, Duration limit) throws Exception {
                    try { parent.onExit().get(limit.toMillis(), TimeUnit.MILLISECONDS); return !parent.isAlive(); }
                    catch (java.util.concurrent.TimeoutException timeout) { return false; }
                }
                public void launch(Spec s) throws IOException { nativeCommand(s).start(); }
            });
        } catch (Exception failure) { status = 2; }
        if (status != 0) System.err.println("RESTART_FAILED"); // 不回显私密路径、环境或异常正文。
        System.exit(status);
    }
}
