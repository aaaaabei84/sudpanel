package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class ApiStructuredBodyBudgetTest {
    static final int LIMIT=2*1024*1024;
    static final String LONG_ID="12345678901234567890123456789012345678";
    static final String BUDGET="结构化解析";
    final ObjectMapper json=new ObjectMapper();
    @TempDir Path dir;
    ApiHttpFilesTest f;
    @AfterEach void cleanup(){if(f!=null)f.close();}
    static String payload(int size){String prefix="{\"ret_code\":0,\"id\":"+LONG_ID+",\"pad\":\"",suffix="\"}";return prefix+"x".repeat(size-prefix.length()-suffix.length())+suffix;}
    static ApiBodyAssert.Rule rule(String path,String op,String expected){return new ApiBodyAssert.Rule(path,op,expected);}
    @Test void overBudgetJsonCannotEnterAnyStructuredReaderOrPretendToBeNonJson(){
        String body=payload(LIMIT+1);assertThat(CodeDictionary.extract(body)).isNull();
        assertThatThrownBy(()->ApiBodyAssert.readValues(body,"ret_code")).hasMessageContaining(BUDGET);
        var inspected=ApiResponseTools.inspect(body);assertThat(inspected.validJson()).isFalse();assertThat(inspected.error()).contains(BUDGET);assertThat(inspected.truncationReasons()).contains("STRUCTURED_BODY_LIMIT");
        assertThat(ApiResponseTools.readPath(body,Map.of(),"ret_code").error()).contains(BUDGET);
        var rules=List.of(rule("","contains","ret_code"),rule("missing","notexists",""),rule("","schema","{\"type\":\"object\"}"));
        assertThat(ApiBodyAssert.judgeAll(body,rules)).allSatisfy(j->{assertThat(j.ok()).isFalse();assertThat(j.failure().message()).contains(BUDGET);assertThat(j.actual()).isNull();});
    }
    @Test void exactBoundaryKeepsCodePathPrecisionAndExistingComparisons(){
        String body=payload(LIMIT);assertThat(body.length()).isEqualTo(LIMIT);assertThat(CodeDictionary.extract(body).raw()).isEqualTo("0");
        assertThat(ApiBodyAssert.readValues(body,"id").getFirst().toString()).isEqualTo(LONG_ID);
        var rules=new ArrayList<ApiBodyAssert.Rule>();for(int i=0;i<50;i++)rules.add(rule("id","equals",LONG_ID));
        assertThat(ApiBodyAssert.judgeAll(body,rules)).hasSize(50).allSatisfy(j->{assertThat(j.ok()).isTrue();assertThat(j.actual()).isEqualTo(LONG_ID);});
        assertThat(ApiResponseTools.inspect(body).validJson()).isTrue();
    }
    @Test void oversizedBodyDoesNotDisableFileHeadersElapsedOrHeaderInspection(){
        String body=payload(LIMIT+1);var headers=Map.of("Content-Type",List.of("application/json"),"X-Token",List.of("fixture"));
        var rules=List.of(rule("$response.bytes","equals","3145728"),rule("$response.sha256","equals","fixture-sha"),rule("header:Content-Type","equals","application/json"),rule("$elapsedMs","lt","1000"),rule("ret_code","equals","0"));
        var results=ApiBodyAssert.judgeAll(body,rules,headers,20L,Map.of("bytes",3145728,"sha256","fixture-sha"),false);
        assertThat(results.subList(0,4)).allMatch(ApiBodyAssert.Judged::ok);assertThat(results.getLast().ok()).isFalse();
        var inspected=ApiResponseTools.inspect(body,headers);assertThat(inspected.fields()).anyMatch(x->x.path().equals("header:X-Token"));assertThat(inspected.error()).contains(BUDGET);
        assertThat(ApiResponseTools.readPath(body,headers,"header:X-Token").found()).isTrue();
    }
    @Test void ordinaryTextFallbackAndOptionalMissingExtractionStayCompatible(){
        assertThat(ApiBodyAssert.judgeAll("hello world",List.of(rule("","contains","world")))).allMatch(ApiBodyAssert.Judged::ok);
        var variables=new HashMap<String,String>();variables.put("missing","old");
        var result=ApiResponseTools.extract("{}",Map.of(),List.of(new ApiExtractor("missing","JSON","none",false)),variables);
        assertThat(result.getFirst().ok()).isFalse();assertThat(result.getFirst().required()).isFalse();assertThat(variables).doesNotContainKey("missing");
    }
    @Test void oversizedJsonExtractionsAreExplicitFailuresAndHeaderTargetsStillPopulate(){
        var variables=new HashMap<String,String>();variables.put("id","old");variables.put("optional","old");
        var results=ApiResponseTools.extract(payload(LIMIT+1),Map.of("X-Token",List.of("new")),List.of(new ApiExtractor("id","JSON","id",true),new ApiExtractor("optional","JSON","missing",false),new ApiExtractor("token","HEADER","X-Token",true)),variables);
        assertThat(results.subList(0,2)).allSatisfy(e->{assertThat(e.ok()).isFalse();assertThat(e.error()).contains(BUDGET);});assertThat(results.getLast().ok()).isTrue();assertThat(variables).containsOnlyKeys("token");
    }
    private ApiExec call(String body,String code,String assertions,List<ApiExtractor> extractors)throws Exception{return call(body,code,assertions,extractors,false);}
    private ApiExec call(String body,String code,String assertions,List<ApiExtractor> extractors,boolean gzip)throws Exception{
        f=new ApiHttpFilesTest();f.temp=dir;f.setup();byte[] original=body.getBytes(StandardCharsets.UTF_8);byte[] encoded=original;
        if(gzip){var out=new java.io.ByteArrayOutputStream();try(var compressor=new java.util.zip.GZIPOutputStream(out)){compressor.write(original);}encoded=out.toByteArray();}final byte[] bytes=encoded;
        f.server.createContext("/budget",x->{try{x.getRequestBody().readAllBytes();x.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");x.getResponseHeaders().set("X-Budget-Fixture","header-ok");if(gzip)x.getResponseHeaders().set("Content-Encoding","gzip");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);}finally{x.close();}});
        var opts=new ApiRequestOptions("NONE",null,null,extractors,null,new ApiRequestOptions.Transport("NEVER","DIRECT",null,null,"OFF",64*1024*1024),true);
        var spec=new ApiSpec(null,"budget","GET",f.url+"/budget","{}",null,null,"200",code,5000,false,true,null,assertions,opts);return f.runner.send(spec,1L,null);
    }
    private void archived(ApiExec execution,String body)throws Exception{
        var meta=json.readTree(execution.metadata());assertThat(meta.path("responseBody").path("complete").asBoolean()).isTrue();
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);assertThat(meta.path("responseBody").path("bytes").asInt()).isEqualTo(bytes.length);
        assertThat(meta.path("responseBody").path("sha256").asText()).isEqualTo(ApiFileStore.sha(bytes));
        try(var lease=f.files.acquire(meta.path("responseBody").path("file").path("fileId").asText())){assertThat(lease.bytes()).isEqualTo(bytes);}
    }
    @Test void realHttpFileOnlyRulesPassAndCompleteOriginalBytesRemainArchived()throws Exception{
        String body=payload(LIMIT+1);String rules="[{\"path\":\"$response.bytes\",\"op\":\"equals\",\"expect\":\""+body.length()+"\"},{\"path\":\"header:X-Budget-Fixture\",\"op\":\"equals\",\"expect\":\"header-ok\"},{\"path\":\"$elapsedMs\",\"op\":\"lt\",\"expect\":\"5000\"}]";
        var execution=call(body,"",rules,List.of(new ApiExtractor("header","HEADER","X-Budget-Fixture",true)));
        assertThat(execution.verdict()).isEqualTo("PASS");assertThat(execution.respCode()).isNull();archived(execution,body);
        assertThat(json.readTree(execution.metadata()).path("structuredBody").path("overLimit").asBoolean()).isTrue();assertThat(execution.respBody().length()).isLessThan(20200);
    }
    @Test void realHttpBodyBudgetFailureIsNotRawContainsPassAndArchiveIsStillComplete()throws Exception{
        String body=payload(LIMIT+1);var execution=call(body,"","[{\"path\":\"\",\"op\":\"contains\",\"expect\":\"ret_code\"}]",null);
        assertThat(execution.verdict()).isEqualTo("FAIL");assertThat(execution.reason()).contains(BUDGET);archived(execution,body);
    }
    @Test void realHttpExpectedBusinessCodeFailsWithBudgetReason()throws Exception{
        String body=payload(LIMIT+1);var execution=call(body,"0",null,null);assertThat(execution.verdict()).isEqualTo("FAIL");assertThat(execution.reason()).contains(BUDGET);archived(execution,body);
    }
    @Test void realHttpBudgetUsesDecodedUtf16TextRatherThanCompressedWireSize()throws Exception{
        String body=payload(LIMIT+1).replace('x','中');var execution=call(body,"","[{\"path\":\"ret_code\",\"op\":\"equals\",\"expect\":\"0\"}]",null,true);
        assertThat(execution.verdict()).isEqualTo("FAIL");assertThat(execution.reason()).contains(BUDGET);archived(execution,body);
        var meta=json.readTree(execution.metadata());assertThat(meta.path("wireBytes").asInt()).isLessThan(20000);assertThat(meta.path("decodedBytes").asInt()).isGreaterThan(LIMIT);assertThat(meta.path("structuredBody").path("bodyChars").asInt()).isEqualTo(LIMIT+1);
    }
    @Test void realHttpOptionalJsonBudgetStillFailsWithoutBreakingHeaderExtraction()throws Exception{
        String body=payload(LIMIT+1);var execution=call(body,"",null,List.of(new ApiExtractor("optional","JSON","id",false),new ApiExtractor("header","HEADER","X-Budget-Fixture",true)));
        assertThat(execution.verdict()).isEqualTo("FAIL");assertThat(execution.reason()).contains(BUDGET);var extracts=json.readTree(execution.metadata()).path("extractions");
        assertThat(extracts.get(0).path("ok").asBoolean()).isFalse();assertThat(extracts.get(0).path("required").asBoolean()).isFalse();assertThat(extracts.get(1).path("ok").asBoolean()).isTrue();archived(execution,body);
    }
}
