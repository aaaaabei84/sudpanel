package com.sudpanel.api.script;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;

/** One execution, one OS-restricted JVM. Secrets travel only through bounded anonymous pipes. */
public final class ApiScriptSandbox {
    public static final int MAX_SCRIPT_BYTES = 64 * 1024;
    public static final int MAX_INPUT_CHARS = 2 * 1024 * 1024;
    public static final int MAX_OUTPUT_BYTES = 2 * 1024 * 1024;
    static final int MAX_PIPE_INPUT = 8 * 1024 * 1024;
    static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Path SANDBOX = Path.of("/usr/bin/sandbox-exec");
    private ApiScriptSandbox() {}

    public record Result(Map<String,String> environmentWrites, Map<String,String> globalsWrites,
            List<Map<String,Object>> headers, String body, boolean bodyUpdated, String url, boolean urlUpdated,
            int consoleCount, Set<String> secretOutputs, Map<String,String> localWrites) {
        public Result(Map<String,String>environmentWrites,Map<String,String>globalsWrites,List<Map<String,Object>>headers,String body,boolean bodyUpdated,String url,boolean urlUpdated,int consoleCount,Set<String>secretOutputs) {
            this(environmentWrites,globalsWrites,headers,body,bodyUpdated,url,urlUpdated,consoleCount,secretOutputs,Map.of());
        }
        public Result {
            environmentWrites = Collections.unmodifiableMap(new LinkedHashMap<>(environmentWrites));
            globalsWrites = Collections.unmodifiableMap(new LinkedHashMap<>(globalsWrites));
            headers = headers.stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
            secretOutputs = Collections.unmodifiableSet(new LinkedHashSet<>(secretOutputs));
            localWrites = Collections.unmodifiableMap(new LinkedHashMap<>(localWrites));
        }
    }
    public static final class ScriptException extends IllegalArgumentException {
        private final String type;
        private final int line;
        public ScriptException(String type, int line) {
            super("签名脚本执行失败（" + safeType(type) + (line > 0 ? "，行" + line : "") + "）");
            this.type = safeType(type); this.line = Math.max(0, Math.min(line, 65536));
        }
        public String type() { return type; }
        public int line() { return line; }
    }

    public static Result execute(String code, Map<String,Object> input, long budgetMillis) {
        if (code == null || code.length() > MAX_SCRIPT_BYTES || code.getBytes(StandardCharsets.UTF_8).length > MAX_SCRIPT_BYTES || input == null || budgetMillis < 1 || budgetMillis > 5000)
            throw new ScriptException("INPUT_LIMIT", 0);
        final byte[] payload;
        try {
            var inputBytes = new BoundedBytes(MAX_INPUT_CHARS * 3);
            JSON.writeValue(inputBytes,input);
            String raw = inputBytes.toString(StandardCharsets.UTF_8);
            if (raw.length() > MAX_INPUT_CHARS) throw new ScriptException("INPUT_LIMIT", 0);
            var envelope = new BoundedBytes(MAX_PIPE_INPUT);
            try(var generator=JSON.getFactory().createGenerator(envelope)) {
                generator.writeStartObject();generator.writeStringField("code",code);generator.writeFieldName("input");generator.writeRawValue(raw);
                generator.writeNumberField("budgetMillis",budgetMillis);generator.writeEndObject();
            }
            payload=envelope.toByteArray();
        } catch (ScriptException e) { throw e; }
        catch (Exception e) { throw new ScriptException("INPUT_ERROR", 0); }
        if (!System.getProperty("os.name", "").startsWith("Mac") || !Files.isExecutable(SANDBOX))
            throw new ScriptException("ISOLATION_UNAVAILABLE", 0);
        Process process = null;
        ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<String> command = workerCommand(ScriptWorker.class.getName());
            var builder = new ProcessBuilder(command).directory(Path.of("/").toFile());
            builder.environment().clear();
            // Fixed CF encoding avoids reading the user's preference file during macOS JVM startup.
            builder.environment().put("__CF_USER_TEXT_ENCODING", "0:0:0");
            process = builder.start(); final Process child = process;
            Future<byte[]> stdout = drains.submit(() -> drain(child.getInputStream(), MAX_OUTPUT_BYTES, child));
            Future<byte[]> stderr = drains.submit(() -> drain(child.getErrorStream(), 16384, child));
            Future<?> writer = drains.submit(() -> { try (var out = child.getOutputStream()) { out.write(payload); } return null; });
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis + 2500);
            if (!process.waitFor(remaining(end), TimeUnit.MILLISECONDS)) throw new ScriptException("TIME_LIMIT", 0);
            writer.get(remaining(end), TimeUnit.MILLISECONDS);
            byte[] output = stdout.get(remaining(end), TimeUnit.MILLISECONDS);
            stderr.get(remaining(end), TimeUnit.MILLISECONDS); // drain, never log or return text
            if (process.exitValue() != 0) throw new ScriptException("WORKER_FAILED", 0);
            Map<String,Object> envelope = JSON.readValue(output, new TypeReference<>() {});
            if (!Boolean.TRUE.equals(envelope.get("ok"))) {
                int line = envelope.get("line") instanceof Number n ? n.intValue() : 0;
                throw new ScriptException(String.valueOf(envelope.get("errorType")), line);
            }
            Result result = JSON.convertValue(envelope.get("result"), Result.class);
            validateResult(result);
            return result;
        } catch (ScriptException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ScriptException("INTERRUPTED", 0); }
        catch (TimeoutException e) { throw new ScriptException("TIME_LIMIT", 0); }
        catch (Exception e) { throw new ScriptException("WORKER_FAILED", 0); }
        finally {
            if (process != null) {
                process.destroyForcibly();
                try { process.waitFor(300, TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                close(process.getOutputStream()); close(process.getInputStream()); close(process.getErrorStream());
            }
            drains.shutdownNow();
        }
    }

    static List<String> workerCommand(String mainClass) throws IOException {
        Path home,javaExe;
        try{home=Path.of(System.getProperty("java.home")).toRealPath();if(!Files.isExecutable(home.resolve("bin/java")))throw new IOException();javaExe=home.resolve("bin/java").toRealPath();}
        catch(IOException|RuntimeException unavailable){throw new ScriptException("ISOLATION_UNAVAILABLE",0);}
        var paths = new LinkedHashSet<Path>();
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (entry.isBlank() || entry.contains("*")) throw new IOException();
            paths.add(Path.of(entry).toRealPath());
        }
        if (paths.isEmpty()) throw new IOException();
        Path boot = null;
        for (Path path : paths) if (Files.isRegularFile(path) && path.toString().endsWith(".jar")) {
            try (var zip = new ZipFile(path.toFile())) {
                if (zip.getEntry("BOOT-INF/classes/com/sudpanel/api/script/ScriptWorker.class") != null && zip.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class") != null) { boot = path; break; }
            }
        }
        var command = new ArrayList<String>();
        Set<Path> runtimePaths = boot == null ? paths : Set.of(boot);
        command.add(SANDBOX.toString()); command.add("-p"); command.add(profile(home, javaExe, runtimePaths)); command.add(javaExe.toString());
        command.addAll(List.of("-Xmx96m", "-Xss512k", "-XX:MaxMetaspaceSize=64m", "-XX:ReservedCodeCacheSize=32m", "-XX:-UsePerfData", "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=/dev/null", "-Djava.awt.headless=true", "-Duser.timezone=UTC", "-Duser.language=en", "-Duser.country=US", "-Duser.home=/nonexistent", "-Djava.io.tmpdir=/nonexistent"));
        if (boot != null) {
            command.add("-Dloader.main=" + mainClass); command.add("-cp"); command.add(boot.toString());
            command.add("org.springframework.boot.loader.launch.PropertiesLauncher");
        } else {
            command.add("-cp"); command.add(String.join(java.io.File.pathSeparator, paths.stream().map(Path::toString).toList())); command.add(mainClass);
        }
        return List.copyOf(command);
    }

    static String profile(Path home, Path javaExe, Set<Path> classpath) {
        var data = new LinkedHashSet<String>();
        data.add("(subpath " + quote(home.toString()) + ")");
        data.add("(subpath \"/System/Library\")"); data.add("(subpath \"/usr/lib\")");
        for (String path : List.of("/", "/dev/null", "/dev/urandom", "/dev/random")) data.add("(literal " + quote(path) + ")");
        for (Path path : classpath) data.add("(" + (Files.isDirectory(path) ? "subpath" : "literal") + " " + quote(path.toString()) + ")");
        var metadata = new LinkedHashSet<Path>(); metadata.add(home); metadata.addAll(classpath);
        var parents = new LinkedHashSet<String>();
        for (Path path : metadata) for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) parents.add("(literal " + quote(parent.toString()) + ")");
        parents.add("(literal \"/etc\")"); parents.add("(literal \"/var\")");
        return "(version 1)(deny default)(allow process-exec (literal " + quote(javaExe.toString()) + "))"
                + "(allow sysctl-read)(allow mach-lookup (global-name \"com.apple.system.notification_center\") (global-name \"com.apple.system.opendirectoryd.libinfo\"))"
                + "(allow file-read* file-map-executable " + String.join(" ", data) + ")(allow file-read-metadata " + String.join(" ", parents) + ")";
    }
    private static String quote(String path) { return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private static long remaining(long end) { return Math.max(1, TimeUnit.NANOSECONDS.toMillis(end - System.nanoTime())); }
    private static byte[] drain(InputStream in, int max, Process process) throws IOException {
        var out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
        for (int count; (count = in.read(buffer)) != -1;) {
            if (count > max - out.size()) { process.destroyForcibly(); throw new IOException(); }
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
    private static final class BoundedBytes extends ByteArrayOutputStream {
        final int limit;
        BoundedBytes(int limit){this.limit=limit;}
        @Override public synchronized void write(int value){if(count>=limit)throw new ScriptException("INPUT_LIMIT",0);super.write(value);}
        @Override public synchronized void write(byte[]bytes,int offset,int length){if(length>limit-count)throw new ScriptException("INPUT_LIMIT",0);super.write(bytes,offset,length);}
    }
    private static void close(Closeable stream) { try { stream.close(); } catch (IOException ignored) {} }
    static String safeType(String type) {
        return Set.of("INPUT_LIMIT", "INPUT_ERROR", "OUTPUT_LIMIT", "UNSUPPORTED_API", "UNRESOLVED_SECRET", "TIME_LIMIT", "STACK_LIMIT", "MEMORY_LIMIT", "SCRIPT_ERROR", "INTERRUPTED", "WORKER_FAILED", "ISOLATION_UNAVAILABLE").contains(type) ? type : "SCRIPT_ERROR";
    }
    private static void validateResult(Result result) throws IOException {
        if (result == null || result.environmentWrites().size() + result.globalsWrites().size() + result.localWrites().size() > 1000 || result.headers().size() > 200 || result.consoleCount() < 0 || result.secretOutputs().size() > 1000) throw new IOException();
        if (result.body() != null && result.body().length() > MAX_INPUT_CHARS || result.url() != null && result.url().length() > 65536) throw new IOException();
        for (var row : result.headers()) if (!(row.get("name") instanceof String) || !(row.get("value") instanceof String) || !(row.get("enabled") instanceof Boolean)) throw new IOException();
    }
}
