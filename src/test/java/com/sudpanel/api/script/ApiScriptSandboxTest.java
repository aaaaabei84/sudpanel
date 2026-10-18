package com.sudpanel.api.script;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ApiScriptSandboxTest {
    Map<String,Object> input() {
        var result = new LinkedHashMap<String,Object>();
        result.put("environment", Map.of("key", "FICTIONAL_SECRET", "empty", "")); result.put("globals", Map.of("g", "global"));
        result.put("variables", Map.of("run.id", "12345678901234567890123456789012345678"));
        result.put("body", "{ \"id\":12345678901234567890123456789012345678 }");
        result.put("headers", List.of(Map.of("name", "X-Test", "value", "first", "enabled", true), Map.of("name", "X-Off", "value", "hidden", "enabled", false)));
        // Test-only deterministic random input; randomKey is a repeated fixture seed, not a credential.
        result.put("method", "POST"); result.put("url", "https://example.invalid/path"); result.put("timeMillis", 1792111111000L); result.put("randomSeed", 12345L);result.put("randomKey","0123456789abcdef".repeat(4));
        return result;
    }
    @Test void realSandboxHmacAndHashMatchIndependentJceWithoutExposingConsole() throws Exception {
        String script = "const C=require('crypto-js');const raw=pm.request.body.raw; const h=C.HmacSHA256(raw,pm.environment.get('key'));"
                + "pm.environment.set('signature',h.toString(C.enc.Base64)); pm.globals.set('hex',h.toString());"
                + "pm.request.headers.upsert({key:'Authorization',value:h.toString(C.enc.Base64)}); console.log(pm.environment.get('key'));";
        var result = ApiScriptSandbox.execute(script, input(), 1000);
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("FICTIONAL_SECRET".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(input().get("body").toString().getBytes(StandardCharsets.UTF_8));
        String hex = HexFormat.of().formatHex(digest), base64 = Base64.getEncoder().encodeToString(digest);
        assertThat(result.environmentWrites()).containsEntry("signature", base64); assertThat(result.globalsWrites()).containsEntry("hex", hex);
        assertThat(result.secretOutputs()).contains(hex, base64); assertThat(result.consoleCount()).isEqualTo(1);
        assertThat(result.headers()).anyMatch(h -> h.get("name").equals("Authorization") && h.get("value").equals(base64));
        assertThat(result.body()).isEqualTo(input().get("body")); assertThat(result.bodyUpdated()).isFalse();
    }
    @Test void deterministicClockRandomAndPerCallWritesNeverPersist() {
        String script = "pm.environment.set('time',String(Date.now())+':'+new Date().getTime()+':'+new (new Date().constructor)().getTime());"
                + "pm.environment.set('random',String(Math.random()));pm.environment.set('nonce',CryptoJS.lib.WordArray.random(8).toString());"
                + "pm.variables.set('local','L');pm.globals.unset('g');pm.environment.unset('key');";
        var original = input(); var first = ApiScriptSandbox.execute(script, original, 1000); var second = ApiScriptSandbox.execute(script, original, 1000);
        assertThat(first).isEqualTo(second); assertThat(first.environmentWrites()).containsEntry("time", "1792111111000:1792111111000:1792111111000").containsEntry("key", null);
        assertThat(first.globalsWrites()).containsEntry("g", null); assertThat(((Map<?,?>)original.get("environment")).get("key")).isEqualTo("FICTIONAL_SECRET");
        assertThat(ApiScriptSandbox.execute("if(pm.variables.has('local'))throw 'leak';", original, 1000).environmentWrites()).isEmpty();
        assertThatThrownBy(() -> first.environmentWrites().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rawBodyUrlHeadersAndReplaceInRemainExplicitAndOrdered() {
        String script = "pm.request.body.update({mode:'raw',raw:pm.request.body.raw+'\\n'});pm.request.url=pm.request.url.toString()+'?n='+pm.variables.get('run.id');"
                + "pm.request.headers.add({key:'X-Test',value:'duplicate'});pm.request.headers.remove('X-Off');"
                + "pm.environment.set('replaced',pm.variables.replaceIn('{{g}}/{{missing}}/{{run.id}}'));"
                + "if(pm.environment.get('missing')!==undefined||pm.request.headers.get('X-Test')!=='first')throw 'bad';";
        var result = ApiScriptSandbox.execute(script, input(), 1000);
        assertThat(result.bodyUpdated()).isTrue(); assertThat(result.body()).endsWith("\n"); assertThat(result.urlUpdated()).isTrue();
        assertThat(result.headers()).extracting(h -> h.get("value")).containsExactly("first", "duplicate");
        assertThat(result.environmentWrites().get("replaced")).isEqualTo("global/{{missing}}/12345678901234567890123456789012345678");
    }
    @Test void cryptoEncodersWordArraysAndAllAlgorithmsHaveRealKnownVectors() throws Exception {
        String script = "const C=CryptoJS;const bytes=C.enc.Utf8.parse('中文');if(C.enc.Utf8.stringify(C.enc.Base64.parse(bytes.toString(C.enc.Base64)))!=='中文')throw 'encoding';"
                + "pm.environment.set('words',C.lib.WordArray.create([0x61626300],3).toString(C.enc.Utf8));"
                + "['MD5','SHA1','SHA256','SHA512'].forEach(a=>pm.environment.set(a,C[a]('abc').toString()));"
                + "['HmacSHA1','HmacSHA256','HmacSHA512'].forEach(a=>pm.environment.set(a,C[a]('abc','key').toString(C.enc.Base64)));";
        var result = ApiScriptSandbox.execute(script, input(), 1000); assertThat(result.environmentWrites()).containsEntry("words", "abc");
        for (String algorithm : List.of("MD5", "SHA1", "SHA256", "SHA512")) {
            String jce = algorithm.replace("SHA", "SHA-"); String expected = HexFormat.of().formatHex(MessageDigest.getInstance(jce).digest("abc".getBytes(StandardCharsets.UTF_8)));
            assertThat(result.environmentWrites()).containsEntry(algorithm, expected); assertThat(result.secretOutputs()).contains(expected);
        }
        for (String algorithm : List.of("HmacSHA1", "HmacSHA256", "HmacSHA512")) {
            var mac = Mac.getInstance(algorithm); mac.init(new SecretKeySpec("key".getBytes(StandardCharsets.UTF_8), algorithm));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal("abc".getBytes(StandardCharsets.UTF_8)));
            assertThat(result.environmentWrites()).containsEntry(algorithm, expected); assertThat(result.secretOutputs()).contains(expected);
        }
    }
    @Test void deadlineRecursionAllocationAndFailureCannotDamageNextWorker() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> ApiScriptSandbox.execute("while(true){}", input(), 100)).isInstanceOf(ApiScriptSandbox.ScriptException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(4000);
        assertThatThrownBy(() -> ApiScriptSandbox.execute("function f(){f()}f();", input(), 500)).isInstanceOf(ApiScriptSandbox.ScriptException.class);
        assertThatThrownBy(() -> ApiScriptSandbox.execute("var a=[];while(true)a.push(new Array(1000000).fill('x'));", input(), 1000)).isInstanceOf(ApiScriptSandbox.ScriptException.class);
        assertThat(ApiScriptSandbox.execute("pm.environment.set('ok','after');", input(), 1000).environmentWrites()).containsEntry("ok", "after");
    }
    @Test void hostAccessUnsupportedAsyncAndReservedWritesFailWithoutSecretErrorText() {
        for (String code : List.of("java.lang.System.exit(0)", "Packages.java.nio.file.Files.readString('/etc/passwd')", "require('fs')", "pm.sendRequest('http://127.0.0.1')", "setTimeout(()=>{},1)", "pm.test('x',()=>{})", "CryptoJS.SHA256('x').toString({})", "pm.environment.set('run.id','x')", "pm.globals.set('$timestamp','x')", "pm.variables.unset('data.value')", "throw new Error('PRIVATE_THROW_TEXT')")) {
            assertThatThrownBy(() -> ApiScriptSandbox.execute(code, input(), 1000)).isInstanceOf(ApiScriptSandbox.ScriptException.class).hasMessageNotContaining("PRIVATE_THROW_TEXT").hasMessageNotContaining("FICTIONAL_SECRET").hasMessageNotContaining("/etc/passwd");
        }
        var values = input(); values.put("environment", Map.of("key", "secret://00000000-0000-0000-0000-000000000000"));
        assertThatThrownBy(() -> ApiScriptSandbox.execute("CryptoJS.SHA1(pm.environment.get('key'));", values, 1000)).isInstanceOf(ApiScriptSandbox.ScriptException.class).hasMessageContaining("UNRESOLVED_SECRET");
    }
    @Test void inputAndOutputLimitsAreEnforcedBeforeAResultCanEscape() {
        assertThatThrownBy(() -> ApiScriptSandbox.execute("x".repeat(65537), input(), 1000)).hasMessageContaining("INPUT_LIMIT");
        var oversized = input(); oversized.put("body", "x".repeat(2097153));
        assertThatThrownBy(() -> ApiScriptSandbox.execute("", oversized, 1000)).hasMessageContaining("INPUT_LIMIT");
        assertThatThrownBy(() -> ApiScriptSandbox.execute("for(var i=0;i<1001;i++)pm.environment.set('v'+i,'x')", input(), 1000)).hasMessageContaining("OUTPUT_LIMIT");
        assertThatThrownBy(() -> ApiScriptSandbox.execute("pm.environment.set('long','x'.repeat(65537))", input(), 1000)).hasMessageContaining("OUTPUT_LIMIT");
    }
    @Test void caughtPolicyViolationsStillRejectButOrdinaryCaughtScriptErrorsCanRecover() {
        for(String forbidden:List.of("require('fs')","pm.sendRequest('https://example.invalid')","Java.type('java.lang.Runtime')","Packages.java.lang.System","process.exit(0)")) {
            assertThatThrownBy(()->ApiScriptSandbox.execute("try{"+forbidden+"}catch(e){}pm.environment.set('signature','looks-valid');",input(),1000)).hasMessageContaining("UNSUPPORTED_API");
        }
        assertThat(ApiScriptSandbox.execute("try{throw new Error('ordinary')}catch(e){}pm.variables.set('key','local');",input(),1000).localWrites()).containsEntry("key","local");
        assertThatThrownBy(()->ApiScriptSandbox.execute("try{while(true){}}catch(e){}pm.environment.set('signature','late');",input(),100)).isInstanceOf(ApiScriptSandbox.ScriptException.class);
        assertThatThrownBy(()->ApiScriptSandbox.execute("new Uint8Array(200000000);",input(),1000)).satisfies(e->assertThat(((ApiScriptSandbox.ScriptException)e).type()).isIn("MEMORY_LIMIT","WORKER_FAILED"));
    }
    @Test void keyedCryptographicRandomResetsPerExecutionAndNeverUsesMathRandomSeedAsItsKey() {
        String code="pm.environment.set('random',CryptoJS.lib.WordArray.random(33).toString());";
        var first=ApiScriptSandbox.execute(code,input(),1000).environmentWrites().get("random");
        assertThat(first).hasSize(66).isEqualTo(ApiScriptSandbox.execute(code,input(),1000).environmentWrites().get("random"));
        var different=input();different.put("randomKey","abcdef0123456789".repeat(4));
        assertThat(ApiScriptSandbox.execute(code,different,1000).environmentWrites().get("random")).isNotEqualTo(first);
        var fresh=input();fresh.remove("randomKey");
        assertThatThrownBy(()->ApiScriptSandbox.execute(code,fresh,1000)).hasMessageContaining("INPUT_ERROR");
        assertThat(ApiScriptSandbox.execute("pm.environment.set('hash',CryptoJS.SHA1('abc').toString());pm.environment.set('math',String(Math.random()));",fresh,1000).environmentWrites()).containsKey("hash").containsKey("math");
        var bad=input();bad.put("randomKey","wrong");assertThatThrownBy(()->ApiScriptSandbox.execute(code,bad,1000)).hasMessageContaining("INPUT_ERROR");
    }
}
