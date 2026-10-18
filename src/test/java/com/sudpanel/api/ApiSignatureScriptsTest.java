package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

class ApiSignatureScriptsTest {
    @TempDir Path temp;
    ApiRepository repo; ApiRunner runner; HttpServer server; String url;
    List<Map<String,String>> wires=new CopyOnWriteArrayList<>();
    static final String SECRET="FICTIONAL_SCRIPT_SECRET_ONLY";
    static final String SCRIPT="""
        var CryptoJS = require('crypto-js');
        var raw = pm.request.body.raw;
        var appid = pm.environment.get('app_id');
        var key = pm.environment.get('app_secret');
        var times = parseInt(+new Date()/1000);
        pm.environment.set('times', times);
        var data = raw.replace('{{times}}',times).replace('{{mg_id}}',pm.environment.get('mg_id')).replace('{{app_id}}',appid);
        var nonce = 'FICTIONAL_NONCE';
        pm.globals.set('nonce',nonce);
        var str = appid+'\\n'+times+'\\n'+nonce+'\\n'+data+'\\n';
        var sign = CryptoJS.HmacSHA1(str,key).toString();
        console.log(str); console.log(sign); console.log(pm.request.headers);
        pm.globals.set('sign',sign);
        """;
    static final String TEMPLATE="Sud-Auth app_id=\"{{app_id}}\",timestamp=\"{{times}}\",nonce=\"{{nonce}}\",signature=\"{{sign}}\"";
    @BeforeEach void start() throws Exception {
        repo=new ApiRepository(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("api.db"))));
        runner=new ApiRunner(repo,new CodeDictionary(repo),"");
        repo.saveVars(1,List.of(),List.of(),List.of(new ApiVariables.Var("app_id","FICTIONAL_APP"),new ApiVariables.Var("mg_id","12345678901234567890123456789012345678"),new ApiVariables.Var("times","1111111111"),new ApiVariables.Var("app_secret",SECRET,true)));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            String body=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            wires.add(Map.of("body",body,"authorization",Objects.toString(e.getRequestHeaders().getFirst("Authorization"),""),"x-sign",Objects.toString(e.getRequestHeaders().getFirst("X-Sign"),"")));
            if(e.getRequestURI().getPath().equals("/redirect")){e.getResponseHeaders().set("Location","/target");e.sendResponseHeaders(307,-1);e.close();return;}
            byte[] response=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,response.length);e.getResponseBody().write(response);e.close();
        });server.start();url="http://127.0.0.1:"+server.getAddress().getPort()+"/";
    }
    @AfterEach void stop(){if(server!=null)server.stop(0);}
    ApiSpec spec(String script,String template,String body) {
        var auth=new ApiRequestOptions.Auth("SCRIPT",null,null,null,null,null,null,null,null,null,script,template);
        return new ApiSpec(null,"fictional-script","POST",url,"{\"X-Sign\":\"{{sign}}\"}",body,null,"200",null,15000,false,true,null,null,new ApiRequestOptions("RAW",List.of(),auth,List.of()));
    }
    @Test void suppliedFormulaSignsActualRawBytesAndDoesNotMutateSavedVariables() throws Exception {
        String raw="  {\"app_id\":\"{{app_id}}\",\"mg_id\":{{mg_id}},\"times\":{{times}},\"中文\":\"换行\\n原文\",\"extra\":\"{{$uuid}}\"}  ";
        var request=spec(SCRIPT,TEMPLATE,raw);
        var before=repo.getVars(1);
        var prepared=runner.prepareForUi(request,1L,false);
        assertThat(prepared.valid()).as(prepared.error()).isTrue();assertThat(wires).isEmpty();
        assertThat(prepared.body()).doesNotContain(SECRET,"FICTIONAL_NONCE");
        var execution=runner.send(request,1L,Map.of());
        assertThat(execution.verdict()).as(execution.reason()).isEqualTo("PASS");assertThat(wires).hasSize(1);
        var wire=wires.getFirst();assertThat(wire.get("body")).startsWith("  {").endsWith("}  ").contains("12345678901234567890123456789012345678");
        var timestamp=java.util.regex.Pattern.compile("timestamp=\"([0-9]+)\"").matcher(wire.get("authorization"));assertThat(timestamp.find()).isTrue();
        String input="FICTIONAL_APP\n"+timestamp.group(1)+"\nFICTIONAL_NONCE\n"+wire.get("body")+"\n";
        var hmac=Mac.getInstance("HmacSHA1");hmac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA1"));
        String expected=HexFormat.of().formatHex(hmac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        assertThat(wire.get("authorization")).endsWith("signature=\""+expected+"\"");assertThat(wire.get("x-sign")).isEqualTo(expected);
        assertThat(repo.getVars(1)).isEqualTo(before);
        var metadata=new ObjectMapper().readTree(execution.metadata());
        assertThat(metadata.path("signatureScript").path("bodyVerified").asBoolean()).isTrue();
        assertThat(metadata.path("safeDisplay").toString()).doesNotContain(expected,SECRET,"FICTIONAL_NONCE");
        assertThat(execution.metadata()).doesNotContain("console.log",input);
    }
    @Test void templateOnlyUsesSelectedVariableSnapshotAndRejectsMissingEvenWhenLoose() {
        var auth=new ApiRequestOptions.Auth("TEMPLATE",null,null,null,null,null,null,null,null,null,"throw new Error('must not execute')","Bearer {{app_id}}");
        var request=new ApiSpec(null,"template","POST",url,"{}","{}",null,"200",null,10000,false,true,null,null,new ApiRequestOptions("RAW",List.of(),auth,List.of(),null,null,false));
        assertThat(runner.send(request,1L,Map.of()).verdict()).isEqualTo("PASS");assertThat(wires.getFirst().get("authorization")).isEqualTo("Bearer FICTIONAL_APP");
        var badAuth=new ApiRequestOptions.Auth("TEMPLATE",null,null,null,null,null,null,null,null,null,null,"Bearer {{missing}}");
        var bad=new ApiSpec(null,"bad","POST",url,"{}","{}",null,"200",null,10000,false,true,null,null,new ApiRequestOptions("RAW",List.of(),badAuth,List.of(),null,null,false));
        assertThat(runner.send(bad,1L,Map.of()).verdict()).isEqualTo("ERROR");assertThat(wires).hasSize(1);
    }
    @Test void failuresNeverSendAndCannotLeakThrownSecret() {
        for(String script:List.of("throw new Error(pm.environment.get('app_secret'))","while(true){}","require('fs')","Java.type('java.lang.System')","pm.sendRequest('http://example.test/')","var = ;")) {
            var result=runner.send(spec(script,null,"{}"),1L,Map.of());
            assertThat(result.verdict()).as(script).isEqualTo("ERROR");
            assertThat(Objects.toString(result.reason(),"")+Objects.toString(result.error(),"")).doesNotContain(SECRET);
        }
        assertThat(wires).isEmpty();
        assertThat(runner.send(spec(SCRIPT,TEMPLATE,"{\"times\":{{times}}}"),1L,Map.of()).verdict()).isEqualTo("PASS");assertThat(wires).hasSize(1);
    }
    @Test void unstableBodyMutationAndMissingScriptOutputAreBlocked() {
        assertThat(runner.send(spec("pm.request.body.update(pm.request.body.raw+'x');",null,"abc"),1L,Map.of()).verdict()).isEqualTo("ERROR");
        assertThat(runner.send(spec("pm.environment.set('times',1);",TEMPLATE,"{}"),1L,Map.of()).verdict()).isEqualTo("ERROR");
        assertThat(wires).isEmpty();
    }
    @Test void oldEnvironmentSignatureDoesNotSupplyScriptSecrets() {
        repo.saveVars(1,List.of(),List.of(),List.of());
        repo.saveSignConfig(1,true,"FICTIONAL_OLD_APP",SECRET,null,"FICTIONAL_OLD_GAME");
        var result=runner.send(spec(SCRIPT,TEMPLATE,"{}"),1L,Map.of());
        assertThat(result.verdict()).isEqualTo("ERROR");assertThat(wires).isEmpty();
    }
    @Test void localPriorityAndEnvironmentUnsetMatchPostmanLookupWithoutPersisting() {
        var script="pm.globals.set('times','global'); pm.environment.set('times','env'); pm.variables.set('times','local'); pm.globals.set('sign','FICTIONAL_SIGN');";
        var first=runner.send(spec(script,"Bearer {{times}}","{\"times\":\"{{times}}\"}"),1L,Map.of());
        assertThat(first.verdict()).as(first.reason()).isEqualTo("PASS");assertThat(wires.getFirst().get("authorization")).isEqualTo("Bearer local");
        var unset="pm.globals.set('times','global'); pm.environment.unset('times'); pm.variables.set('times','local'); pm.variables.unset('times'); pm.globals.set('sign','FICTIONAL_SIGN');";
        assertThat(runner.send(spec(unset,"Bearer {{times}}","{}"),1L,Map.of()).verdict()).isEqualTo("PASS");
        assertThat(wires.getLast().get("authorization")).isEqualTo("Bearer global");
        assertThat(repo.getVars(1).body()).contains(new ApiVariables.Var("times","1111111111",false));
    }
    @Test void cancellingScriptStopsBeforeBusinessAndSameIdNeverResubmits() throws Exception {
        var request=spec("while(true){}",null,"{}");String id=UUID.randomUUID().toString();
        runner.submitRequest(request,1L,Map.of(),id);Thread.sleep(150);
        long started=System.nanoTime();runner.cancelRequest(id);
        while(runner.request(id).finishedAt()==null&&(System.nanoTime()-started)<3_000_000_000L)Thread.sleep(10);
        var result=runner.request(id);assertThat(result.state()).isEqualTo("CANCELLED");assertThat(result.serverOutcomeUnknown()).isFalse();
        assertThat(wires).isEmpty();assertThat(runner.submitRequest(request,1L,Map.of(),id).state()).isEqualTo("CANCELLED");assertThat(wires).isEmpty();
    }
    @Test void literalSecretReferenceAccessWorksAndDynamicUnreadableReferenceFailsClosed() {
        var store=new ApiSecretStore(repo.jdbc());var secret=store.create(1,new ApiSecretStore.Input("FICTIONAL_VAULT","VAULT",SECRET,null,null));
        repo.saveVars(1,List.of(),List.of(),List.of(new ApiVariables.Var("app_id","FICTIONAL_APP"),new ApiVariables.Var("app_secret",secret.reference(),true)));
        assertThat(runner.send(spec(SCRIPT,TEMPLATE,"{}"),1L,Map.of()).verdict()).isEqualTo("PASS");
        store.enabled(1,secret.id(),false);
        assertThatThrownBy(()->runner.send(spec(SCRIPT,TEMPLATE,"{}"),1L,Map.of())).hasMessageContaining("停用");assertThat(wires).hasSize(1);
        var dynamic="var key=pm.environment.get('app_'+'secret'); var C=require('crypto-js');pm.globals.set('sign',C.HmacSHA1(pm.request.body.raw,key).toString());";
        assertThat(runner.send(spec(dynamic,null,"{}"),1L,Map.of()).verdict()).isEqualTo("ERROR");assertThat(wires).hasSize(1);
    }
    @Test void scriptMethodMatchesNormalizedWireMethodForDirectRestInput() {
        var auth=new ApiRequestOptions.Auth("SCRIPT",null,null,null,null,null,null,null,null,null,"pm.globals.set('sign',pm.request.method);","Method {{sign}}");
        var request=new ApiSpec(null,"method"," post ",url,"{}","{}",null,"200",null,15000,false,true,null,null,new ApiRequestOptions("RAW",List.of(),auth,List.of()));
        var result=runner.send(request,1L,Map.of());assertThat(result.verdict()).as(result.reason()).isEqualTo("PASS");assertThat(wires.getFirst().get("authorization")).isEqualTo("Method POST");
    }
    @Test void sameOriginRedirectToAnotherPathCannotReuseAUrlBoundSignatureOrTemplate() throws Exception {
        for(String type:List.of("SCRIPT","TEMPLATE")) {
            var auth=new ApiRequestOptions.Auth(type,null,null,null,null,null,null,null,null,null,type.equals("SCRIPT")?"pm.globals.set('sign',CryptoJS.SHA256(pm.request.url.toString()).toString());":null,type.equals("SCRIPT")?"HMAC {{sign}}":"Bearer FICTIONAL_TOKEN");
            var options=new ApiRequestOptions("RAW",List.of(),auth,List.of(),null,new ApiRequestOptions.Transport("ALWAYS","DIRECT",null,null,"OFF",null),true);
            var request=new ApiSpec(null,"redirect","POST",url+"redirect","{}","{}",null,"200",null,15000,false,true,null,null,options);
            var result=runner.send(request,1L,Map.of());assertThat(result.httpStatus()).isEqualTo(307);
            assertThat(new ObjectMapper().readTree(result.metadata()).path("blockedRedirect").asBoolean()).isTrue();
        }
        assertThat(wires).hasSize(2);
    }
    @Test void optedInPostmanScriptActuallyPersistsAndImportsWithoutExecuting() throws Exception {
        var collection=Map.of("info",Map.of("name","FICTIONAL_IMPORT","schema","https://schema.getpostman.com/json/collection/v2.1.0/collection.json"),
            "variable",List.of(Map.of("key","app_id","value","FICTIONAL_APP"),Map.of("key","mg_id","value","12345678901234567890123456789012345678"),Map.of("key","app_secret","value",SECRET)),
            "event",List.of(Map.of("listen","prerequest","script",Map.of("exec",SCRIPT))),
            "item",List.of(Map.of("name","valid","request",Map.of("method","POST","url",url,"header",List.of(Map.of("key","Authorization","value",TEMPLATE)),"body",Map.of("mode","raw","raw","{\"times\":{{times}}}"))),Map.of("name","missing-url","request",Map.of("method","GET"))));
        var service=new ApiImportService(repo,repo.jdbc());
        var committed=service.commit(new ApiImportService.CommitRequest("POSTMAN",new ObjectMapper().writeValueAsString(collection),null,"FICTIONAL_IMPORTED",null,null,true,true));
        assertThat(committed.skippedCount()).isEqualTo(1);assertThat(committed.caseIds()).hasSize(1);assertThat(wires).isEmpty();
        var saved=repo.getCase(committed.caseIds().getFirst());assertThat(saved.options().auth().type()).isEqualTo("SCRIPT");assertThat(saved.options().auth().script()).contains(SCRIPT);assertThat(saved.options().auth().authorizationTemplate()).isNull();
        var result=runner.send(saved,null,Map.of());assertThat(result.verdict()).as(result.reason()).isEqualTo("PASS");assertThat(wires).hasSize(1);assertThat(wires.getFirst().get("authorization")).contains("signature=\"");
    }
    @Test void encryptedFrozenScriptSurvivesReopenAndReplayRemainsExplicit() throws Exception {
        var saved=repo.createCase(spec(SCRIPT,TEMPLATE,"{}"));
        var batch=runner.startBatch("FICTIONAL_SCRIPT_BATCH",List.of(saved.id()),0,1);waitBatch(runner,batch.id());
        assertThat(wires).hasSize(1);
        assertThat(repo.getExecutionPlan(batch.id()).steps().getFirst().spec().options().auth().script()).isEqualTo(SCRIPT);
        repo.updateCase(saved.id(),spec("throw new Error('current-only');",null,"{}"));
        var reopened=new ApiRepository(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("api.db"))));
        var restarted=new ApiRunner(reopened,new CodeDictionary(reopened),"");Thread.sleep(100);assertThat(wires).hasSize(1);
        var replay=restarted.replay(batch.id(),"ORIGINAL","FICTIONAL_REPLAY",false);waitBatch(restarted,replay.id());
        assertThat(wires).hasSize(2);assertThat(reopened.getBatch(replay.id()).passCount()).isEqualTo(1);
    }
    private void waitBatch(ApiRunner running,long id) throws Exception {
        long end=System.nanoTime()+15_000_000_000L;while(running.isRunning(id)&&System.nanoTime()<end)Thread.sleep(10);
        assertThat(running.isRunning(id)).isFalse();
    }
    @Test void authFieldsRoundTripAndDefaultSharingOmitsExecutableSource() throws Exception {
        var saved=repo.createCase(spec(SCRIPT,TEMPLATE,"{}"));
        assertThat(repo.getCase(saved.id()).options().auth().script()).isEqualTo(SCRIPT);
        var archive=new com.sudpanel.api.configarchive.ApiConfigArchive(repo,repo.jdbc());
        String safe=new ObjectMapper().writeValueAsString(archive.export(false));
        assertThat(safe).doesNotContain("console.log",SECRET,"HmacSHA1");
        String full=new ObjectMapper().writeValueAsString(archive.export(true));assertThat(full).contains("HmacSHA1");
    }
}
