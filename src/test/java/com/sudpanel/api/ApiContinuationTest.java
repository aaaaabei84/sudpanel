package com.sudpanel.api;
import static org.assertj.core.api.Assertions.*;
import com.sudpanel.api.configarchive.ApiConfigArchive;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApiContinuationTest {
    @TempDir Path dir;JdbcTemplate jdbc;ApiRepository repo;ApiHistoryRepository history;
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("api.db")));repo=new ApiRepository(jdbc);history=new ApiHistoryRepository(jdbc,repo);}
    private ApiSpec spec(Long group){return new ApiSpec(null,"fixture","POST","https://example.invalid/x","{\"Authorization\":\"fictional-private-value\"}","{\"id\":12345678901234567890123456789012345678,\"password\":\"fictional-private-value\"}",null,"200","0",1000,false,true,group,null);}
    private ApiExec exec(Long batch,String body,String verdict,Long ms,String metadata,String reason,String assertions){return repo.insertExec(new ApiExec(null,batch,null,"fixture",1,1,"POST","https://example.invalid/x",200,"0",null,verdict,reason,ms,body,null,null,"{}",null,"2026-10-14T01:02:03Z",assertions,metadata));}
    String meta="{\"responseBodyTruncated\":false,\"requestBodyTruncated\":false,\"envId\":1,\"dataRowIndex\":2,\"dataIteration\":1}";
    @Test void nativeRoundtripRemapsEveryReferencePreservesPrecisionAndNeverChangesDefault()throws Exception{
        var env=repo.createEnv("source env");repo.saveVars(env.id(),List.of(new ApiVariables.Var("base","https://example.invalid")),List.of(),List.of());
        var group=repo.createCaseGroup("source group",env.id());var c=repo.createCase(spec(group.id()));var ungrouped=repo.createCase(spec(null));
        var scene=repo.saveScenario(null,new ApiScenario(null,"source scene",List.of(new ApiScenario.Step("old","normal",c.id(),env.id(),"STOP",false,null,true),new ApiScenario.Step("cleanup","cleanup",ungrouped.id(),null,"CONTINUE",true,List.of(),false))));
        var archive=new ApiConfigArchive(repo,jdbc);String bundle=json.writeValueAsString(archive.export(true));var imported=archive.commit(bundle);
        assertThat(repo.defaultEnvId()).isEqualTo(1);var copied=repo.getScenario(imported.scenarioIds().get(scene.id()));assertThat(copied.steps().getFirst().caseId()).isEqualTo(imported.caseIds().get(c.id()));assertThat(copied.steps().getFirst().envId()).isEqualTo(imported.environmentIds().get(env.id()));assertThat(copied.steps().getFirst().key()).isNotEqualTo("old");assertThat(copied.steps().getFirst().extractors()).isNull();assertThat(copied.steps().get(1).extractors()).isEmpty();assertThat(repo.getCase(imported.caseIds().get(c.id())).body()).contains("12345678901234567890123456789012345678");
        assertThat(repo.resolveEnvForCase(imported.caseIds().get(ungrouped.id()))).isEqualTo(imported.environmentIds().get(1L));
        assertThat(archive.commit(bundle).environmentIds()).doesNotContainValue(env.id());
        String safe=json.writeValueAsString(archive.export(false));assertThat(safe).doesNotContain("fictional-private-value");assertThat(safe).doesNotContain("api_exec","vault.key");
    }
    @Test void malformedReferencesAndTransactionFailureLeaveNoPartialImport()throws Exception{
        repo.createCase(spec(null));var archive=new ApiConfigArchive(repo,jdbc);String content=json.writeValueAsString(archive.export(true));int before=repo.listEnvs().size();
        assertThat(archive.preview(content.replace("\"schema\":1","\"schema\":99")).valid()).isFalse();
        var tree=json.readTree(content);((com.fasterxml.jackson.databind.node.ObjectNode)tree.path("cases").get(0)).put("groupId",999);assertThat(archive.preview(tree.toString()).valid()).isFalse();assertThat(repo.listEnvs()).hasSize(before);
        jdbc.execute("CREATE TRIGGER fail_import BEFORE INSERT ON api_case BEGIN SELECT RAISE(ABORT,'fixture'); END");assertThatThrownBy(()->archive.commit(content)).isInstanceOf(RuntimeException.class);assertThat(repo.listEnvs()).hasSize(before);assertThat(repo.listCaseGroups()).isEmpty();
    }
    @Test void baselineSurvivesHistoryCleanupAndGeneratedRulesPassExactLargeNumberNullAndOrder()throws Exception{
        var e=exec(null,"{\"id\":12345678901234567890123456789012345678,\"a/b\":null,\"items\":[true,\"hello\"],\"time\":1}","PASS",1L,meta,null,"[]");var b=new ApiBaselineRepository(jdbc,history);var saved=b.create("gold",e.id(),List.of("/time"));assertThat(saved.id()).isPositive();jdbc.update("DELETE FROM api_exec WHERE id=?",e.id());
        var generated=b.assertions(saved.id());String rules=json.writeValueAsString(generated.rules());ApiBodyAssert.validateRules(rules);assertThat(ApiBodyAssert.evaluate(e.respBody(),generated.rules())).isEmpty();assertThat(rules).contains("12345678901234567890123456789012345678","/a~1b").doesNotContain("/time");
        var changed=exec(null,"{\"id\":12345678901234567890123456789012345679,\"items\":[\"hello\",true],\"time\":5}","PASS",1L,meta,null,"[]");assertThat(b.compare(saved.id(),changed.id()).differences()).anyMatch(d->d.path().equals("/a~1b")&&d.kind().equals("REMOVED")).anyMatch(d->d.path().equals("/id"));
        assertThat(new ApiBaselineRepository(jdbc,history).list()).hasSize(1);
    }
    @Test void incompleteBaselineCannotGeneratePretendCompleteAssertions(){var e=exec(null,"{}","PASS",1L,"{}",null,"[]");var b=new ApiBaselineRepository(jdbc,history);var saved=b.create("old",e.id(),List.of());assertThatThrownBy(()->b.assertions(saved.id())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不完整");}
    @Test void statsP90RowsAndFailuresUseAllRecordsButOnlyValidResponseSamples(){var batch=repo.createBatch("stats","ONCE",List.of(1L),0,1);for(long n=1;n<=20;n++)exec(batch.id(),"{}",n==20?"FAIL":"PASS",n,meta,null,n==20?"[{\"path\":\"/id\",\"op\":\"equals\",\"ok\":false}]":"[]");exec(batch.id(),"{}","ERROR",9999L,meta,null,"[]");var stats=history.stats(batch.id());assertThat(stats.sampleCount()).isEqualTo(20);assertThat(stats.p90Ms()).isEqualTo(18);assertThat(stats.byDataRow().getFirst().key()).isEqualTo("2:1");assertThat(stats.byDataRow().getFirst().p90Ms()).isEqualTo(18);assertThat(stats.assertionFailures()).containsExactly(new ApiHistoryRepository.ReasonCount("/id [equals]",1));assertThat(stats.httpCodes()).containsExactly(new ApiHistoryRepository.ReasonCount("200",2));assertThat(stats.trend()).hasSize(1);}
    @Test void policyRedactsConfiguredValuesAcrossRecordsStatsAndJunitWithoutChangingHistory()throws Exception{
        var batch=repo.createBatch("redact","ONCE",List.of(1L),0,1);var first=exec(batch.id(),"{\"profile\":{\"private\":\"FICTIONAL_REPORT_CANARY\"}}","FAIL",1L,meta,"reason", "[]");exec(batch.id(),"{}","FAIL",1L,meta,"leak FICTIONAL_REPORT_CANARY", "[]");
        new ApiReportPolicy(jdbc).save(new ApiReportPolicy.Policy(List.of("/profile/private")));String report=json.writeValueAsString(history.exportJson(batch.id(),false));assertThat(report).doesNotContain("FICTIONAL_REPORT_CANARY").contains("***");String xml=history.exportJunit(batch.id(),false);assertThat(xml).doesNotContain("FICTIONAL_REPORT_CANARY");javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));assertThat(history.detail(first.id()).respBody()).contains("FICTIONAL_REPORT_CANARY");assertThat(json.writeValueAsString(history.exportJson(batch.id(),true))).contains("FICTIONAL_REPORT_CANARY");
    }
    @Test void encodedCredentialsAndWhitespaceBaselineDoNotSilentlyLeakOrGenerateFailingRule()throws Exception{
        var c=repo.createCase(new ApiSpec(null,"encoded","POST","https://username:fictional-uri@example.invalid/?access%5Ftoken=fictional-query",null,"access%5Ftoken=fictional-form&regular=kept",null,null,null,1000,false,true,null,null));
        var archive=new ApiConfigArchive(repo,jdbc);String safe=json.writeValueAsString(archive.export(false));assertThat(safe).doesNotContain("fictional-uri","fictional-query","fictional-form").contains("regular=kept");
        var tree=json.readTree(json.writeValueAsString(archive.export(true)));((com.fasterxml.jackson.databind.node.ArrayNode)tree.path("cases")).addNull();assertThat(archive.preview(tree.toString()).valid()).isFalse();
        var e=exec(null,"{\"value\":\" leading and trailing \"}","PASS",1L,meta,null,"[]");var baselines=new ApiBaselineRepository(jdbc,history);var b=baselines.create("space",e.id(),List.of());var rules=baselines.assertions(b.id()).rules();assertThat(ApiBodyAssert.evaluate(e.respBody(),rules)).isEmpty();assertThat(rules).anyMatch(r->r.op().equals("regex"));
    }

}
