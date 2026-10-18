package com.sudpanel.adb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 同时排空两个管道；进程、输出回收共享一个截止时间，避免读取先于 timeout 而无限等待。 */
final class AdbProcessRunner {
    private AdbProcessRunner() { }

    record Result(int exitCode, byte[] out, byte[] err) { }

    static Result run(List<String> command, long timeoutMillis) {
        return run(command, timeoutMillis, Integer.MAX_VALUE);
    }

    /** 单次命令每个输出管道的保留上限；超限结束本次命令，不影响其它 adb 进程。 */
    static Result run(List<String> command, long timeoutMillis, int maxOutputBytes) {
        if (maxOutputBytes <= 0) throw new IllegalArgumentException("maxOutputBytes must be positive");
        if (!command.isEmpty() && "disabled".equalsIgnoreCase(command.getFirst())) {
            return new Result(-1, new byte[0],
                    "ADB is disabled. Set SUDPANEL_ADB_PATH explicitly to enable device access."
                            .getBytes(StandardCharsets.UTF_8));
        }
        Process process = null;
        Drain stdout = null, stderr = null;
        Set<ProcessHandle> descendants = new LinkedHashSet<>();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMillis));
        try {
            process = new ProcessBuilder(command).redirectErrorStream(false).start();
            stdout = new Drain(process.getInputStream(), process, maxOutputBytes);
            stderr = new Drain(process.getErrorStream(), process, maxOutputBytes);
            stdout.start();
            stderr.start();
            // 记录本地子进程，在超时和中断时一起结束；不触碰独立运行的 adb server。
            while (process.isAlive()) {
                process.descendants().forEach(descendants::add);
                if (stdout.limitExceeded || stderr.limitExceeded) {
                    terminateAndDrain(process, descendants, stdout, stderr);
                    return failure(stdout, stderr, "process output exceeded " + maxOutputBytes + " bytes");
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    terminateAndDrain(process, descendants, stdout, stderr);
                    return failure(stdout, stderr, "timeout after " + timeoutMillis + "ms");
                }
                process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
            }
            if (!stdout.await(deadline) || !stderr.await(deadline)) {
                terminateAndDrain(process, descendants, stdout, stderr);
                return failure(stdout, stderr, "timeout reading process output after " + timeoutMillis + "ms");
            }
            if (stdout.limitExceeded || stderr.limitExceeded) {
                return failure(stdout, stderr, "process output exceeded " + maxOutputBytes + " bytes");
            }
            if (stdout.failure != null || stderr.failure != null) {
                return failure(stdout, stderr, "could not read process output");
            }
            return new Result(process.exitValue(), stdout.bytes(), stderr.bytes());
        } catch (InterruptedException interrupted) {
            if (process != null) terminateAndDrain(process, descendants, stdout, stderr);
            Thread.currentThread().interrupt();
            return failure(stdout, stderr, "interrupted");
        } catch (IOException failedStart) {
            if (process != null) terminate(process, descendants);
            return failure(stdout, stderr, "could not start adb: " + failedStart.getMessage());
        } finally {
            if (process != null && process.isAlive()) terminate(process, descendants);
            if (stdout != null) stdout.stopAndClose();
            if (stderr != null) stderr.stopAndClose();
        }
    }

    private static Result failure(Drain out, Drain err, String message) {
        byte[] oldErr = err == null ? new byte[0] : err.bytes();
        String detail = new String(oldErr, StandardCharsets.UTF_8);
        if ((out != null && out.alive()) || (err != null && err.alive())) message += "; output cleanup incomplete";
        return new Result(-1, out == null ? new byte[0] : out.bytes(),
                (detail + (detail.isBlank() ? "" : "\n") + message).getBytes(StandardCharsets.UTF_8));
    }

    private static void terminateAndDrain(Process process, Set<ProcessHandle> seen, Drain out, Drain err) {
        terminate(process, seen);
        // 已杀掉本次命令后，只给管道一个固定的回收窗口，保留超时前已产生的有限输出。
        long cleanupDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        try {
            if (out != null) out.await(cleanupDeadline);
            if (err != null) err.await(cleanupDeadline);
            if (out != null) out.stopAndClose();
            if (err != null) err.stopAndClose();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void terminate(Process process, Set<ProcessHandle> seen) {
        process.descendants().forEach(seen::add);
        // 子进程先结束，以免父进程退出后丢失子树。只终止本次命令所派生的进程。
        for (ProcessHandle child : seen) if (child.isAlive()) child.destroyForcibly();
        if (process.isAlive()) process.destroyForcibly();
    }

    private static final class Drain {
        private final InputStream input;
        private final Process process;
        private volatile boolean stop;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Thread thread;
        private volatile IOException failure;
        private volatile boolean limitExceeded;
        private final int maxOutputBytes;

        Drain(InputStream input, Process process, int maxOutputBytes) {
            this.input = input; this.process = process; this.maxOutputBytes = maxOutputBytes;
        }

        void start() {
            thread = Thread.ofPlatform().daemon(true).name("sudpanel-adb-output").start(() -> {
                try (input) {
                    byte[] buffer = new byte[8192];
                    while (!stop) {
                        int available = input.available();
                        if (available > 0) {
                            int length = input.read(buffer, 0, Math.min(buffer.length, available));
                            if (length < 0) break;
                            synchronized (output) {
                                int retained = Math.min(length, maxOutputBytes - output.size());
                                if (retained > 0) output.write(buffer, 0, retained);
                                if (retained < length) limitExceeded = true;
                            }
                        } else if (!process.isAlive()) {
                            break;
                        } else {
                            Thread.sleep(5);
                        }
                    }
                } catch (IOException error) {
                    failure = error;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        boolean await(long deadline) throws InterruptedException {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) thread.join(TimeUnit.NANOSECONDS.toMillis(remaining) + 1);
            return !thread.isAlive();
        }

        boolean alive() { return thread != null && thread.isAlive(); }

        void stopAndClose() {
            if (thread == null || !thread.isAlive()) return;
            stop = true;
            if (thread != null) thread.interrupt();
            // 某些 ProcessPipeInputStream 的 close 会等待 read 的锁；它不能阻塞调用线程。
            Thread.ofPlatform().daemon(true).name("sudpanel-adb-close").start(() -> {
                try { input.close(); } catch (IOException ignored) { }
            });
        }

        byte[] bytes() { synchronized (output) { return output.toByteArray(); } }
    }
}
