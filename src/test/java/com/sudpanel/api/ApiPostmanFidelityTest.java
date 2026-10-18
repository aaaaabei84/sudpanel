package com.sudpanel.api;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudpanel.api.configarchive.ApiConfigArchive;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
class ApiPostmanFidelityTest {
 @TempDir Path dir;
 JdbcTemplate jdbc;ApiRepository repo;ApiImportService service;
 final String prefix="{\"info\":{\"name\":\"source\",\"schema\":\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"},";
 @BeforeEach void setup(){jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("state.db")));repo=new ApiRepository(jdbc);service=new ApiImportService(repo,jdbc);}
 String scripted(){return prefix+"\"event\":[{\"listen\":\"prerequest\",\"script\":{\"exec\":[\"const secret='FICTIONAL_SCRIPT_SECRET';\",\"pm.environment.set('token',secret);\"]}}],\"item\":[{\"name\":\"a / b\",\"event\":[{\"listen\":\"test\",\"disabled\":true,\"script\":{\"exec\":\"throw new Error('folder-test');\"}}],\"item\":[{\"name\":\"request\",\"event\":[{\"listen\":\"test\",\"script\":{\"exec\":[\"pm.test('item-test',()=>{});\"]}}],\"request\":{\"url\":\"https://example.test\",\"method\":\"POST\",\"body\":{\"mode\":\"raw\",\"raw\":\"{\\\"id\\\":12345678901234567890123456789012345678}\"}}}]},{\"name\":\"empty\",\"item\":[]}]}";}
 @Test void scriptsAtAllLevelsArePreservedAsInertNotesAndLossesAreSpecific(){
  var p=ApiInterchange.preview(new ApiInterchange.Input("POSTMAN",scripted(),null));assertThat(p.errors()).isEmpty();assertThat(p.folders()).hasSize(3);assertThat(p.losses()).filteredOn(x->x.code().equals("SCRIPT_NOT_EXECUTED")).hasSize(3);
  var s=p.requests().getFirst().spec();assertThat(s.asserts()).isNull();assertThat(s.note()).contains("FICTIONAL_SCRIPT_SECRET","folder-test","item-test","\"disabled\":true","仅保存，未执行");assertThat(s.body()).contains("12345678901234567890123456789012345678");
  assertThat(p.warnings()).noneMatch(x->x.contains("FICTIONAL_SCRIPT_SECRET"));assertThat(p.losses()).allSatisfy(x->assertThat(x.message()).doesNotContain("FICTIONAL_SCRIPT_SECRET"));
 }
 @Test void sameDisplayNamesAndSlashNamesKeepDistinctDirectoryIdentity(){
  String source=prefix+"\"item\":[{\"name\":\"same\",\"item\":[{\"name\":\"one\",\"request\":\"https://example.test/1\"}]},{\"name\":\"same\",\"item\":[{\"name\":\"two\",\"request\":\"https://example.test/2\"}]},{\"name\":\"a / b\",\"item\":[]},{\"name\":\"a\",\"item\":[{\"name\":\"b\",\"item\":[]}]}]}";
  var p=ApiInterchange.preview(new ApiInterchange.Input("POSTMAN",source,null));assertThat(p.errors()).isEmpty();assertThat(p.requests().get(0).folder()).isEqualTo(p.requests().get(1).folder());assertThat(p.requests().get(0).folderKey()).isNotEqualTo(p.requests().get(1).folderKey());
  var committed=service.commit(new ApiImportService.CommitRequest("POSTMAN",source,null,null,1L));assertThat(committed.groupIds()).hasSize(6);assertThat(repo.getCase(committed.caseIds().get(0)).groupId()).isNotEqualTo(repo.getCase(committed.caseIds().get(1)).groupId());
  assertThat(repo.listCaseGroups().stream().map(ApiCaseGroup::importPath).toList()).contains(List.of("source","a / b"),List.of("source","a","b"));
 }
 @Test void emptyFoldersAndInheritedScriptsPersistAfterRepositoryReopen(){
  var result=service.commit(new ApiImportService.CommitRequest("POSTMAN",scripted(),null,null,1L));var reopened=new ApiRepository(jdbc);assertThat(result.groupIds()).hasSize(3);
  var empty=reopened.listCaseGroups().stream().filter(g->g.importPath().equals(List.of("source","empty"))).findFirst().orElseThrow();assertThat(empty.caseCount()).isZero();assertThat(empty.importNote()).contains("FICTIONAL_SCRIPT_SECRET");
  assertThat(reopened.getCase(result.caseIds().getFirst()).note()).contains("folder-test","item-test");
 }
 @Test void invalidRequestLocatesJsonPointerAndCommitCreatesNothing(){
  String content=prefix+"\"item\":[{\"name\":\"good\",\"request\":\"https://example.test\"},{\"name\":\"bad\",\"request\":{\"url\":\"https://example.test\",\"body\":{\"mode\":\"file\",\"file\":{\"src\":\"/never/read\"}}}}]}";
  var p=ApiInterchange.preview(new ApiInterchange.Input("POSTMAN",content,null));assertThat(p.errors()).anyMatch(x->x.contains("root/item/1"));assertThat(p.losses()).anyMatch(x->x.blocking()&&x.code().equals("REQUEST_UNSUPPORTED"));
  assertThatThrownBy(()->service.commit(new ApiImportService.CommitRequest("POSTMAN",content,null,null,1L))).hasMessageContaining("未提交");assertThat(repo.listCaseGroups()).isEmpty();assertThat(repo.listCases()).isEmpty();
 }
 @Test void nativeConfigDefaultOmitsImportedScriptLiteralAndPrivateRoundtripKeepsIt() throws Exception {
  service.commit(new ApiImportService.CommitRequest("POSTMAN",scripted(),null,null,1L));var archive=new ApiConfigArchive(repo,jdbc);var json=new ObjectMapper();
  assertThat(json.writeValueAsString(archive.export(false))).doesNotContain("FICTIONAL_SCRIPT_SECRET","folder-test","item-test");
  String privateBundle=json.writeValueAsString(archive.export(true));assertThat(privateBundle).contains("FICTIONAL_SCRIPT_SECRET");var result=archive.commit(privateBundle);
  assertThat(repo.getCase(result.caseIds().values().iterator().next()).note()).contains("item-test");assertThat(repo.getCaseGroup(result.groupIds().values().iterator().next()).importNote()).contains("FICTIONAL_SCRIPT_SECRET");
 }
 @Test void inheritedNotesHaveCumulativeBoundWithoutTruncatingScripts(){
  String script="x".repeat(1000000);String input=prefix+"\"event\":[{\"script\":{\"exec\":\""+script+"\"}}],\"item\":["+String.join(",",Collections.nCopies(10,"{\"name\":\"x\",\"request\":\"https://example.test\"}"))+"]}";
  var p=ApiInterchange.preview(new ApiInterchange.Input("POSTMAN",input,null));assertThat(p.errors()).anyMatch(x->x.contains("备注累计"));assertThat(p.requests()).allSatisfy(x->assertThat(x.spec().note()).contains(script));
 }
 @Test void officialCollectionInfoDescriptionIsPreservedWithCollectionEvents(){
  String input=prefix.replace("\"name\":\"source\"","\"name\":\"source\",\"description\":{\"content\":\"collection description fixture\",\"type\":\"text/plain\"}")+"\"event\":[{\"listen\":\"prerequest\",\"script\":{\"exec\":[\"throw new Error('INERT_ONLY');\"]}}],\"item\":[{\"name\":\"request\",\"request\":\"https://example.test\"}]}";
  var preview=ApiInterchange.preview(new ApiInterchange.Input("POSTMAN",input,null));
  assertThat(preview.errors()).isEmpty();assertThat(preview.folders().getFirst().note()).contains("collection description fixture","INERT_ONLY");
  var committed=service.commit(new ApiImportService.CommitRequest("POSTMAN",input,null,null,1L));
  assertThat(repo.getCase(committed.caseIds().getFirst()).note()).contains("collection description fixture","INERT_ONLY");
 }

}
