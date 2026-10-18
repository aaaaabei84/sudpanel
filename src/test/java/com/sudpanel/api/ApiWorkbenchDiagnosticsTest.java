package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApiWorkbenchDiagnosticsTest {
    @TempDir Path directory;ApiRepository repo;ApiRunner runner;HttpServer server;String base;String wireBody,wireAuth;AtomicInteger requests=new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        repo=new ApiRepository(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("fixture.db"))));runner=new ApiRunner(repo,new CodeDictionary(repo),"");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",x->{requests.incrementAndGet();wireBody=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);wireAuth=x.getRequestHeaders().getFirst("Authorization");byte[]bytes="{}".getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();});server.start();base="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void close(){server.stop(0);}
    ApiSpec spec(String headers,String body,ApiRequestOptions options){return new ApiSpec(null,"fixture","POST",base+"/echo",headers,body,null,"200",null,2000,false,true,null,null,options);}
    ApiRequestOptions options(String type,Boolean strict){return new ApiRequestOptions(type,null,null,null,null,null,strict);}
    @Test void environmentStrictPersistsAndRequestOverrideRetainsOldCompatibility() {
        repo.setEnvStrict(1,true);var s=spec("{}","{{missing}}",options("RAW",null));
        assertThat(runner.prepare(s,null).valid()).isFalse();assertThat(runner.send(s,null,null).verdict()).isEqualTo("ERROR");assertThat(requests).hasValue(0);
        var legacy=spec("{}","{{missing}}",options("RAW",false));assertThat(runner.send(legacy,null,null).verdict()).isEqualTo("PASS");assertThat(wireBody).isEqualTo("{{missing}}");
        assertThat(repo.duplicateEnv(1).strictVariables()).isTrue();
    }
    @Test void previewDiagnosticsLocateMissingNamesAndScopedShadowingWithoutValues() throws Exception {
        repo.saveVars(1,List.of(new ApiVariables.Var("host",base)),List.of(new ApiVariables.Var("host","fictional-secret")),List.of(new ApiVariables.Var("token","fictional-private"),new ApiVariables.Var("$uuid","shadow-value")));
        var s=new ApiSpec(null,"names","POST","{{host}}/echo","{}","{\n\"token\":\"{{toke}}\",\"id\":\"{{$uuid}}\"}",null,"200",null,2000,false,true,null,null,options("JSON",true));
        var preview=runner.prepare(s,null);assertThat(preview.valid()).isFalse();
        var missing=preview.diagnostics().stream().filter(i->i.name().equals("toke")).findFirst().orElseThrow();assertThat(missing.location()).isEqualTo("body");assertThat(missing.line()).isEqualTo(2);assertThat(missing.candidates()).contains("token");
        var host=preview.diagnostics().stream().filter(i->i.name().equals("host")).findFirst().orElseThrow();assertThat(host.source()).isEqualTo("BASE");assertThat(host.shadowedSources()).contains("HEADER");
        assertThat(new ObjectMapper().writeValueAsString(preview.diagnostics())).doesNotContain("fictional-secret","fictional-private","shadow-value");
    }
    @Test void literalPlaceholdersAreEscapedBeforeLegacySigningAndTypedJsonValidation() throws Exception {
        repo.setEnvStrict(1,true);repo.saveSignConfig(1,true,"app","test-key","nonce","game");
        var s=spec("{\"X-Literal\":\"{{!$uuid}}\"}","{\"literal\":\"{{!times}} {{!run.missing}} {{!$uuid}}\"}",options("JSON",null));
        var result=runner.send(s,null,null);assertThat(result.verdict()).isEqualTo("PASS");assertThat(wireBody).contains("{{times}} {{run.missing}} {{$uuid}}");
        var matcher=java.util.regex.Pattern.compile("timestamp=\\\"([0-9]+)\\\"").matcher(wireAuth);assertThat(matcher.find()).isTrue();
        String signature=ApiSigner.sign("test-key","app",Long.parseLong(matcher.group(1)),"nonce",wireBody);assertThat(wireAuth).contains(signature);
        assertThat(runner.prepare(s,null).diagnostics()).filteredOn(i->i.status().equals("LITERAL")).hasSize(4);
    }
    @Test void urlencodedContentTypeConflictWarnsWithoutSilentlyReplacingManualHeader() {
        var o=new ApiRequestOptions("URLENCODED",null,null,null,List.of(new ApiRequestOptions.Param("名字","空 格+&",true)),null,true);
        var prepared=runner.prepare(spec("{\"Content-Type\":\"application/json\"}","{{staleUnusedBody}}",o),null);
        assertThat(prepared.valid()).isTrue();assertThat(prepared.warnings()).anyMatch(w->w.contains("Content-Type")&&w.contains("冲突"));assertThat(prepared.headers().get("Content-Type")).isEqualTo("application/json");
        assertThat(prepared.body()).isEqualTo("%E5%90%8D%E5%AD%97=%E7%A9%BA+%E6%A0%BC%2B%26");
    }
    @Test void inspectionDistinguishesExactNodeLimitDepthAndLargeValueAndReadsDeepPath() {
        var exact=ApiResponseTools.inspect("["+String.join(",",java.util.Collections.nCopies(499,"0"))+"]");assertThat(exact.fields()).hasSize(500);assertThat(exact.truncationReasons()).isEmpty();
        var over=ApiResponseTools.inspect("["+String.join(",",java.util.Collections.nCopies(500,"0"))+"]");assertThat(over.truncationReasons()).containsExactly("FIELD_LIMIT");
        String body="{\"deep\":"+"[".repeat(34)+"12345678901234567890123456789012345678"+"]".repeat(34)+",\"large\":\""+"x".repeat(5000)+"\"}";
        var inspection=ApiResponseTools.inspect(body);assertThat(inspection.truncationReasons()).contains("DEPTH_LIMIT","LARGE_VALUE");assertThat(inspection.fields().getFirst().typeSelectable()).isTrue();assertThat(inspection.fields().getFirst().valueJson()).isNull();
        var deep=ApiResponseTools.readPath(body,null,"deep"+"[0]".repeat(34));assertThat(deep.found()).isTrue();assertThat(deep.fields().getFirst().valueJson()).isEqualTo("12345678901234567890123456789012345678");
        var literal=ApiResponseTools.readPath("{\"a.b\":null}",null,"/a.b");assertThat(literal.fields().getFirst().valueJson()).isEqualTo("null");assertThat(ApiResponseTools.readPath("{}",null,"missing").found()).isFalse();
    }
    @Test void actualCurlContainsNewlyAssembledSignedBytesAndIsPureStringConversion() throws Exception {
        repo.saveSignConfig(1,true,"app","test-key","nonce","game");var s=spec("{}","{\"time\":{{$timestamp}},\"id\":12345678901234567890123456789012345678}",options("JSON",null));
        var exported=runner.actualCurl(s,null,true);assertThat(requests).hasValue(0);assertThat(exported.source()).isEqualTo("PREPARED");assertThat(exported.complete()).isTrue();
        var imported=ApiInterchange.preview(new ApiInterchange.Input("CURL",exported.curl(),null));assertThat(imported.errors()).isEmpty();var importedSpec=imported.requests().getFirst().spec();
        assertThat(importedSpec.body()).contains("12345678901234567890123456789012345678").doesNotContain("{{$timestamp}}");
        assertThat(importedSpec.headers()).contains("Sud-Auth");
        var replayed=runner.send(importedSpec,null,null);assertThat(replayed.verdict()).isEqualTo("PASS");assertThat(wireBody).isEqualTo(importedSpec.body());assertThat(wireAuth).isEqualTo(new ObjectMapper().readTree(importedSpec.headers()).path("Authorization").asText());
    }
    @Test void actualCurlDefaultMasksCustomHeadersEveryQueryValueAndRawBody() {
        var s=new ApiSpec(null,"private","POST",base+"/echo?custom=fake-key&ret%5Fcode=ret-value","{\"X-Custom\":\"private-header\"}","private-body",null,"200",null,2000,false,true,null,null,options("RAW",true));
        var exported=runner.actualCurl(s,null,false);assertThat(exported.curl()).doesNotContain("fake-key","ret-value","private-header","private-body").contains("REDACTED");assertThat(exported.warnings()).anyMatch(w->w.contains("不能直接复放"));
    }
    @Test void curlQuotesMultilineRawTextPreservesAtAndEmptyBodyAndRejectsTruncatedHistory() {
        var s=spec("{}","@literal\nquote ' and \\\nmore",options("RAW",true));var exported=runner.actualCurl(s,null,true);
        var imported=ApiInterchange.preview(new ApiInterchange.Input("CURL",exported.curl(),null));assertThat(imported.errors()).isEmpty();assertThat(imported.requests().getFirst().spec().body()).isEqualTo(s.body());
        assertThat(runner.actualCurl(spec("{}","",options("RAW",true)),null,true).curl()).contains("--data-raw ''");
        var e=runner.send(s,null,null);assertThat(ApiActualCurl.history(e,true).complete()).isTrue();
        var truncated=new ApiExec(e.id(),e.batchId(),e.caseId(),e.caseName(),e.roundNo(),e.seq(),e.method(),e.url(),e.httpStatus(),e.respCode(),e.codeMeaning(),e.verdict(),e.reason(),e.elapsedMs(),e.respBody(),e.respHeaders(),e.reqHeaders(),e.reqBody(),e.error(),e.ts(),e.assertsResult(),"{\"requestBodyTruncated\":true}");
        assertThatThrownBy(()->ApiActualCurl.history(truncated,true)).hasMessageContaining("截断");
        assertThatThrownBy(()->runner.actualCurl(spec("{}","x\\0".replace("\\0","\0"),options("RAW",true)),null,true)).hasMessageContaining("NUL");
    }
}
