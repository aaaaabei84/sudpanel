package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sudpanel.web.ApiImportController;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Pure parser and mock-repository commit contracts. No worker, real repository save or device execution. */
class ApiPostmanSignatureScriptImportTest {
    @TempDir Path dir;
    final ObjectMapper json=new ObjectMapper();
    Map<String,Object> event(String listen,Object exec){return Map.of("listen",listen,"script",Map.of("exec",exec));}
    Map<String,Object> req(String name,List<Map<String,Object>> events,Map<String,Object> auth){
        var request=new LinkedHashMap<String,Object>();request.put("method","GET");request.put("url","https://example.invalid/never-send");if(auth!=null)request.put("auth",auth);
        var item=new LinkedHashMap<String,Object>();item.put("name",name);item.put("request",request);if(events!=null)item.put("event",events);return item;
    }
    String doc(List<Map<String,Object>> items,List<Map<String,Object>> events,Map<String,Object> auth)throws Exception{
        var root=new LinkedHashMap<String,Object>();root.put("info",Map.of("name","signature-import-fixture","schema","https://schema.getpostman.com/json/collection/v2.1.0/collection.json"));root.put("item",items);if(events!=null)root.put("event",events);if(auth!=null)root.put("auth",auth);return json.writeValueAsString(root);
    }
    ApiInterchange.Input input(String source,Boolean enabled,Boolean skip){return new ApiInterchange.Input("POSTMAN",source,null,null,null,skip,enabled);}
    ApiInterchange.Preview preview(String source,Boolean enabled,Boolean skip){return ApiInterchange.preview(input(source,enabled,skip));}
    ApiRequestOptions.Auth auth(ApiInterchange.Preview p,int index){return p.requests().get(index).spec().options().auth();}
    ApiImportService imports(ApiRepository repo){return new ApiImportService(repo,new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("transaction-only.db"))));}
    ApiRepository repository(){
        ApiRepository repo=mock(ApiRepository.class);var env=new ApiEnv(1,"fixture",true,0);when(repo.defaultEnvId()).thenReturn(1L);when(repo.getEnv(anyLong())).thenReturn(env);when(repo.listEnvs()).thenReturn(List.of(env));when(repo.listCaseGroups()).thenReturn(List.of());when(repo.createCaseGroup(anyString(),anyLong())).thenAnswer(call->new ApiCaseGroup(11L,call.getArgument(0),0,0,call.getArgument(1)));
        when(repo.createCase(any())).thenAnswer(call->{ApiSpec s=call.getArgument(0);return new ApiSpec(21L,s.name(),s.method(),s.url(),s.headers(),s.body(),s.note(),s.expectStatus(),s.expectCode(),s.timeoutMs(),s.insecure(),s.enabled(),s.groupId(),s.asserts(),s.options());});return repo;
    }
    @Test void oldPayloadAndEveryLegacyConstructorKeepScriptsDisabled()throws Exception{
        String source=doc(List.of(req("one",List.of(event("prerequest","throw new Error('INERT');")),null)),null,null);
        for(ApiInterchange.Input old:List.of(new ApiInterchange.Input("POSTMAN",source,null),new ApiInterchange.Input("POSTMAN",source,null,null,null),new ApiInterchange.Input("POSTMAN",source,null,null,null,true))){assertThat(old.enableSignatureScripts()).isNull();assertThat(ApiInterchange.preview(old).requests().getFirst().spec().options().auth().type()).isEqualTo("NONE");}
        for(Boolean enabled:Arrays.asList(null,false)){var p=preview(source,enabled,false);assertThat(p.errors()).isEmpty();assertThat(auth(p,0).script()).isNull();assertThat(p.requests().getFirst().spec().note()).contains("INERT");}
        assertThat(json.readValue("{\"format\":\"POSTMAN\",\"content\":\"x\"}",ApiInterchange.Input.class).enableSignatureScripts()).isNull();
        assertThat(new ApiImportService.CommitRequest("POSTMAN",source,null,null,1L).enableSignatureScripts()).isNull();assertThat(new ApiImportService.CommitRequest("POSTMAN",source,null,null,1L,null).enableSignatureScripts()).isNull();assertThat(new ApiImportService.CommitRequest("POSTMAN",source,null,null,1L,null,true).enableSignatureScripts()).isNull();
    }
    @Test void collectionFolderAndRequestScriptsMergeInOrderAndKeepSourceText()throws Exception{
        var request=req("one",List.of(event("prerequest","requestLiteral('{{sign}}');")),null);var folder=Map.<String,Object>of("name","folder","event",List.of(event("prerequest",List.of("folderFirst();","folderSecond();"))),"item",List.of(request));String source=doc(List.of(folder),List.of(event("prerequest","collectionFirst();"),event("prerequest",List.of("collectionSecond();"))),null);
        var p=preview(source,true,false);assertThat(p.errors()).isEmpty();assertThat(auth(p,0).type()).isEqualTo("SCRIPT");assertThat(auth(p,0).script()).isEqualTo("collectionFirst();\n;\ncollectionSecond();\n;\nfolderFirst();\nfolderSecond();\n;\nrequestLiteral('{{sign}}');");assertThat(auth(p,0).authorizationTemplate()).isNull();assertThat(p.requests().getFirst().spec().note()).contains("folderFirst","requestLiteral","collectionFirst");
    }
    @Test void siblingScriptsNeverLeakAcrossFoldersOrRequests()throws Exception{
        var folderA=Map.<String,Object>of("name","A","event",List.of(event("prerequest","folderA();")),"item",List.of(req("A1",List.of(event("prerequest","requestA1();")),null),req("A2",null,null)));
        var folderB=Map.<String,Object>of("name","B","event",List.of(event("prerequest","folderB();")),"item",List.of(req("B1",null,null)));
        var p=preview(doc(List.of(folderA,folderB),List.of(event("prerequest","root();")),null),true,false);assertThat(p.errors()).isEmpty();assertThat(auth(p,0).script()).contains("root();","folderA();","requestA1();");assertThat(auth(p,1).script()).contains("root();","folderA();").doesNotContain("requestA1","folderB");assertThat(auth(p,2).script()).contains("root();","folderB();").doesNotContain("folderA","requestA1");
    }
    @Test void disabledEventDisabledScriptAndTestStayRemarksOnly()throws Exception{
        var disabledEvent=Map.<String,Object>of("listen","prerequest","disabled",true,"script",Map.of("exec",42));var disabledScript=Map.<String,Object>of("listen","prerequest","script",Map.of("disabled",true,"exec",42));var events=List.of(disabledEvent,disabledScript,event("test","TEST_ONLY();"),event("prerequest","ACTIVE_ONLY();"));
        var p=preview(doc(List.of(req("one",events,null)),null,null),true,false);assertThat(p.errors()).isEmpty();assertThat(auth(p,0).script()).isEqualTo("ACTIVE_ONLY();");assertThat(p.requests().getFirst().spec().note()).contains("TEST_ONLY","disabled","42");
    }
    @Test void testOnlyBlankAndEmptyExecDoNotInventScriptAuthentication()throws Exception{
        for(Object exec:List.of("",List.of(),"  \n ")){var p=preview(doc(List.of(req("one",List.of(event("test","TEST_ONLY();"),event("prerequest",exec)),null)),null,null),true,false);assertThat(p.errors()).isEmpty();assertThat(auth(p,0).type()).isEqualTo("NONE");assertThat(auth(p,0).script()).isNull();}
    }
    @Test void automaticAuthenticationConflictIsPerRequestAndExplicitNoauthCanOverrideInheritance()throws Exception{
        for(var existing:List.of(Map.<String,Object>of("type","bearer","bearer",List.of(Map.of("key","token","value","fixture"))),Map.<String,Object>of("type","basic","basic",List.of(Map.of("key","username","value","fixture"))),Map.<String,Object>of("type","apikey","apikey",List.of(Map.of("key","key","value","fixture"),Map.of("key","value","value","fake"))))){var p=preview(doc(List.of(req("conflict",null,existing)),List.of(event("prerequest","root();")),null),true,false);assertThat(p.errors()).anyMatch(e->e.contains("鉴权冲突"));assertThat(p.requests()).isEmpty();}
        var inherited=Map.<String,Object>of("type","bearer","bearer",List.of(Map.of("key","token","value","fixture")));var p=preview(doc(List.of(req("override",null,Map.of("type","noauth"))),List.of(event("prerequest","root();")),inherited),true,false);assertThat(p.errors()).isEmpty();assertThat(auth(p,0).type()).isEqualTo("SCRIPT");
    }
    @Test void strictAndExplicitSkipKeepConflictLocationAndNeverDiscardOldAuthSilently()throws Exception{
        String source=doc(List.of(req("valid",null,null),req("conflict",null,Map.of("type","bearer","bearer",List.of(Map.of("key","token","value","fixture"))))),List.of(event("prerequest","root();")),null);
        var strict=preview(source,true,false);assertThat(strict.errors()).hasSize(1);assertThat(strict.requests()).hasSize(1);assertThat(strict.skippedCount()).isZero();var skip=preview(source,true,true);assertThat(skip.errors()).isEmpty();assertThat(skip.requests()).hasSize(1);assertThat(skip.skippedRequests()).containsExactly(new ApiInterchange.SkippedRequest("root/item/1","conflict",strict.errors().getFirst().substring(strict.errors().getFirst().indexOf("：")+1)));assertThat(skip.losses()).anyMatch(loss->loss.code().equals("REQUEST_SKIPPED")&&!loss.blocking());
    }
    @Test void authorizationHeaderBodyAndOtherOptionsRemainVerbatimAndTemplateStaysOptional()throws Exception{
        String raw="{ \"id\":12345678901234567890123456789012345678 }";var request=new LinkedHashMap<String,Object>();request.put("method","POST");request.put("url","https://example.invalid/never-send");request.put("header",List.of(Map.of("key","authorization","value","Sud-Auth signature=\"{{sign}}\""),Map.of("key","X-Keep","value","fixture")));request.put("body",Map.of("mode","raw","raw",raw));var item=Map.<String,Object>of("name","one","request",request,"event",List.of(event("prerequest","pm.globals.set('sign','fixture');")));var p=preview(doc(List.of(item),null,null),true,false);assertThat(p.errors()).isEmpty();var spec=p.requests().getFirst().spec();assertThat(spec.headers()).contains("authorization","{{sign}}","X-Keep");assertThat(spec.body()).isEqualTo(raw);assertThat(spec.options().bodyType()).isEqualTo("RAW");assertThat(spec.options().strictVariables()).isTrue();assertThat(spec.options().transport().redirect()).isEqualTo("NORMAL");assertThat(auth(p,0).authorizationTemplate()).isNull();
    }
    @Test void exactUtf8LimitPassesAndAnyOverLimitBlocksWholeImportEvenWithSkip()throws Exception{
        for(String code:List.of("x".repeat(65536),"中".repeat(21845))){var p=preview(doc(List.of(req("one",List.of(event("prerequest",code)),null)),null,null),true,true);assertThat(p.errors()).isEmpty();assertThat(auth(p,0).script()).isEqualTo(code);}
        for(String code:List.of("x".repeat(65537),"中".repeat(21846)," ".repeat(65537))){var p=preview(doc(List.of(req("before",null,null),req("oversized",List.of(event("prerequest",code)),null)),null,null),true,true);assertThat(p.errors()).anyMatch(e->e.contains("64KiB UTF-8"));assertThat(p.skippedCount()).isZero();assertThat(p.losses()).noneMatch(loss->loss.code().equals("REQUEST_SKIPPED"));}
    }
    @Test void inheritedAggregateBudgetIncludesSeparatorsAndDoesNotTruncate()throws Exception{
        String root="r".repeat(32766),own="q".repeat(32767);var p=preview(doc(List.of(req("one",List.of(event("prerequest",own)),null)),List.of(event("prerequest",root)),null),true,false);assertThat(p.errors()).isEmpty();assertThat(auth(p,0).script().getBytes(StandardCharsets.UTF_8)).hasSize(65536);var over=preview(doc(List.of(req("one",List.of(event("prerequest",own+"q")),null)),List.of(event("prerequest",root)),null),true,true);assertThat(over.errors()).anyMatch(e->e.contains("64KiB"));assertThat(over.skippedCount()).isZero();
    }
    @Test void malformedEnabledExecIsGlobalStructureErrorAndCannotBeSkipped()throws Exception{
        for(Object invalid:List.of(42,List.of("ok",42),Map.of("source","not exec"))){String source=doc(List.of(req("bad",List.of(event("prerequest",invalid)),null)),null,null);var p=preview(source,true,true);assertThat(p.errors()).anyMatch(e->e.contains("exec"));assertThat(p.skippedCount()).isZero();assertThat(preview(source,false,true).errors()).isEmpty();}
    }
    @Test void optInIsRejectedOutsidePostmanAndDoesNotBreakEnvironmentOnlyFiles(){
        for(String format:List.of("CURL","OPENAPI"))assertThat(ApiInterchange.preview(new ApiInterchange.Input(format,"{}",null,null,null,false,true)).errors()).anyMatch(e->e.contains("仅支持 Postman"));
        var p=preview("{\"name\":\"environment\",\"values\":[{\"key\":\"a\",\"value\":\"b\"}]}",true,false);assertThat(p.errors()).isEmpty();assertThat(p.requests()).isEmpty();assertThat(p.variables().body()).extracting(ApiVariables.Var::value).containsExactly("b");
    }
    @Test void commitIndependentlyReparsesOptInAndPreservesOriginalScript()throws Exception{
        String code="// keep {{literal}} and 12345678901234567890123456789012345678\nthrow new Error('INERT');",source=doc(List.of(req("one",List.of(event("prerequest",code)),null)),null,null);assertThat(auth(preview(source,false,false),0).type()).isEqualTo("NONE");var repo=repository();imports(repo).commit(new ApiImportService.CommitRequest("POSTMAN",source,null,null,1L,null,false,true));var captured=ArgumentCaptor.forClass(ApiSpec.class);verify(repo).createCase(captured.capture());assertThat(captured.getValue().options().auth().type()).isEqualTo("SCRIPT");assertThat(captured.getValue().options().auth().script()).isEqualTo(code);
        var legacy=repository();imports(legacy).commit(new ApiImportService.CommitRequest("POSTMAN",source,null,null,1L,null,false));var old=ArgumentCaptor.forClass(ApiSpec.class);verify(legacy).createCase(old.capture());assertThat(old.getValue().options().auth().type()).isEqualTo("NONE");
    }
    @Test void globalScriptCapacityErrorStopsCommitBeforeAnyRepositoryAction()throws Exception{
        String source=doc(List.of(req("one",List.of(event("prerequest","x".repeat(65537))),null)),null,null);var repo=mock(ApiRepository.class);assertThatThrownBy(()->imports(repo).commit(new ApiImportService.CommitRequest("POSTMAN",source,null,null,1L,null,true,true))).hasMessageContaining("64KiB");verifyNoInteractions(repo);
    }
    @Test void parserAndMockCommitNeverExecuteCodeOrSendAnyHttp()throws Exception{
        var hits=new AtomicInteger();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{hits.incrementAndGet();exchange.sendResponseHeaders(200,0);exchange.close();});server.start();try{
            String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/",code="fetch('"+endpoint+"');throw new Error('MUST_NOT_RUN');";var item=req("one",List.of(event("prerequest",code)),null);((Map<String,Object>)item.get("request")).put("url",endpoint);String source=doc(List.of(item),null,null);var p=preview(source,true,false);assertThat(p.errors()).isEmpty();assertThat(auth(p,0).script()).isEqualTo(code);imports(repository()).commit(new ApiImportService.CommitRequest("POSTMAN",source,null,null,1L,null,false,true));assertThat(hits).hasValue(0);
        }finally{server.stop(0);}
    }
    @Test void mockMvcBindsExplicitOptionAndKeepsDefaultOff()throws Exception{
        String source=doc(List.of(req("one",List.of(event("prerequest","INERT_ONLY();")),null)),null,null);var mvc=MockMvcBuilders.standaloneSetup(new ApiImportController(imports(repository()))).build();
        mvc.perform(post("/api/apitest/import/preview").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("format","POSTMAN","content",source,"enableSignatureScripts",true)))).andExpect(status().isOk()).andExpect(jsonPath("$.requests[0].spec.options.auth.type").value("SCRIPT")).andExpect(jsonPath("$.requests[0].spec.options.auth.script").value("INERT_ONLY();"));
        mvc.perform(post("/api/apitest/import/preview").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("format","POSTMAN","content",source)))).andExpect(status().isOk()).andExpect(jsonPath("$.requests[0].spec.options.auth.type").value("NONE"));
    }
}
