package com.sudpanel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ApiSecretPrivacyTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;ApiRepository repo;ApiRunner runner;HttpServer server;
    String endpoint,lastWire;int hits;final ObjectMapper json=new ObjectMapper();
    static final String SECRET="fictional-A&b+中-credential-429";
    @BeforeEach void setup() throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("secrets.db")));
        repo=new ApiRepository(jdbc);runner=new ApiRunner(repo,new CodeDictionary(repo),"");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",x->{lastWire=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);hits++;byte[] body=(lastWire.isEmpty()?"{}":lastWire).getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,body.length);x.getResponseBody().write(body);x.close();});server.start();endpoint="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void close(){server.stop(0);}
    ApiSpec spec(String body,List<ApiExtractor> extractors){return new ApiSpec(null,"fixture","POST",endpoint,"{}",body,null,"200",null,2000,false,true,null,null,new ApiRequestOptions("JSON",List.of(),null,extractors));}
    ApiSecretStore.Summary secret(){return repo.secrets().create(1,new ApiSecretStore.Input("fixture secret","VAULT",SECRET,null,null));}
    void variables(String value){repo.saveVars(1,List.of(),List.of(),List.of(new ApiVariables.Var("ordinary",value,true)));}
    @Test void encryptedReferenceSurvivesReopenAndNeverAppearsInSummaryOrPayload() throws Exception {
        var saved=secret();String sealed=jdbc.queryForObject("SELECT payload_sealed FROM api_secret_reference",String.class);
        assertThat(sealed).doesNotContain(SECRET);assertThat(json.writeValueAsString(saved)).doesNotContain(SECRET);
        repo=new ApiRepository(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("secrets.db"))));
        assertThat(repo.secrets().resolve(1,saved.reference())).isEqualTo(SECRET);
        assertThat(Files.getPosixFilePermissions(directory.resolve(ApiPlanVault.KEY_FILE_NAME))).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
    }
    @Test void scopeDisabledMissingAndLostKeyFailClosed() throws Exception {
        var saved=secret();long other=repo.createEnv("other").id();
        assertThatThrownBy(()->repo.secrets().resolve(other,saved.reference())).hasMessageContaining("不属于当前环境");
        repo.secrets().enabled(1,saved.id(),false);assertThatThrownBy(()->repo.secrets().resolve(1,saved.reference())).hasMessageContaining("停用");repo.secrets().enabled(1,saved.id(),true);
        Files.delete(directory.resolve(ApiPlanVault.KEY_FILE_NAME));
        assertThatThrownBy(()->repo.secrets().resolve(1,saved.reference())).hasMessageContaining("不可读取");
        assertThatThrownBy(this::secret).hasMessageContaining("加密保存失败");assertThat(directory.resolve(ApiPlanVault.KEY_FILE_NAME)).doesNotExist();
    }
    @Test void disabledReferenceSendsNoRequestAndUnmarkedReferenceStaysLiteral() {
        var saved=secret();variables(saved.reference());repo.secrets().enabled(1,saved.id(),false);
        assertThatThrownBy(()->runner.send(spec("{\"ordinary\":\"{{ordinary}}\"}",null),1L,null)).hasMessageContaining("停用");assertThat(hits).isZero();
        var input=new ApiVariables.VarSet(List.of(),List.of(),List.of(new ApiVariables.Var("ordinary",saved.reference(),false)));
        assertThat(repo.secrets().resolve(1,input).body().getFirst().value()).isEqualTo(saved.reference());
    }
    @Test void actualWireRawEvidenceDefaultDisplayAndReportHaveSeparateSemantics() throws Exception {
        variables(secret().reference());var execution=runner.send(spec("{\"ordinary\":\"{{ordinary}}\"}",null),1L,null);
        assertThat(execution.verdict()).isEqualTo("PASS");assertThat(lastWire).contains(SECRET);assertThat(execution.reqBody()).contains(SECRET);assertThat(execution.respBody()).contains(SECRET);
        var metadata=json.readTree(execution.metadata());assertThat(metadata.path("safeDisplay").toString()).doesNotContain(SECRET).contains("***");assertThat(metadata.path("secretMask").toString()).doesNotContain(SECRET);assertThat(ApiPrivacy.inventory(execution,jdbc)).contains(SECRET);
        assertThat(runner.prepareForUi(spec("{\"ordinary\":\"{{ordinary}}\"}",null),1L,false).body()).contains("***").doesNotContain(SECRET);
        assertThat(runner.prepareForUi(spec("{\"ordinary\":\"{{ordinary}}\"}",null),1L,true).body()).contains(SECRET);
        var b=repo.createBatch("fixture", "ONCE",List.of(1L),0,1);jdbc.update("UPDATE api_exec SET batch_id=? WHERE id=?",b.id(),execution.id());
        var history=new ApiHistoryRepository(jdbc,repo);String report=json.writeValueAsString(history.exportJson(b.id(),false));
        assertThat(report).doesNotContain(SECRET,"privateKnownSecrets","secretMask");assertThat(history.exportJunit(b.id(),false)).doesNotContain(SECRET);
        assertThat(json.writeValueAsString(history.exportJson(b.id(),true))).contains(SECRET);
        assertThat(repo.getExec(execution.id()).reqBody()).contains(SECRET);
    }
    @Test void extractorPrivacyFollowsRunLifetimeWithoutMaskingExecutionValue() throws Exception {
        var variables=new ApiRunVariables();var extracted=ApiResponseTools.extract("{\"ordinary\":\""+SECRET+"\"}",Map.of(),List.of(new ApiExtractor("value","JSON","ordinary",true,true)),variables);
        assertThat(extracted.getFirst().secret()).isTrue();assertThat(variables.get("value")).contains(SECRET);
        assertThat(ApiPrivacy.known(null,null,variables)).contains(SECRET);variables.remove("value");assertThat(variables.secretNames()).isEmpty();
        variables.put("value","\"plain\"");variables.secret("value",true);variables.clear();assertThat(variables.secretNames()).isEmpty();
    }
    @Test void duplicateEnvironmentClonesBoundReferencesAndPreservesSecretMarks() {
        var saved=secret();variables(saved.reference());repo.saveSignConfig(1,false,"fixture",saved.reference(),"n","m");
        var copy=new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(s->repo.duplicateEnv(1));
        var variable=repo.getVars(copy.id()).body().getFirst();assertThat(variable.secret()).isTrue();assertThat(variable.value()).isNotEqualTo(saved.reference());
        assertThat(repo.secrets().resolve(copy.id(),variable.value())).isEqualTo(SECRET);assertThat(repo.getSignConfig(copy.id()).appSecret()).isEqualTo(variable.value());
        assertThatThrownBy(()->repo.secrets().resolve(1,variable.value())).hasMessageContaining("不属于");
    }
    @Test void copyFailureRollsBackNewEnvironmentAndReferences() {
        secret();jdbc.execute("CREATE TRIGGER fail_secret_copy BEFORE INSERT ON api_secret_reference WHEN NEW.env_id<>1 BEGIN SELECT RAISE(ABORT,'fixture failure');END");
        assertThatThrownBy(()->new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).execute(s->repo.duplicateEnv(1))).isInstanceOf(RuntimeException.class);
        assertThat(repo.listEnvs()).hasSize(1);assertThat(repo.secrets().list(1)).hasSize(1);
    }
    @Test void keychainReadsOnlyReferencedItemAndFailureNeverFallsBack() {
        var store=new ApiSecretStore(jdbc,(service,account)->{assertThat(service).isEqualTo("fictional-service");assertThat(account).isEqualTo("fictional-account");return SECRET;});
        var saved=store.create(1,new ApiSecretStore.Input("keychain","KEYCHAIN",null,"fictional-service","fictional-account"));assertThat(store.resolve(1,saved.reference())).isEqualTo(SECRET);
        var failed=new ApiSecretStore(jdbc,(s,a)->{throw new IllegalStateException("fictional private system text");});assertThatThrownBy(()->failed.resolve(1,saved.reference())).hasMessageNotContaining("fictional private system text").hasMessageContaining("不会使用空值");
    }
    @Test void encodedAndEscapedSecretsAreMaskedWithoutChangingBusinessNumbers() {
        String encoded=java.net.URLEncoder.encode(SECRET,StandardCharsets.UTF_8);assertThat(ApiPrivacy.replace("?ordinary="+encoded+"&id=12345678901234567890",Set.of(SECRET))).isEqualTo("?ordinary=***&id=12345678901234567890");
        assertThat(ApiPrivacy.replace("fictional\\\"credential",Set.of("fictional\"credential"))).isEqualTo("***");
    }
    @Test void emptyLegacyEvidenceDoesNotPreventFirstKeyCreationButEncryptedMasksDo() throws Exception {
        repo.insertExec(new ApiExec(null,null,null,"legacy",1,1,"GET",endpoint,200,"0",null,"PASS",null,1L,"{}","","","",null,"2026-10-15T00:00:00Z","[]","{}"));
        var saved=secret();assertThat(saved).isNotNull();
        var raw=new ApiExec(null,null,null,"fixture",1,1,"GET",endpoint,200,"0",null,"PASS",null,1L,"{}","","","",null,"2026-10-15T00:00:00Z","[]","{}");repo.insertExec(ApiPrivacy.attach(raw,Set.of(SECRET),jdbc));
        jdbc.update("DELETE FROM api_secret_reference");Files.delete(directory.resolve(ApiPlanVault.KEY_FILE_NAME));assertThatThrownBy(()->ApiPrivacy.attach(raw,Set.of(SECRET),jdbc)).hasMessageContaining("无法加密");assertThat(directory.resolve(ApiPlanVault.KEY_FILE_NAME)).doesNotExist();
    }
    @Test void defaultConfigurationHidesMarkedLiteralsAndKeepsExtractorMarks() throws Exception {
        variables(SECRET);repo.createCase(spec("{\"ordinary\":\""+SECRET+"\"}",List.of(new ApiExtractor("value","JSON","ordinary",true,true))));
        var archive=new com.sudpanel.api.configarchive.ApiConfigArchive(repo,jdbc);
        var safe=archive.export(false);assertThat(json.writeValueAsString(safe)).doesNotContain(SECRET);assertThat(safe.cases().getFirst().options().extractors().getFirst().secret()).isTrue();assertThat(safe.environments().getFirst().vars().body().getFirst().secret()).isTrue();
        assertThat(json.writeValueAsString(archive.export(true))).contains(SECRET);assertThat(repo.listCases().getFirst().body()).contains(SECRET);
    }
    @Test void defaultComparisonMasksValuesAfterComparingOriginals() throws Exception {
        variables(SECRET);var first=runner.send(spec("{\"ordinary\":\"{{ordinary}}\"}",null),1L,null);
        var second=runner.send(spec("{\"ordinary\":\"public fixture\"}",null),1L,null);var history=new ApiHistoryRepository(jdbc,repo);
        var safe=history.compare(first.id(),second.id(),List.of(),false);assertThat(safe.differences()).isNotEmpty();assertThat(json.writeValueAsString(safe)).doesNotContain(SECRET).contains("***");assertThat(json.writeValueAsString(history.compare(first.id(),second.id(),List.of(),true))).contains(SECRET);
    }
    @Test void independentBaselineMasksAlsoProtectMissingKeyReinitialization() throws Exception {
        var raw=new ApiExec(null,null,null,"fixture",1,1,"GET",endpoint,200,"0",null,"PASS",null,1L,"{}","","","",null,"2026-10-15T00:00:00Z","[]","{}");
        var saved=repo.insertExec(ApiPrivacy.attach(raw,Set.of(SECRET),jdbc));var history=new ApiHistoryRepository(jdbc,repo);new ApiBaselineRepository(jdbc,history).create("fixture",saved.id(),List.of());jdbc.update("DELETE FROM api_exec");Files.delete(directory.resolve(ApiPlanVault.KEY_FILE_NAME));assertThatThrownBy(this::secret).hasMessageContaining("加密保存失败");assertThat(directory.resolve(ApiPlanVault.KEY_FILE_NAME)).doesNotExist();
    }

    @Test void unusedDisabledReferencesAndDisabledSignatureDoNotBlockIndependentRequests() {
        var saved=secret();repo.secrets().enabled(1,saved.id(),false);variables(saved.reference());repo.saveSignConfig(1,false,"fixture",saved.reference(),"n","m");
        assertThat(runner.send(spec("{\"public\":true}",null),1L,null).verdict()).isEqualTo("PASS");assertThat(hits).isEqualTo(1);
        assertThatThrownBy(()->runner.send(spec("{\"ordinary\":\"{{ordinary}}\"}",null),1L,null)).hasMessageContaining("停用");assertThat(hits).isEqualTo(1);
    }
    @Test void baseShadowingLiteralsAndSchemaConstantsDoNotConsumeOtherScopes() {
        var saved=secret();repo.secrets().enabled(1,saved.id(),false);
        repo.saveVars(1,List.of(new ApiVariables.Var("ordinary",endpoint,false)),List.of(new ApiVariables.Var("ordinary",saved.reference(),true)),List.of(new ApiVariables.Var("ordinary",saved.reference(),true)));
        var base=spec("{\"ordinary\":\"{{!ordinary}}\"}",null);var request=new ApiSpec(null,"fixture","POST","{{ordinary}}",base.headers(),base.body(),null,"200",null,2000,false,true,null,"[{\"path\":\"$\",\"op\":\"schema\",\"expect\":\"{\\\"type\\\":\\\"object\\\"}\"}]",base.options());
        assertThat(runner.send(request,1L,null).verdict()).isEqualTo("PASS");assertThat(lastWire).contains("{{ordinary}}");
    }

    @Test void spacesLiteralBracesAndRepeatedCookiesRemainPrivate() throws Exception {
        for(String value:List.of("fictional spaced credential","private{{literal}}")){
            variables(value);var execution=runner.send(spec("{\"ordinary\":\"{{ordinary}}\"}",null),1L,null);
            assertThat(execution.verdict()).isEqualTo("PASS");assertThat(lastWire).contains(value);assertThat(json.readTree(execution.metadata()).path("safeDisplay").toString()).doesNotContain(value);
            assertThat(ApiPrivacy.replace("q="+java.net.URLEncoder.encode(value,StandardCharsets.UTF_8).replace("+","%20"),Set.of(value))).isEqualTo("q=***");
        }
        var raw=new ApiExec(null,null,null,"fixture",1,1,"GET",endpoint,200,"0",null,"PASS",null,1L,"{\"a\":\"fictional-cookie-one\",\"b\":\"fictional-cookie-two\"}","Set-Cookie: a=fictional-cookie-one; Path=/\nSet-Cookie: b=fictional-cookie-two; Path=/\n","","",null,"2026-10-15T00:00:00Z","[]","{}");
        var attached=ApiPrivacy.attach(raw,Set.of(),jdbc);assertThat(json.readTree(attached.metadata()).path("safeDisplay").toString()).doesNotContain("fictional-cookie-one","fictional-cookie-two");assertThat(ApiPrivacy.inventory(attached,jdbc)).contains("fictional-cookie-one","fictional-cookie-two");
    }
    @Test void implicitAuthCredentialIsPrivateEvenWithoutVariableSecretMark() throws Exception {
        String credential="fictional-ordinary-auth-429";
        repo.saveVars(1,List.of(),List.of(new ApiVariables.Var("ordinary",credential,false)),List.of(new ApiVariables.Var("ordinary",credential,false)));
        var options=new ApiRequestOptions("JSON",List.of(),new ApiRequestOptions.Auth("API_KEY",null,null,null,"X-Ordinary","{{ordinary}}","HEADER"),null);
        var b=spec("{\"ordinary\":\"{{ordinary}}\"}",null);var request=new ApiSpec(null,"credential fixture","POST",endpoint,"{}",b.body(),null,"200",null,2000,false,true,null,null,options);
        var prepared=runner.prepareForUi(request,1L,false);assertThat(prepared.headers().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("X-Ordinary")).findFirst().orElseThrow().getValue()).isEqualTo("***");assertThat(prepared.body()).doesNotContain(credential);
        var execution=runner.send(request,1L,null);assertThat(execution.verdict()).withFailMessage("%s",execution.error()).isEqualTo("PASS");assertThat(lastWire).contains(credential);assertThat(json.readTree(execution.metadata()).path("safeDisplay").toString()).doesNotContain(credential);repo.createCase(request);
        var archive=new com.sudpanel.api.configarchive.ApiConfigArchive(repo,jdbc);assertThat(json.writeValueAsString(archive.export(false))).doesNotContain(credential);
    }
    @Test void defaultConfigurationKeepsSessionPoliciesAndRemainsImportable() throws Exception {
        var transport=new ApiRequestOptions.Transport("NORMAL","DIRECT",null,null,"CHAIN",8192,3,"HTTP_2","00000000-0000-0000-0000-000000000003","SHARED",null);
        var options=new ApiRequestOptions("JSON",List.of(),null,List.of(new ApiExtractor("value","JSON","ordinary",true,true)),List.of(),transport,true);
        var b=spec("{\"secret\":\"fictional-unmarked-password\"}",null);repo.createCase(new ApiSpec(null,"fixture","POST",endpoint,"{}",b.body(),null,"200",null,2000,false,true,null,null,options));
        var archive=new com.sudpanel.api.configarchive.ApiConfigArchive(repo,jdbc);String text=json.writeValueAsString(archive.export(false));assertThat(text).doesNotContain("fictional-unmarked-password");assertThat(archive.preview(text).valid()).isTrue();
        assertThat(archive.export(false).cases().getFirst().options().transport().cookieDataRows()).isEqualTo("SHARED");assertThat(archive.export(false).cases().getFirst().options().extractors().getFirst().secret()).isTrue();
    }

}
