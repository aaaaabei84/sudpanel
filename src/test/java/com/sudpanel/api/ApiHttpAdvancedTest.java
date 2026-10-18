package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/** Real loopback HTTP + private SQLite only; no production configuration, network or ADB. */
@Timeout(30)
class ApiHttpAdvancedTest {
    @TempDir Path temporary;
    JdbcTemplate jdbc;ApiRepository repo;ApiRunner runner;ApiHttpProfiles profiles;HttpServer server,other;
    String endpoint,otherEndpoint;final List<Wire> wires=new CopyOnWriteArrayList<>();
    final AtomicInteger tokens=new AtomicInteger(),dropped=new AtomicInteger();
    volatile String digestAlgorithm="SHA-256",digestQop="auth",tokenGrant;volatile boolean rejectAuth;
    final ObjectMapper json=new ObjectMapper();
    record Wire(String path,String method,String body,Map<String,List<String>> headers) {
        String first(String name){return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).map(e->e.getValue().getFirst()).findFirst().orElse(null);}
        List<String> values(String name){return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).flatMap(e->e.getValue().stream()).toList();}
    }
    @BeforeEach void setup()throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+temporary.resolve("test.db")));repo=new ApiRepository(jdbc);profiles=new ApiHttpProfiles(jdbc,repo);runner=new ApiRunner(repo,new CodeDictionary(repo),"");runner.setAdvancedProfiles(profiles);
        other=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);other.createContext("/",this::handle);other.setExecutor(Executors.newVirtualThreadPerTaskExecutor());other.start();otherEndpoint="http://127.0.0.1:"+other.getAddress().getPort();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",this::handle);server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());server.start();endpoint="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void close(){if(server!=null)server.stop(0);if(other!=null)other.stop(0);}
    void handle(HttpExchange x)throws java.io.IOException {
        var wire=new Wire(x.getRequestURI().getPath(),x.getRequestMethod(),new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),new LinkedHashMap<>(x.getRequestHeaders()));wires.add(wire);
        String path=wire.path(),body="ok";if(path.equals("/digest-final"))body=wire.first("Authorization");int status=200;
        if(path.equals("/cookie"))x.getResponseHeaders().add("Set-Cookie","session=fixture-cookie; Path=/; HttpOnly");
        if(path.equals("/cookie-rules")){x.getResponseHeaders().add("Set-Cookie","pathonly=private; Path=/private");x.getResponseHeaders().add("Set-Cookie","secureonly=private; Path=/; Secure");x.getResponseHeaders().add("Set-Cookie","expired=private; Path=/; Max-Age=0");}
        if(path.equals("/peek")||path.equals("/private/peek"))body=Objects.toString(wire.first("Cookie"),"none");
        if(path.equals("/row")){body=Objects.toString(wire.first("Cookie"),"none");x.getResponseHeaders().add("Set-Cookie","row=fixture-row; Path=/");}
        if(path.equals("/redirect")){status=302;x.getResponseHeaders().set("Location","/peek");}
        if(path.equals("/loop")){status=307;x.getResponseHeaders().set("Location","/loop");}
        if(path.equals("/cross")){status=302;x.getResponseHeaders().set("Location",otherEndpoint+"/target");}
        if(path.equals("/cross-write")){status=307;x.getResponseHeaders().set("Location",otherEndpoint+"/target");}
        if(path.equals("/drop")){dropped.incrementAndGet();x.close();return;}
        if(path.equals("/token")){int count=tokens.incrementAndGet();tokenGrant=wire.body();body="{\"access_token\":\"fixture-token-"+count+"\",\"token_type\":\"Bearer\",\"expires_in\":1,\"refresh_token\":\"rotated-fixture\"}";}
        if(path.equals("/slow-token")){tokens.incrementAndGet();x.sendResponseHeaders(200,0);x.getResponseBody().write('{');x.getResponseBody().flush();try{Thread.sleep(1000);}catch(InterruptedException ignored){}x.close();return;}
        if(path.equals("/resource")){body=Objects.toString(wire.first("Authorization"),"none");if(rejectAuth)status=401;}
        if(path.equals("/digest")){
            String authorization=wire.first("Authorization");
            if(authorization==null||rejectAuth){status=401;String qop=digestQop.equals("none")?"":", qop=\""+digestQop+"\"";x.getResponseHeaders().set("WWW-Authenticate","Digest realm=\"fixture\", nonce=\"fixture-nonce\", algorithm="+digestAlgorithm+qop+", opaque=\"opaque-fixture\"");}
            else if("redirect=1".equals(x.getRequestURI().getRawQuery())){status=302;x.getResponseHeaders().set("Location","/digest-final");}else body=authorization;
        }
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,bytes.length);try{x.getResponseBody().write(bytes);}finally{x.close();}
    }
    ApiRequestOptions.Transport transport(String redirect,Integer hops,String protocol,String cookie,String session,String rows,String cert){return new ApiRequestOptions.Transport(redirect,"DIRECT",null,null,cookie,null,hops,protocol,session,rows,cert);}
    ApiRequestOptions options(ApiRequestOptions.Auth auth,ApiRequestOptions.Transport transport,List<ApiRequestOptions.HeaderRow> rows){return new ApiRequestOptions("RAW",null,auth,null,null,transport,true,rows);}
    ApiSpec spec(String path,String method,String body,ApiRequestOptions options){return new ApiSpec(null,path,method,endpoint+path,"{}",body,null,"","",2000,false,true,null,null,options);}
    ApiRequestOptions.Auth auth(String type,String profile){return new ApiRequestOptions.Auth(type,null,null,null,null,null,null,profile,null,null);}
    String create(String kind,String grant,String tokenUrl,String algorithm,String qop,long env){return (String)profiles.create(env,new ApiHttpProfiles.Input(kind,"fixture","127.0.0.1",server.getAddress().getPort(),null,null,"fixture-password",tokenUrl,grant,"fixture+client","fixture+secret","initial-fixture","read write","fixture/audience","fixture-user",algorithm,qop)).get("id");}
    @Test void orderedRowsPreserveMultipleValuesAndDisabledRowsAndCurlWithoutLegacyJsonLoss() {
        var rows=List.of(new ApiRequestOptions.HeaderRow("X-Multi","one",true),new ApiRequestOptions.HeaderRow("X-Skip","hidden",false),new ApiRequestOptions.HeaderRow("X-Multi","two",true));
        var request=spec("/headers","POST","payload",options(null,null,rows));request=new ApiSpec(null,request.name(),request.method(),request.url(),"{broken old JSON {{run.ignored}}",request.body(),null,"","",2000,false,true,null,null,request.options());
        var preview=runner.prepare(request,1L);assertThat(preview.valid()).isTrue();assertThat(preview.diagnostics()).isEmpty();assertThat(preview.headerRows()).extracting(ApiRequestOptions.HeaderRow::value).containsSubsequence("one","two");
        var sent=runner.send(request,1L,null);assertThat(sent.verdict()).isEqualTo("PASS");assertThat(wires.getFirst().values("X-Multi")).containsExactly("one","two");assertThat(wires.getFirst().first("X-Skip")).isNull();
        assertThat(sent.reqHeaders()).containsSubsequence("X-Multi: one","X-Multi: two");
        var curl=ApiActualCurl.history(sent,true).curl();assertThat(curl).containsSubsequence("X-Multi: one","X-Multi: two");
        assertThat(repo.createCase(request).options().headerRows()).isEqualTo(rows);
    }
    @Test void orderedHeaderSignatureUsesFinalBodyAndAllDuplicateValuesReceiveSameFinalSignature()throws Exception {
        repo.saveSignConfig(1,true,"fixture-app","fixture-secret","stable-nonce","fixture-game");repo.saveVars(1,List.of(),List.of(),List.of(new ApiVariables.Var("value","resolved")));
        var rows=List.of(new ApiRequestOptions.HeaderRow("X-Sign","{{$auth.sign}}",true),new ApiRequestOptions.HeaderRow("X-Sign","{{sign}}",true),new ApiRequestOptions.HeaderRow("Authorization","old",true));
        var request=spec("/signed","POST","{\"value\":\"{{value}}\",\"time\":{{times}}}",options(auth("SUD",null),null,rows));var sent=runner.send(request,1L,null);assertThat(sent.verdict()).as(sent.reason()).isEqualTo("PASS");var wire=wires.getFirst();var timestamp=Pattern.compile("timestamp=\"([0-9]+)\"").matcher(wire.first("Authorization"));assertThat(timestamp.find()).isTrue();var mac=javax.crypto.Mac.getInstance("HmacSHA1");mac.init(new javax.crypto.spec.SecretKeySpec("fixture-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA1"));String expected=HexFormat.of().formatHex(mac.doFinal(("fixture-app\n"+timestamp.group(1)+"\nstable-nonce\n"+wire.body()+"\n").getBytes(StandardCharsets.UTF_8)));assertThat(wire.values("X-Sign")).containsExactly(expected,expected);assertThat(wire.first("Authorization")).contains("signature=\""+expected+"\"");assertThat(wire.body()).contains("resolved").doesNotContain("{{");
    }
    @Test void restrictedHeadersFailSaveAndPreviewAndCannotHideInDisabledRows() {
        for(String name:List.of("Host","content-LENGTH","Connection","Expect","Upgrade","Transfer-Encoding","Trailer")){
            var rows=List.of(new ApiRequestOptions.HeaderRow(name,"bad",false));var request=spec("/never","GET",null,options(null,null,rows));
            assertThat(runner.prepare(request,1L).valid()).isFalse();assertThatThrownBy(()->repo.createCase(request)).isInstanceOf(IllegalArgumentException.class);
            var raw=new ApiSpec(null,"raw","GET",endpoint,"{\""+name+"\":\"bad\"}",null,null,"","",2000,false,true,null,null);
            assertThatThrownBy(()->repo.createCase(raw)).isInstanceOf(IllegalArgumentException.class);
        }
        var duplicate=spec("/never","GET",null,options(null,null,List.of(new ApiRequestOptions.HeaderRow("Cookie","a",true),new ApiRequestOptions.HeaderRow("cookie","b",true))));
        assertThat(runner.prepare(duplicate,1L).valid()).isFalse();assertThat(wires).isEmpty();
    }
    @Test void redirectsHaveExplicitHopBoundsMethodTransitionsAndCrossOriginAuthProtection() {
        var follow=runner.send(spec("/redirect","POST","body",options(null,transport("NORMAL",2,"HTTP_1_1","OFF",null,null,null),null)),1L,null);
        assertThat(follow.verdict()).isEqualTo("PASS");assertThat(wires.getLast().method()).isEqualTo("GET");assertThat(wires.getLast().body()).isEmpty();assertThat(follow.metadata()).contains("nextMethod","302","/peek");
        int before=wires.size();var loop=runner.send(spec("/loop","GET",null,options(null,transport("ALWAYS",2,null,"OFF",null,null,null),null)),1L,null);
        assertThat(loop.verdict()).isEqualTo("ERROR");assertThat(loop.reason()).contains("最大跳数 2");assertThat(wires.size()-before).isEqualTo(3);
        var bearer=new ApiRequestOptions.Auth("BEARER","fixture-bearer",null,null,null,null,null);
        runner.send(spec("/cross","GET",null,options(bearer,null,List.of(new ApiRequestOptions.HeaderRow("Cookie","manual=private",true)))),1L,null);
        assertThat(wires.getLast().path()).isEqualTo("/target");assertThat(wires.getLast().first("Authorization")).isNull();assertThat(wires.getLast().first("Cookie")).isNull();
        runner.send(spec("/cookie","GET",null,options(null,transport(null,null,null,"ENV",null,null,null),null)),1L,null);runner.send(spec("/cross","GET",null,options(null,transport(null,null,null,"ENV",null,null,null),null)),1L,null);assertThat(wires.getLast().first("Cookie")).isNull();
        var blocked=runner.send(spec("/cross-write","POST","private body",options(null,null,null)),1L,null);assertThat(blocked.httpStatus()).isEqualTo(307);assertThat(blocked.metadata()).contains("blockedRedirect");assertThat(wires.getLast().path()).isEqualTo("/cross-write");
        var key=new ApiRequestOptions.Auth("API_KEY",null,null,null,"fixture-key","private","QUERY");blocked=runner.send(spec("/cross","GET",null,options(key,null,null)),1L,null);assertThat(blocked.metadata()).contains("blockedRedirect");assertThat(wires.getLast().path()).isEqualTo("/cross");
    }
    @Test void http2PreferenceFallsBackWithActualVersionAndWriteNetworkFailureIsNotRetried() {
        var sent=runner.send(spec("/protocol","GET",null,options(null,transport(null,null,"HTTP_2","OFF",null,null,null),null)),1L,null);
        assertThat(sent.verdict()).isEqualTo("PASS");assertThat(sent.metadata()).contains("requestedHttpVersion\":\"HTTP_2","actualHttpVersion\":\"HTTP_1_1");
        sent=runner.send(spec("/drop","POST","write-once",options(null,null,null)),1L,null);assertThat(sent.verdict()).isEqualTo("ERROR");assertThat(dropped).hasValue(1);
    }
    @Test void chainCookieSessionsAndEnvironmentAreIsolatedMaskedAndIndividuallyRemovable() {
        String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();var enabled=options(null,transport(null,null,null,"CHAIN",a,null,null),null);
        runner.send(spec("/cookie","GET",null,enabled),1L,null);assertThat(runner.send(spec("/peek","GET",null,enabled),1L,null).respBody()).contains("fixture-cookie");
        assertThat(runner.send(spec("/peek","GET",null,options(null,transport(null,null,null,"CHAIN",b,null,null),null)),1L,null).respBody()).isEqualTo("none");
        long env=repo.createEnv("second").id();assertThat(runner.send(spec("/peek","GET",null,enabled),env,null).respBody()).isEqualTo("none");assertThat(runner.cookies(1L,a)).hasSize(1);assertThat(runner.cookies(1L,a).toString()).doesNotContain("fixture-cookie");
        String id=(String)runner.cookies(1L,a).getFirst().get("id");runner.deleteCookie(1L,a,id);assertThat(runner.send(spec("/peek","GET",null,enabled),1L,null).respBody()).isEqualTo("none");
        runner.send(spec("/cookie-rules","GET",null,enabled),1L,null);assertThat(runner.send(spec("/peek","GET",null,enabled),1L,null).respBody()).isEqualTo("none");assertThat(runner.send(spec("/private/peek","GET",null,enabled),1L,null).respBody()).contains("pathonly=private").doesNotContain("secureonly","expired");
        var restarted=new ApiRunner(repo,new CodeDictionary(repo),"");assertThat(restarted.send(spec("/private/peek","GET",null,enabled),1L,null).respBody()).isEqualTo("none");
    }
    @Test void dataRowsChooseIsolatedOrSharedCookieAndBatchAlwaysClearsSession()throws Exception {
        for(String mode:List.of("ISOLATED","SHARED")){
            var row=repo.createCase(spec("/row","GET",null,options(null,transport(null,null,null,"CHAIN",null,mode,null),null)));
            var batch=runner.startDataBatch(mode,List.of(row.id()),null,new ApiDataSet.Input("JSON","[{\"row\":1},{\"row\":2}]"),0,1);
            long until=System.nanoTime()+Duration.ofSeconds(5).toNanos();while(runner.isRunning(batch.id())&&System.nanoTime()<until)Thread.sleep(10);assertThat(runner.isRunning(batch.id())).isFalse();var result=repo.listExecsByBatch(batch.id());assertThat(result).hasSize(2);
            assertThat(result.getFirst().respBody()).isEqualTo("none");if(mode.equals("SHARED"))assertThat(result.getLast().respBody()).contains("fixture-row");else assertThat(result.getLast().respBody()).isEqualTo("none");
            assertThat(runner.cookies(1L,"api-batch-"+batch.id())).isEmpty();assertThat(runner.cookies(1L,"api-batch-"+batch.id()+"-row-1-1")).isEmpty();
        }
    }
    @Test void oauthCacheExpiresRefreshRotatesAnd401DoesNotResendResourceWrite()throws Exception {
        String id=create("OAUTH2","refresh_token",endpoint+"/token",null,null,1);var options=options(auth("OAUTH2",id),null,null);var request=spec("/resource","POST","one write",options);
        assertThat(runner.prepare(request,1L).valid()).isTrue();assertThat(tokens).hasValue(0);
        assertThat(runner.send(request,1L,null).respBody()).isEqualTo("Bearer fixture-token-1");assertThat(tokenGrant).contains("grant_type=refresh_token","refresh_token=initial-fixture","client_id=fixture%2Bclient","client_secret=fixture%2Bsecret","scope=read+write","audience=fixture%2Faudience");
        runner.send(request,1L,null);assertThat(tokens).hasValue(1);Thread.sleep(1100);assertThat(runner.send(request,1L,null).respBody()).isEqualTo("Bearer fixture-token-2");assertThat(tokenGrant).contains("refresh_token=rotated-fixture");
        rejectAuth=true;int previous=wires.size();var fail=runner.send(request,1L,null);assertThat(fail.verdict()).isEqualTo("FAIL");assertThat(wires.size()-previous).isEqualTo(1);rejectAuth=false;runner.send(request,1L,null);assertThat(tokens).hasValue(3);
        long second=repo.createEnv("different").id();assertThat(runner.send(request,second,null).verdict()).isEqualTo("ERROR");assertThat(tokens).hasValue(3);
        String sealed=jdbc.queryForObject("SELECT config_sealed FROM api_http_profile WHERE id=?",String.class,id);assertThat(sealed).doesNotContain("fixture-password","fixture+secret","initial-fixture");assertThat(profiles.list(1).toString()).doesNotContain("fixture-password","fixture+secret","initial-fixture","fixture-token");
        var restarted=new ApiHttpProfiles(jdbc,repo);assertThat(restarted.list(1)).hasSize(1);assertThat(restarted.oauth(1,id,URI.create(request.url()),System.nanoTime()+Duration.ofSeconds(2).toNanos())).isEqualTo("fixture-token-4");assertThat(tokenGrant).contains("refresh_token=rotated-fixture");
        Files.delete(temporary.resolve(ApiPlanVault.KEY_FILE_NAME));assertThatThrownBy(()->new ApiHttpProfiles(jdbc,repo).auth(1,id,"OAUTH2",URI.create(request.url()))).isInstanceOf(IllegalStateException.class);assertThat(Files.exists(temporary.resolve(ApiPlanVault.KEY_FILE_NAME))).isFalse();
    }
    @Test void oauthClientSecretBasicEncodesCredentialsIndependently() {
        String id=(String)profiles.create(1,new ApiHttpProfiles.Input("OAUTH2","basic","127.0.0.1",server.getAddress().getPort(),null,null,null,endpoint+"/token","client_credentials","client+id","secret:with space",null,null,null,null,null,null,"BASIC")).get("id");
        var sent=runner.send(spec("/resource","GET",null,options(auth("OAUTH2",id),null,null)),1L,null);assertThat(sent.verdict()).isEqualTo("PASS");var tokenWire=wires.stream().filter(w->w.path().equals("/token")).findFirst().orElseThrow();assertThat(tokenWire.body()).isEqualTo("grant_type=client_credentials");assertThat(tokenWire.first("Authorization")).isEqualTo("Basic "+Base64.getEncoder().encodeToString("client%2Bid:secret%3Awith+space".getBytes(StandardCharsets.UTF_8)));
    }
    @Test void oauthClientCredentialsTokenFailureHasWholeBodyDeadlineAndNoBusinessWrite() {
        String id=create("OAUTH2","client_credentials",endpoint+"/slow-token",null,null,1);var request=spec("/resource","POST","must-not-send",options(auth("OAUTH2",id),null,null));
        request=new ApiSpec(null,request.name(),request.method(),request.url(),"{}",request.body(),null,"","",150,false,true,null,null,request.options());long start=System.nanoTime();var result=runner.send(request,1L,null);
        assertThat(result.verdict()).isEqualTo("ERROR");assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofMillis(900));assertThat(wires).extracting(Wire::path).doesNotContain("/resource");
    }
    @Test void cancellingDuringTokenBodyStopsPromptlyAndNeverSendsBusinessRequest()throws Exception {
        String id=create("OAUTH2","client_credentials",endpoint+"/slow-token",null,null,1);String requestId=UUID.randomUUID().toString();var request=spec("/resource","POST","must-not-send",options(auth("OAUTH2",id),null,null));
        runner.submitRequest(request,1L,null,requestId);long deadline=System.nanoTime()+Duration.ofSeconds(2).toNanos();while(tokens.get()==0&&System.nanoTime()<deadline)Thread.sleep(5);assertThat(tokens).hasValue(1);long start=System.nanoTime();runner.cancelRequest(requestId);while(runner.request(requestId).finishedAt()==null&&System.nanoTime()<deadline)Thread.sleep(5);assertThat(runner.request(requestId).state()).isEqualTo("CANCELLED");assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofMillis(500));assertThat(wires).extracting(Wire::path).doesNotContain("/resource");assertThat(runner.request(requestId).serverOutcomeUnknown()).isFalse();
    }
    @Test void digestUsesIndependentRfcHashOracleForMd5AndSha256AndRetriesOnlyOneChallenge()throws Exception {
        for(String algorithm:List.of("MD5","SHA-256")){
            digestAlgorithm=algorithm;String id=create("DIGEST",null,null,algorithm,"auth",1);int before=wires.size();var result=runner.send(spec("/digest","POST","body",options(auth("DIGEST",id),null,null)),1L,null);
            assertThat(result.verdict()).isEqualTo("PASS");assertThat(wires.size()-before).isEqualTo(2);Map<String,String> header=parseDigest(wires.getLast().first("Authorization"));
            String ha1=hash(algorithm,"fixture-user:fixture:fixture-password"),ha2=hash(algorithm,"POST:/digest");String expected=hash(algorithm,ha1+":fixture-nonce:00000001:"+header.get("cnonce")+":auth:"+ha2);assertThat(header.get("response")).isEqualTo(expected);assertThat(header).containsEntry("opaque","opaque-fixture");
            rejectAuth=true;before=wires.size();assertThat(runner.send(spec("/digest","GET",null,options(auth("DIGEST",id),null,null)),1L,null).verdict()).isEqualTo("FAIL");assertThat(wires.size()-before).isEqualTo(2);rejectAuth=false;
        }
        digestAlgorithm="SHA-256";digestQop="auth-int";String id=create("DIGEST",null,null,"SHA-256","auth",1);int before=wires.size();assertThat(runner.send(spec("/digest","GET",null,options(auth("DIGEST",id),null,null)),1L,null).verdict()).isEqualTo("ERROR");assertThat(wires.size()-before).isEqualTo(1);
    }
    @Test void digestRedirectRecomputesFinalUriAndExplicitNoQopWorks()throws Exception {
        String id=create("DIGEST",null,null,"SHA-256","auth",1);var result=runner.send(spec("/digest?redirect=1","GET",null,options(auth("DIGEST",id),null,null)),1L,null);
        assertThat(result.verdict()).as(result.reason()).isEqualTo("PASS");assertThat(wires).hasSize(3);var header=parseDigest(wires.getLast().first("Authorization"));assertThat(header.get("uri")).isEqualTo("/digest-final");
        String ha1=hash("SHA-256","fixture-user:fixture:fixture-password"),ha2=hash("SHA-256","GET:/digest-final");assertThat(header.get("response")).isEqualTo(hash("SHA-256",ha1+":fixture-nonce:00000001:"+header.get("cnonce")+":auth:"+ha2));
        digestAlgorithm="MD5";digestQop="none";id=create("DIGEST",null,null,"MD5","none",1);result=runner.send(spec("/digest","GET",null,options(auth("DIGEST",id),null,null)),1L,null);assertThat(result.verdict()).isEqualTo("PASS");header=parseDigest(wires.getLast().first("Authorization"));assertThat(header).doesNotContainKeys("qop","nc","cnonce");assertThat(header.get("response")).isEqualTo(hash("MD5",hash("MD5","fixture-user:fixture:fixture-password")+":fixture-nonce:"+hash("MD5","GET:/digest")));
    }
    static Map<String,String> parseDigest(String h){var out=new HashMap<String,String>();var m=Pattern.compile("([a-zA-Z_]+)=(?:\"([^\"]*)\"|([^, ]+))").matcher(h);while(m.find())out.put(m.group(1),m.group(2)!=null?m.group(2):m.group(3));return out;}
    static String hash(String algorithm,String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.ISO_8859_1)));}
}
