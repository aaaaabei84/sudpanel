package com.sudpanel.api.script;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.mozilla.javascript.*;

/** Internal child entry point. Never start this class in the application JVM. */
public final class ScriptWorker {
    private ScriptWorker() {}
    static final class Denied extends RuntimeException {
        final String type;
        Denied(String type) { this.type = ApiScriptSandbox.safeType(type); }
    }
    static final class Deadline extends Error {}
    static final class RandomBytes {
        final byte[] key;long counter;
        RandomBytes(JsonNode input) {
            JsonNode configured=input.get("randomKey");
            if(configured==null||configured.isNull())key=null;
            else{if(!configured.isTextual()||!configured.asText().matches("[0-9a-fA-F]{64}"))throw new Denied("INPUT_ERROR");key=HexFormat.of().parseHex(configured.asText());}
        }
        byte[] next(int length)throws Exception {
            if(key==null)throw new Denied("INPUT_ERROR");
            byte[] result=new byte[length];var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            for(int offset=0;offset<length;){
                mac.update("sudpanel-script-random-v1".getBytes(StandardCharsets.US_ASCII));
                byte[] block=mac.doFinal(ByteBuffer.allocate(8).putLong(++counter).array());int size=Math.min(block.length,length-offset);System.arraycopy(block,0,result,offset,size);offset+=size;
            }
            return result;
        }
    }
    static final class Factory extends ContextFactory {
        final long deadline;
        final String[] firstDenied;
        Factory(long millis,String[] firstDenied) { deadline = System.nanoTime() + millis * 1_000_000L; this.firstDenied=firstDenied; }
        @Override protected Context makeContext() {
            Context cx = super.makeContext(); cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setInterpretedMode(true); cx.setMaximumInterpreterStackDepth(128);
            cx.setInstructionObserverThreshold(1000); cx.setClassShutter(name -> {firstDenied[0]="UNSUPPORTED_API";return false;});
            cx.setWrapFactory(new WrapFactory() {
                @Override public Object wrap(Context c, Scriptable s, Object object, Class<?> type) {
                    if (object == null || object instanceof String || object instanceof Number || object instanceof Boolean) return object;
                    throw new Denied("UNSUPPORTED_API");
                }
                @Override public Scriptable wrapAsJavaObject(Context c, Scriptable s, Object object, Class<?> type) { throw new Denied("UNSUPPORTED_API"); }
                @Override public Scriptable wrapNewObject(Context c, Scriptable s, Object object) { throw new Denied("UNSUPPORTED_API"); }
                @Override public Scriptable wrapJavaClass(Context c, Scriptable s, Class<?> type) { throw new Denied("UNSUPPORTED_API"); }
            });
            return cx;
        }
        @Override protected void observeInstructionCount(Context cx, int count) {
            if (System.nanoTime() >= deadline || Thread.currentThread().isInterrupted()) throw new Deadline();
        }
    }
    @FunctionalInterface interface Call { Object call(Object[] args); }
    static BaseFunction fn(Scriptable scope,String[] firstDenied, Call call) {
        var result = new BaseFunction() {
            @Override public Object call(Context cx, Scriptable s, Scriptable self, Object[] args) {
                try{return call.call(args);}catch(Denied denied){if(firstDenied[0]==null)firstDenied[0]=denied.type;throw denied;}
            }
        };
        result.setParentScope(scope); result.setPrototype(ScriptableObject.getFunctionPrototype(scope)); return result;
    }
    static void put(ScriptableObject scope, String key, Object value) { scope.defineProperty(key, value, ScriptableObject.READONLY | ScriptableObject.PERMANENT); }
    public static void main(String[] args) {
        Map<String,Object> output;
        try {
            byte[] input = System.in.readNBytes(ApiScriptSandbox.MAX_PIPE_INPUT + 1);
            if (input.length > ApiScriptSandbox.MAX_PIPE_INPUT) throw new Denied("INPUT_LIMIT");
            JsonNode envelope = ApiScriptSandbox.JSON.readTree(input);
            String code = envelope.path("code").asText(); JsonNode values = envelope.path("input");
            long millis = envelope.path("budgetMillis").asLong();
            if (code.getBytes(StandardCharsets.UTF_8).length > ApiScriptSandbox.MAX_SCRIPT_BYTES || !values.isObject() || values.toString().length() > ApiScriptSandbox.MAX_INPUT_CHARS || millis < 1 || millis > 5000) throw new Denied("INPUT_LIMIT");
            long seed = values.path("randomSeed").asLong(0), time = values.path("timeMillis").asLong(0);
            var random = new Random(seed);var randomBytes=new RandomBytes(values); var secrets = new LinkedHashSet<String>(); int[] consoleCount = {0};String[] firstDenied={null};
            Object result = new Factory(millis,firstDenied).call(cx -> {
                cx.setLocale(Locale.ROOT); cx.setTimeZone(TimeZone.getTimeZone("UTC"));
                ScriptableObject scope = cx.initSafeStandardObjects();
                for (String name : List.of("Packages", "java", "javax", "org", "com", "edu", "net", "getClass", "JavaAdapter", "JavaImporter", "Promise")) ScriptableObject.deleteProperty(scope, name);
                for(String name:List.of("Java","Packages","java","javax","org","com","edu","net","process","fs")) {
                    var denied=new ScriptableObject(){
                        @Override public String getClassName(){return "Unavailable";}
                        @Override public Object get(String key,Scriptable start){firstDenied[0]="UNSUPPORTED_API";throw new Denied("UNSUPPORTED_API");}
                        @Override public Object get(int key,Scriptable start){firstDenied[0]="UNSUPPORTED_API";throw new Denied("UNSUPPORTED_API");}
                    };denied.setParentScope(scope);denied.sealObject();put(scope,name,denied);
                }
                for (String name : List.of("setTimeout", "setInterval", "queueMicrotask", "fetch", "XMLHttpRequest", "WebSocket", "process", "fs"))
                    if(!List.of("process","fs").contains(name))put(scope, name, fn(scope,firstDenied, a -> { throw new Denied("UNSUPPORTED_API"); }));
                var host = (ScriptableObject)cx.newObject(scope);
                put(host, "fail", fn(scope,firstDenied, a -> { throw new Denied(a.length == 0 ? "SCRIPT_ERROR" : Context.toString(a[0])); }));
                put(host, "random", fn(scope,firstDenied, a -> random.nextDouble()));
                put(host, "console", fn(scope,firstDenied, a -> { if (consoleCount[0] >= 10000) throw new Denied("OUTPUT_LIMIT"); consoleCount[0]++; return Undefined.instance; }));
                put(host, "secret", fn(scope,firstDenied, a -> { if (a.length > 0) secret(secrets, Context.toString(a[0])); return Undefined.instance; }));
                put(host, "crypto", fn(scope,firstDenied, a -> crypto(a, randomBytes, secrets)));
                Function parse = (Function)cx.evaluateString(scope, "JSON.parse", "input", 1, null);
                var jsInput = (ScriptableObject)parse.call(cx, scope, scope, new Object[]{values.toString()});
                jsInput.put("timeMillis", jsInput, (double)time);
                String bootstrap;
                try (var in = ScriptWorker.class.getResourceAsStream("bootstrap.js")) { if (in == null) throw new Denied("WORKER_FAILED"); bootstrap = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
                catch (IOException e) { throw new Denied("WORKER_FAILED"); }
                Function init = (Function)cx.evaluateString(scope, bootstrap, "bootstrap", 1, null);
                Scriptable harness = (Scriptable)init.call(cx, scope, scope, new Object[]{jsInput, host});
                Function finish = (Function)ScriptableObject.getProperty(harness, "finish");
                for (String name : List.of("pm", "require", "console")) put(scope, name, ScriptableObject.getProperty(harness, name));
                put(scope,"CryptoJS",ScriptableObject.getProperty(harness,"CryptoJS"));
                ScriptableObject.deleteProperty(scope, "Date"); put(scope, "Date", ScriptableObject.getProperty(harness, "Date"));
                Scriptable math = (Scriptable)ScriptableObject.getProperty(scope, "Math");
                ScriptableObject.putProperty(math, "random", ScriptableObject.getProperty(harness, "random"));
                // Per-execution lexical scope permits the common `const CryptoJS = require(...)` declaration.
                Function user=cx.compileFunction(scope,"function(){\n"+code+"\n}","signature.js",0,null);
                user.call(cx,scope,scope,Context.emptyArgs);
                if(firstDenied[0]!=null)throw new Denied(firstDenied[0]);
                String encoded = Context.toString(finish.call(cx, scope, harness, Context.emptyArgs));
                if (encoded.getBytes(StandardCharsets.UTF_8).length > ApiScriptSandbox.MAX_OUTPUT_BYTES) throw new Denied("OUTPUT_LIMIT");
                try { return ApiScriptSandbox.JSON.readValue(encoded, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {}); }
                catch (IOException e) { throw new Denied("OUTPUT_LIMIT"); }
            });
            @SuppressWarnings("unchecked") var fields = new LinkedHashMap<>((Map<String,Object>)result);
            fields.put("consoleCount", consoleCount[0]); fields.put("secretOutputs", secrets);
            output = Map.of("ok", true, "result", fields);
        } catch (Throwable error) {
            String type = "SCRIPT_ERROR"; int line = 0;
            for (Throwable t = error; t != null; t = t.getCause()) {
                if (t instanceof Denied denied) { type = denied.type; break; }
                if (t instanceof Deadline) { type = "TIME_LIMIT"; break; }
                if (t instanceof OutOfMemoryError) { type = "MEMORY_LIMIT"; break; }
                if (t instanceof StackOverflowError) { type = "STACK_LIMIT"; break; }
            }
            if (error instanceof RhinoException rhino) for (var entry : rhino.getScriptStack()) if ("signature.js".equals(entry.fileName)) { line = entry.lineNumber; break; }
            output = Map.of("ok", false, "errorType", type, "line", Math.max(0, Math.min(line, 65536)));
        }
        try {
            byte[] encoded = ApiScriptSandbox.JSON.writeValueAsBytes(output);
            if (encoded.length > ApiScriptSandbox.MAX_OUTPUT_BYTES) encoded = "{\"ok\":false,\"errorType\":\"OUTPUT_LIMIT\",\"line\":0}".getBytes(StandardCharsets.UTF_8);
            System.out.write(encoded); System.out.flush();
        } catch (Throwable ignored) { Runtime.getRuntime().halt(2); }
    }
    static String crypto(Object[] args, RandomBytes random, Set<String> secrets) {
        try {
            String op = arg(args, 0), algorithm = arg(args, 1), value = arg(args, 2), format = arg(args, 3);
            byte[] bytes;
            if (op.equals("RANDOM")) {
                int length = Integer.parseInt(value); if (length < 0 || length > 65536) throw new Denied("OUTPUT_LIMIT");
                bytes = random.next(length);
            } else if (op.equals("ENCODE")) {
                bytes = switch (algorithm) { case "UTF8" -> value.getBytes(StandardCharsets.UTF_8); case "BASE64" -> Base64.getDecoder().decode(value.replaceAll("\\s", "")); default -> throw new Denied("UNSUPPORTED_API"); };
                return HexFormat.of().formatHex(bytes);
            } else if (op.equals("DECODE")) {
                bytes = HexFormat.of().parseHex(value);
                return switch (algorithm) { case "BASE64" -> Base64.getEncoder().encodeToString(bytes); case "UTF8" -> StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); default -> throw new Denied("UNSUPPORTED_API"); };
            } else if (op.equals("HASH")) {
                if (!Set.of("MD5", "SHA-1", "SHA-256", "SHA-512").contains(algorithm)) throw new Denied("UNSUPPORTED_API");
                bytes = MessageDigest.getInstance(algorithm).digest(material(value, format));
            } else if (op.equals("HMAC")) {
                if (!Set.of("HmacSHA1", "HmacSHA256", "HmacSHA512").contains(algorithm)) throw new Denied("UNSUPPORTED_API");
                var mac = Mac.getInstance(algorithm); byte[] key = material(arg(args, 4), arg(args, 5));
                // An empty HMAC key is valid; all-zero block is equivalent after HMAC padding.
                mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, algorithm)); bytes = mac.doFinal(material(value, format));
            } else throw new Denied("UNSUPPORTED_API");
            String hex = HexFormat.of().formatHex(bytes); secret(secrets, hex); secret(secrets, Base64.getEncoder().encodeToString(bytes)); return hex;
        } catch (Denied e) { throw e; }
        catch (Exception e) { throw new Denied("SCRIPT_ERROR"); }
    }
    static byte[] material(String value, String format) { return switch (format) { case "UTF8" -> value.getBytes(StandardCharsets.UTF_8); case "HEX" -> HexFormat.of().parseHex(value); default -> throw new Denied("UNSUPPORTED_API"); }; }
    static String arg(Object[] args, int n) { if (args.length <= n) return ""; String value = Context.toString(args[n]); if (value.length() > 12 * 1024 * 1024) throw new Denied("INPUT_LIMIT"); return value; }
    static void secret(Set<String> secrets, String value) { if (value.isEmpty()) return; if (value.length() > 262144 || !secrets.contains(value) && secrets.size() >= 1000) throw new Denied("OUTPUT_LIMIT"); secrets.add(value); }
}
