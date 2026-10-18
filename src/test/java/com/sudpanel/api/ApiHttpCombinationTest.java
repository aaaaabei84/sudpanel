package com.sudpanel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Independent wire observations for merged features; no formal service, device or remote endpoint. */
class ApiHttpCombinationTest {
    @TempDir Path directory;ApiRepository repo;ApiRunner runner;ApiFileStore files;ApiHttpProfiles profiles;HttpServer server;String endpoint;
    record Wire(String path,String method,byte[] body,Map<String,List<String>> headers) {String header(String name){return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).map(e->e.getValue().getFirst()).findFirst().orElse(null);}}
    List<Wire> wires=new java.util.concurrent.CopyOnWriteArrayList<>();
    byte[] payload={0,1,2,13,10,127,(byte)128,(byte)255};
    @BeforeEach void setup() throws Exception {
        Path db=directory.resolve("combination.db");var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+db));repo=new ApiRepository(jdbc);runner=new ApiRunner(repo,new CodeDictionary(repo),"");files=new ApiFileStore(jdbc,db);runner.fileStore(files);profiles=new ApiHttpProfiles(jdbc,repo);runner.setAdvancedProfiles(profiles);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",x->{var wire=new Wire(x.getRequestURI().toString(),x.getRequestMethod(),x.getRequestBody().readAllBytes(),new LinkedHashMap<>(x.getRequestHeaders()));wires.add(wire);
            String response="{\"ok\":true}";int status=200;
            if(wire.path().equals("/token"))response="{\"access_token\":\"fictional-combination-token\",\"expires_in\":60}";
            else if(wire.path().equals("/digest") && wire.header("Authorization")==null){status=401;x.getResponseHeaders().set("WWW-Authenticate","Digest realm=\"fixture\", nonce=\"fixed-fixture-nonce\", algorithm=SHA-256, qop=\"auth\"");}
            else if(wire.path().equals("/redirect")){status=307;x.getResponseHeaders().set("Location","/upload");}
            else if(wire.path().equals("/seeother")){status=303;x.getResponseHeaders().set("Location","/upload");}
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);x.close();});server.start();endpoint="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void close(){server.stop(0);}
    ApiFileStore.FileInfo upload(){return files.save("UPLOAD","fixture.bin","application/octet-stream",payload);}
    ApiRequestOptions.Auth auth(String type,String id){return new ApiRequestOptions.Auth(type,null,null,null,null,null,null,id,null,null);}
    ApiRequestOptions options(String mode,ApiFileStore.FileInfo file,ApiRequestOptions.Auth auth,List<ApiRequestOptions.HeaderRow> rows){return new ApiRequestOptions(mode,List.of(),auth,List.of(),List.of(),new ApiRequestOptions.Transport("NORMAL","DIRECT",null,null,"OFF",8192),true,new ApiRequestOptions.FileBody(file.fileId(),file.filename(),file.contentType()),mode.equals("MULTIPART")?List.of(new ApiRequestOptions.FilePart("same","TEXT","fictional-text",null,null,null,true),new ApiRequestOptions.FilePart("same","FILE",null,file.fileId(),"fixture.bin",null,true)):null,rows);}
    ApiSpec request(String path,ApiRequestOptions options) throws Exception {return new ApiSpec(null,"combination","POST",endpoint+path,"{}",null,null,"200",null,3000,false,true,null,new ObjectMapper().writeValueAsString(List.of(new ApiBodyAssert.Rule("$","schema","{\"type\":\"object\",\"required\":[\"ok\"],\"properties\":{\"ok\":{\"const\":true}}}"))),options);}
    String profile(String kind){return (String)profiles.create(1,new ApiHttpProfiles.Input(kind,"combination","127.0.0.1",server.getAddress().getPort(),null,null,"fixture-password",kind.equals("OAUTH2")?endpoint+"/token":null,"client_credentials","fictional-client","fictional-client-secret",null,null,null,"fixture-user","SHA-256","auth")).get("id");}
    @Test void multipartManagedTypeMatchesActualBoundaryAndIndependentSudHmacWithDuplicateHeaders() throws Exception {
        repo.saveSignConfig(1,true,"fake-app","fake-secret","fake-nonce","fake-game");var file=upload();
        var rows=List.of(new ApiRequestOptions.HeaderRow("Content-Type","application/json",true),new ApiRequestOptions.HeaderRow("X-Repeat","first",true),new ApiRequestOptions.HeaderRow("X-Repeat","second",true));
        var result=runner.send(request("/upload",options("MULTIPART",file,auth("SUD",null),rows)),1L,null);assertThat(result.verdict()).isEqualTo("PASS");Wire actual=wires.getLast();String contentType=actual.header("Content-Type");assertThat(contentType).startsWith("multipart/form-data; boundary=");String boundary=contentType.substring(contentType.indexOf("boundary=")+9);assertThat(new String(actual.body(),StandardCharsets.ISO_8859_1)).startsWith("--"+boundary+"\r\n").endsWith("--"+boundary+"--\r\n");assertThat(actual.headers().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("X-Repeat")).findFirst().orElseThrow().getValue()).containsExactly("first","second");
        var matcher=java.util.regex.Pattern.compile("timestamp=\"([0-9]+)\",nonce=\"([^\"]+)\",signature=\"([^\"]+)\"").matcher(actual.header("Authorization"));assertThat(matcher.find()).isTrue();var mac=javax.crypto.Mac.getInstance("HmacSHA1");mac.init(new javax.crypto.spec.SecretKeySpec("fake-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA1"));mac.update(("fake-app\n"+matcher.group(1)+"\n"+matcher.group(2)+"\n").getBytes(StandardCharsets.UTF_8));mac.update(actual.body());mac.update((byte)'\n');assertThat(HexFormat.of().formatHex(mac.doFinal())).isEqualTo(matcher.group(3));
        assertThatThrownBy(()->files.delete(file.fileId())).hasMessageContaining("引用").hasMessageNotContaining("正在预览");
    }
    @Test void binaryOAuthAndSameOrigin307KeepOriginalBytesAndOrderedHeaders() throws Exception {
        var file=upload();var result=runner.send(request("/redirect",options("BINARY",file,auth("OAUTH2",profile("OAUTH2")),List.of(new ApiRequestOptions.HeaderRow("X-Repeat","first",true),new ApiRequestOptions.HeaderRow("X-Repeat","second",true)))),1L,null);
        assertThat(result.verdict()).isEqualTo("PASS");var sent=wires.stream().filter(w->!w.path().equals("/token")).toList();assertThat(sent).hasSize(2);for(var wire:sent){assertThat(wire.body()).isEqualTo(payload);assertThat(wire.header("Authorization")).isEqualTo("Bearer fictional-combination-token");assertThat(wire.header("Content-Type")).isEqualTo("application/octet-stream");}
        assertThat(result.metadata()).contains("BINARY","nextMethod");assertThat(new ObjectMapper().readTree(result.metadata()).path("safeDisplay").toString()).doesNotContain("fictional-combination-token");
    }
    @Test void digestChallengeReusesExactlyTheSameMultipartBoundaryAndBytes() throws Exception {
        var file=upload();var result=runner.send(request("/digest",options("MULTIPART",file,auth("DIGEST",profile("DIGEST")),List.of())),1L,null);
        assertThat(result.verdict()).isEqualTo("PASS");assertThat(wires).hasSize(2);assertThat(wires.get(0).body()).isEqualTo(wires.get(1).body());assertThat(wires.get(0).header("Content-Type")).isEqualTo(wires.get(1).header("Content-Type"));assertThat(wires.get(1).header("Authorization")).contains("uri=\"/digest\"");assertThat(result.reqHeaders()).contains("文件正文").doesNotContain("（无请求体）");
    }
    @Test void binary303BecomesAnEmptyGetWithoutLeakingBodyHeaders() throws Exception {
        var file=upload();var result=runner.send(request("/seeother",options("BINARY",file,auth("NONE",null),List.of())),1L,null);
        assertThat(result.verdict()).isEqualTo("PASS");assertThat(wires).hasSize(2);assertThat(wires.get(0).body()).isEqualTo(payload);assertThat(wires.get(1).method()).isEqualTo("GET");assertThat(wires.get(1).body()).isEmpty();assertThat(wires.get(1).header("Content-Type")).isNull();
    }
}
