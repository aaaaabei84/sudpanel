package com.sudpanel.api;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sudpanel.api.configarchive.ApiConfigArchive;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
class ApiPostmanVariableMappingTest {
 @TempDir Path dir;JdbcTemplate jdbc;ApiRepository repo;ApiImportService imports;
 @BeforeEach void setup(){jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("map.db")));repo=new ApiRepository(jdbc);imports=new ApiImportService(repo,jdbc);}
 String collection(String url){return "{\"info\":{\"name\":\"source\",\"schema\":\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"},\"variable\":[{\"key\":\"same\",\"value\":\"collection\"},{\"key\":\"credential\",\"value\":\"OLD_PRIVATE\",\"type\":\"secret\"}],\"item\":[{\"name\":\"all-scopes\",\"request\":{\"method\":\"POST\",\"url\":\""+url+"/{{same}}\",\"header\":[{\"key\":\"X-Same\",\"value\":\"{{same}}\"},{\"key\":\"X-Credential\",\"value\":\"{{credential}}\"}],\"body\":{\"mode\":\"raw\",\"raw\":\"{\\\"same\\\":\\\"{{same}}\\\",\\\"id\\\":12345678901234567890123456789012345678}\"}}}]}";}
 String environment="{\"name\":\"source-env\",\"values\":[{\"key\":\"same\",\"value\":\"environment\"},{\"key\":\"credential\",\"value\":\"NEW_PRIVATE\",\"type\":\"secret\"},{\"key\":\"off\",\"value\":\"DISABLED_PRIVATE\",\"type\":\"secret\",\"enabled\":false}]}";
 @Test void mappingPreviewShowsSourcesDisabledAndMaskedSecretsWithoutRawSecretValues() throws Exception {
  var preview=ApiInterchange.preview(new ApiInterchange.Input("POSTMAN",collection("https://example.test"),environment));assertThat(preview.errors()).isEmpty();
  var active=preview.variableMappings().stream().filter(v->v.name().equals("same")&&!v.overridden()).toList();assertThat(active).hasSize(1);assertThat(active.getFirst().source()).isEqualTo("ENVIRONMENT");assertThat(active.getFirst().scopes()).containsExactly("BASE","HEADER","BODY");assertThat(active.getFirst().references()).hasSize(3);
  assertThat(preview.variableMappings()).anyMatch(v->v.name().equals("same")&&v.overridden());assertThat(preview.variableMappings()).anyMatch(v->v.name().equals("off")&&!v.enabled()&&v.secret());
  assertThat(new ObjectMapper().writeValueAsString(preview)).doesNotContain("OLD_PRIVATE","NEW_PRIVATE","DISABLED_PRIVATE");
  var shown=ApiInterchange.preview(new ApiInterchange.Input("POSTMAN",collection("https://example.test"),environment,null,true));assertThat(shown.variables().header()).contains(new ApiVariables.Var("credential","NEW_PRIVATE",true));
 }
 @Test void commitUsesUnmaskedOriginalValuesAndKeepsSecretFlagAfterReopenAndLegacyEdit(){
  var committed=imports.commit(new ApiImportService.CommitRequest("POSTMAN",collection("https://example.test"),environment,null,1L));var reopened=new ApiRepository(jdbc);var vars=reopened.getVars(committed.envId());assertThat(vars.header()).contains(new ApiVariables.Var("credential","NEW_PRIVATE",true));assertThat(vars.base()).containsExactly(new ApiVariables.Var("same","environment",false));
  reopened.saveVars(committed.envId(),vars.base(),List.of(new ApiVariables.Var("credential","UPDATED_PRIVATE",null)),vars.body());assertThat(reopened.getVars(committed.envId()).header()).containsExactly(new ApiVariables.Var("credential","UPDATED_PRIVATE",true));
  var archive=new ApiConfigArchive(reopened,jdbc);String json=ApiRequestEngine.stringify(archive.export(false));assertThat(json).doesNotContain("UPDATED_PRIVATE").contains("\"secret\":true");
 }
 @Test void explicitMappingIsAppliedInPreviewAndCommitAndMissingReferenceGroupsWarn(){
  var mapping=Map.of("same",List.of("HEADER"));var preview=ApiInterchange.preview(new ApiInterchange.Input("POSTMAN",collection("https://example.test"),environment,mapping,null));assertThat(preview.errors()).isEmpty();assertThat(preview.variables().base()).isEmpty();assertThat(preview.variables().body()).isEmpty();assertThat(preview.warnings()).anyMatch(w->w.contains("未覆盖全部引用位置")&&w.contains("same"));
  var committed=imports.commit(new ApiImportService.CommitRequest("POSTMAN",collection("https://example.test"),environment,null,1L,mapping));assertThat(repo.getVars(committed.envId()).base()).isEmpty();assertThat(repo.getVars(committed.envId()).header()).contains(new ApiVariables.Var("same","environment",false));
 }
 @Test void disabledEnvironmentDoesNotShadowEnabledCollectionAndInvalidMappingIsAtomic(){
  String disabled="{\"values\":[{\"key\":\"same\",\"value\":\"ignored\",\"enabled\":false}]}";
  var p=ApiInterchange.preview(new ApiInterchange.Input("POSTMAN",collection("https://example.test"),disabled));assertThat(p.variables().base()).contains(new ApiVariables.Var("same","collection"));
  for(var mapping:List.of(Map.of("same",List.<String>of()),Map.of("same",List.of("UNKNOWN")),Map.of("off",List.of("BASE"))))assertThatThrownBy(()->imports.commit(new ApiImportService.CommitRequest("POSTMAN",collection("https://example.test"),environment,null,1L,mapping))).hasMessageContaining("未提交");
  assertThat(repo.listCases()).isEmpty();assertThat(repo.listCaseGroups()).isEmpty();assertThat(repo.listEnvs()).hasSize(1);
 }
 @Test void allThreeInferredScopesSendTheSameEnvironmentValueOnActualHttp() throws Exception {
  var requestPath=new AtomicReference<String>();var header=new AtomicReference<String>();var body=new AtomicReference<String>();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/",x->{requestPath.set(x.getRequestURI().getPath());header.set(x.getRequestHeaders().getFirst("X-Same"));body.set(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[]out="{}".getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,out.length);x.getResponseBody().write(out);x.close();});server.start();
  try{var committed=imports.commit(new ApiImportService.CommitRequest("POSTMAN",collection("http://127.0.0.1:"+server.getAddress().getPort()),environment,null,1L));var result=new ApiRunner(repo,new CodeDictionary(repo),"").send(repo.getCase(committed.caseIds().getFirst()),committed.envId(),null);assertThat(result.verdict()).isEqualTo("PASS");assertThat(requestPath.get()).isEqualTo("/environment");assertThat(header.get()).isEqualTo("environment");assertThat(body.get()).contains("\"same\":\"environment\"","12345678901234567890123456789012345678");}finally{server.stop(0);}
 }
 @Test void secretsAndMappingSurviveExplicitNativeConfigRoundtrip() throws Exception {
  imports.commit(new ApiImportService.CommitRequest("POSTMAN",collection("https://example.test"),environment,null,1L));var archive=new ApiConfigArchive(repo,jdbc);String content=new ObjectMapper().writeValueAsString(archive.export(true));var imported=archive.commit(content);
  long id=imported.environmentIds().values().stream().filter(v->repo.getVars(v).header().stream().anyMatch(x->x.name().equals("credential"))).findFirst().orElseThrow();assertThat(repo.getVars(id).header()).contains(new ApiVariables.Var("credential","NEW_PRIVATE",true));
 }
}
