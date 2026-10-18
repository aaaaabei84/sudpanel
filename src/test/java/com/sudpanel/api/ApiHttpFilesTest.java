package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import com.sudpanel.web.ApiFileController;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApiHttpFilesTest {
    @TempDir Path temp;
    JdbcTemplate jdbc;ApiRepository repo;ApiRunner runner;ApiFileStore files;HttpServer server;String url;
    final ObjectMapper json=new ObjectMapper();
    final AtomicReference<byte[]> wire=new AtomicReference<>();final AtomicReference<Map<String,List<String>>> wireHeaders=new AtomicReference<>();
    final byte[] binary={0,(byte)255,(byte)0xc3,0x28,13,10,1,2,3,(byte)128};
    CountDownLatch arrived,release;
    @BeforeEach void setup()throws Exception{
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+temp.resolve("api.db")));repo=new ApiRepository(jdbc);files=new ApiFileStore(jdbc,temp.resolve("api.db"));runner=new ApiRunner(repo,new CodeDictionary(repo),"");runner.fileStore(files);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));
        server.createContext("/",x->{try{
            wire.set(x.getRequestBody().readAllBytes());wireHeaders.set(new LinkedHashMap<>(x.getRequestHeaders()));String path=x.getRequestURI().getPath();byte[] body="{\"code\":0}".getBytes(StandardCharsets.UTF_8);String type="application/json";
            if(path.equals("/binary")){body=binary;type="application/octet-stream";}
            if(path.equals("/large-text")){body=("<script>throw 42</script>"+"文本".repeat(30000)).getBytes(StandardCharsets.UTF_8);type="text/html; charset=UTF-8";}
            if(path.startsWith("/too-")){body=new byte[10000];Arrays.fill(body,(byte)'x');type="text/plain";if(path.equals("/too-gzip")){var out=new ByteArrayOutputStream();try(var gzip=new GZIPOutputStream(out)){gzip.write(body);}body=out.toByteArray();x.getResponseHeaders().set("Content-Encoding","gzip");}}
            if(path.equals("/held")){arrived.countDown();release.await(3,TimeUnit.SECONDS);}
            x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(200,path.equals("/too-chunked")?0:body.length);x.getResponseBody().write(body);
        }catch(Exception ignored){}finally{x.close();}});server.start();url="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void close(){if(release!=null)release.countDown();if(server!=null)server.stop(0);}
    ApiFileStore.FileInfo upload(byte[] b)throws Exception{return files.upload(new ByteArrayInputStream(b),"fixture.bin","application/octet-stream");}
    ApiRequestOptions opts(String type,ApiRequestOptions.FileBody b,List<ApiRequestOptions.FilePart> parts,Integer limit){return new ApiRequestOptions(type,null,null,null,null,new ApiRequestOptions.Transport("NEVER","DIRECT",null,null,"OFF",limit),true,b,parts);}
    ApiSpec spec(String path,ApiRequestOptions options,String assertions){return new ApiSpec(null,"files","POST",url+path,"{}","ignored raw body",null,"200","",3000,false,true,null,assertions,options);}
    ApiRequestOptions.FilePart text(String name,String value,boolean on){return new ApiRequestOptions.FilePart(name,"TEXT",value,null,null,null,on);}
    @Test void binaryPublishesExactNonUtf8AndSignsCapturedBytes()throws Exception{
        var f=upload(binary);repo.saveSignConfig(1,true,"fake-app","fake-secret","fixed-nonce",null);
        var result=runner.send(spec("/ok",opts("BINARY",new ApiRequestOptions.FileBody(f.fileId(),null,null),null,null),null),1L,null);
        assertThat(result.verdict()).isEqualTo("PASS");assertThat(wire.get()).isEqualTo(binary);assertThat(result.reqBody()).isNull();assertThat(result.reqHeaders()).doesNotContain("无请求体").contains("10 字节");assertThat(result.metadata()).contains(f.sha256(),"BINARY");
        assertWireSignature();assertThatThrownBy(()->ApiActualCurl.history(result,false)).hasMessageContaining("文件正文");assertThatThrownBy(()->ApiHistoryDraft.restore(result,repo)).hasMessageContaining("文件正文");
    }
    @Test void multipartOrderDuplicatesDisabledEmptyUnicodeAndFinalBoundarySignature()throws Exception{
        var f=upload(binary);repo.saveVars(1,List.of(),List.of(),List.of(new ApiVariables.Var("word","中文&+")));repo.saveSignConfig(1,true,"fake-app","fake-secret","fixed-nonce",null);
        var parts=List.of(text("duplicate","{{word}}",true),text("duplicate","",true),text("skip","NOPE",false),new ApiRequestOptions.FilePart("disabled-file","FILE",null,"not-a-real-id",null,null,false),new ApiRequestOptions.FilePart("attachment","FILE",null,f.fileId(),"a\"b.bin","application/octet-stream",true));
        var o=opts("MULTIPART",null,parts,null);var request=spec("/ok",o,null);request=new ApiSpec(null,"multipart","POST",request.url(),"{\"Content-Type\":\"wrong/type\"}","MUST_NOT_SEND",null,"200","",3000,false,true,null,null,o);
        var preview=runner.prepare(request,1L);assertThat(preview.valid()).isTrue();assertThat(wire.get()).isNull();assertThat(preview.body()).isNull();assertThat(json.writeValueAsString(preview)).doesNotContain("MUST_NOT_SEND","NOPE","中文&+");assertThat(preview.bodyMetadata()).containsKeys("boundary","sha256","parts");
        var result=runner.send(request,1L,null);assertThat(result.verdict()).isEqualTo("PASS");byte[] received=wire.get();String ct=header(wireHeaders.get(),"Content-Type"),boundary=ct.substring(ct.indexOf("boundary=")+9);assertThat(ct).startsWith("multipart/form-data;");assertThat(received).startsWith(("--"+boundary+"\r\n").getBytes(StandardCharsets.UTF_8)).endsWith(("--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        String latin=new String(received,StandardCharsets.ISO_8859_1);assertThat(latin.split("name=\"duplicate\"",-1)).hasSize(3);assertThat(latin).doesNotContain("NOPE","MUST_NOT_SEND","name=\"skip\"").contains("filename=\"a\\\"b.bin\"");assertThat(indexOf(received,binary)).isGreaterThan(0);assertThat(indexOf(received,"中文&+".getBytes(StandardCharsets.UTF_8))).isGreaterThan(0);assertWireSignature();
    }
    @Test void filePartsRejectControlNamesPathsAndMissingIdsBeforeNetwork()throws Exception{
        var bad=opts("MULTIPART",null,List.of(text("evil\r\nX: y","x",true)),null);assertThat(runner.prepare(spec("/ok",bad,null),1L).valid()).isFalse();assertThat(wire.get()).isNull();
        assertThatThrownBy(()->files.info("../../outside")).hasMessageContaining("fileId");
        var missing=opts("BINARY",new ApiRequestOptions.FileBody(UUID.randomUUID().toString(),null,null),null,null);assertThat(runner.send(spec("/ok",missing,null),1L,null).verdict()).isEqualTo("ERROR");assertThat(wire.get()).isNull();
    }
    @Test void leaseBlocksDeletionUntilRequestFinishes()throws Exception{
        var f=upload(binary);arrived=new CountDownLatch(1);release=new CountDownLatch(1);var request=spec("/held",opts("BINARY",new ApiRequestOptions.FileBody(f.fileId(),null,null),null,null),null);
        var work=CompletableFuture.supplyAsync(()->runner.execute(request,null,null,1,1));assertThat(arrived.await(2,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->files.delete(f.fileId())).hasMessageContaining("正在");release.countDown();assertThat(work.get(3,TimeUnit.SECONDS).verdict()).isEqualTo("PASS");files.delete(f.fileId());assertThatThrownBy(()->files.info(f.fileId())).hasMessageContaining("不存在");
    }
    @Test void databaseIsDurableAndCacheIsRepairableButSymlinkIsRefused()throws Exception{
        var f=upload(binary);Path cache=temp.resolve("api.db.api-files").resolve(f.fileId()+".bin");Files.write(cache,new byte[]{7});files=new ApiFileStore(jdbc,temp.resolve("api.db"));try(var lease=files.acquire(f.fileId())){assertThat(lease.bytes()).isEqualTo(binary);}assertThat(Files.readAllBytes(cache)).isEqualTo(binary);
        Files.delete(cache);Path outside=temp.resolve("outside");Files.write(outside,new byte[]{9});Files.createSymbolicLink(cache,outside);assertThatThrownBy(()->files.acquire(f.fileId())).hasMessageContaining("路径");assertThat(Files.readAllBytes(outside)).containsExactly((byte)9);
    }
    @Test void savedCaseWorkspaceAndFrozenPlanEachBlockDeletion()throws Exception{
        var f=upload(binary);var request=spec("/ok",opts("BINARY",new ApiRequestOptions.FileBody(f.fileId(),null,null),null,null),null);var c=repo.createCase(request);assertThatThrownBy(()->files.delete(f.fileId())).hasMessageContaining("引用");repo.deleteCase(c.id());
        var workspace=new ApiWorkspaceRepository(jdbc);String workspaceId=UUID.randomUUID().toString(),tabId=UUID.randomUUID().toString();workspace.save(workspaceId,0,"{\"version\":1,\"activeId\":\""+tabId+"\",\"tabs\":[{\"id\":\""+tabId+"\",\"draft\":{\"fileId\":\""+f.fileId()+"\"},\"envId\":\"1\"}],\"closed\":[]}");assertThatThrownBy(()->files.delete(f.fileId())).hasMessageContaining("引用");workspace.delete(workspaceId,1);
        var vault=new ApiPlanVault(jdbc);jdbc.update("INSERT INTO api_batch_plan(batch_id,schema_version,sealed_plan,digest,created_at) VALUES(999,1,?,'fixture','now')",vault.encrypt("api-batch",999,"{\"fileId\":\""+f.fileId()+"\"}"));assertThatThrownBy(()->files.delete(f.fileId())).hasMessageContaining("引用");jdbc.update("DELETE FROM api_batch_plan WHERE batch_id=999");files.delete(f.fileId());
    }
    @Test void uploadLimitQuotaAndCorruptionFailClosed()throws Exception{
        assertThatThrownBy(()->files.upload(new ByteArrayInputStream(new byte[ApiFileStore.UPLOAD_LIMIT+1]),"big.bin",null)).hasMessageContaining("16MiB");assertThat(files.overview().files()).isEmpty();
        var f=upload(binary);jdbc.update("UPDATE api_file SET bytes=? WHERE id=?",ApiFileStore.STORE_LIMIT,f.fileId());assertThatThrownBy(()->upload(new byte[]{1})).hasMessageContaining("容量");jdbc.update("UPDATE api_file SET bytes=?,payload=? WHERE id=?",binary.length,new byte[]{1},f.fileId());assertThatThrownBy(()->files.acquire(f.fileId())).hasMessageContaining("校验失败");
    }
    @Test void binaryResponseUsesOriginalDownloadAndMetadataAssertions()throws Exception{
        String assertions="[{\"path\":\"$response.bytes\",\"op\":\"equals\",\"expect\":\"10\"},{\"path\":\"$response.sha256\",\"op\":\"equals\",\"expect\":\""+ApiFileStore.sha(binary)+"\"},{\"path\":\"header:Content-Type\",\"op\":\"equals\",\"expect\":\"application/octet-stream\"}]";
        var result=runner.send(spec("/binary",null,assertions),1L,null);assertThat(result.verdict()).isEqualTo("PASS");assertThat(result.respBody()).isNull();var body=json.readTree(result.metadata()).path("responseBody");assertThat(body.path("classification").asText()).isEqualTo("BINARY");var controller=new ApiFileController(files);var response=controller.download(body.path("file").path("fileId").asText());var out=new ByteArrayOutputStream();response.getBody().writeTo(out);assertThat(out.toByteArray()).isEqualTo(binary);assertThat(response.getHeaders().getFirst("X-SudPanel-SHA256")).isEqualTo(ApiFileStore.sha(binary));assertThat(response.getHeaders().getFirst("Content-Disposition")).startsWith("attachment;");
        var bad=runner.send(spec("/binary",null,"[{\"path\":\"missing\",\"op\":\"notexists\"}]"),1L,null);assertThat(bad.verdict()).isEqualTo("FAIL");assertThat(bad.reason()).contains("二进制");
    }
    @Test void largeHtmlIsEscapedSourceAndOriginalBytesSurvivePreviewTruncation()throws Exception{
        var r=runner.send(spec("/large-text",null,"[{\"path\":\"\",\"op\":\"contains\",\"expect\":\"文本\"}]"),1L,null);assertThat(r.verdict()).isEqualTo("PASS");assertThat(r.respBody().length()).isLessThan(20200);var m=json.readTree(r.metadata()).path("responseBody");String id=m.path("file").path("fileId").asText();try(var lease=files.acquire(id)){assertThat(new String(lease.bytes(),StandardCharsets.UTF_8)).startsWith("<script>").endsWith("文本");assertThat(lease.info().sha256()).isEqualTo(m.path("sha256").asText());}
        var controller=new ApiFileController(files);var download=controller.download(id);assertThat(download.getHeaders().getContentType().toString()).isEqualTo("application/octet-stream");assertThat(download.getHeaders().getFirst("Content-Security-Policy")).contains("sandbox");download.getBody().writeTo(new ByteArrayOutputStream());assertThatThrownBy(()->controller.image(id)).hasMessageContaining("仅支持");
    }
    @Test void allLimitStagesProduceStructuredErrorAndNoAssertions()throws Exception{
        for(String path:List.of("/too-fixed","/too-chunked","/too-gzip")){
            var r=runner.send(spec(path,opts("NONE",null,null,1024),"[{\"path\":\"\",\"op\":\"notexists\"}]"),1L,null);assertThat(r.verdict()).isEqualTo("ERROR");assertThat(r.httpStatus()).isEqualTo(200);assertThat(r.assertsResult()).isNull();var m=json.readTree(r.metadata()).path("responseLimit");assertThat(m.path("complete").asBoolean(true)).isFalse();assertThat(m.path("tooLarge").asBoolean()).isTrue();assertThat(m.path("retainedBytes").asLong()).isLessThanOrEqualTo(1024);assertThat(m.path("stage").asText()).isEqualTo(path.equals("/too-gzip")?"DECODED":"WIRE");if(!path.equals("/too-fixed")){assertThat(m.path("readBytes").asLong()).isGreaterThan(1024);assertThat(m.path("file").path("kind").asText()).isEqualTo("PARTIAL");}
        }
    }
    @Test void actualControllerHttpUploadAndAsyncDownloadKeepOriginalBytes()throws Exception{
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ApiFileController(files)).build();
        var uploaded=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/apitest/files").param("filename","fixture-http.bin").param("contentType","application/octet-stream").contentType("application/octet-stream").content(binary)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn();
        var f=json.readTree(uploaded.getResponse().getContentAsString());assertThat(f.path("bytes").asInt()).isEqualTo(binary.length);assertThat(f.path("sha256").asText()).isEqualTo(ApiFileStore.sha(binary));
        var pending=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/apitest/files/"+f.path("fileId").asText()+"/download")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(pending)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(binary)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options","nosniff"));
    }
    @Test void exactUploadLimitAndMultipartAggregateAreEnforcedAndReleasePinsOnFailure()throws Exception{
        byte[] maximum=new byte[ApiFileStore.UPLOAD_LIMIT];var f=upload(maximum);assertThat(f.bytes()).isEqualTo(ApiFileStore.UPLOAD_LIMIT);
        var part=new ApiRequestOptions.FilePart("file","FILE",null,f.fileId(),null,null,true);
        var bad=opts("MULTIPART",null,List.of(part,part,part,part),null);var result=runner.prepare(spec("/ok",bad,null),1L);assertThat(result.valid()).isFalse();assertThat(result.error()).contains("64MiB");assertThat(wire.get()).isNull();files.delete(f.fileId());
    }
    @Test void cacheDirectoryFailureDoesNotPreventPlatformMetadataStartup()throws Exception{
        var f=upload(binary);Path cache=temp.resolve("api.db.api-files");try(var paths=Files.list(cache)){for(Path p:paths.toList())Files.delete(p);}Files.delete(cache);Path outside=temp.resolve("outside-cache");Files.createDirectory(outside);Files.createSymbolicLink(cache,outside);
        var degraded=new ApiFileStore(jdbc,temp.resolve("api.db"));assertThat(degraded.info(f.fileId()).sha256()).isEqualTo(f.sha256());assertThat(degraded.overview().retention()).contains("其他平台功能可继续");assertThat(repo.listEnvs()).isNotEmpty();assertThatThrownBy(()->degraded.acquire(f.fileId())).hasMessageContaining("路径");try(var paths=Files.list(outside)){assertThat(paths.count()).isZero();}
    }
    @Test void activeResultOwnerPreventsOrphanCleanupAndHeadDoesNotLeakLease()throws Exception{
        var f=upload(binary);files.activity(()->true);assertThatThrownBy(()->files.delete(f.fileId())).hasMessageContaining("收尾");assertThatThrownBy(()->files.cleanupOrphans()).hasMessageContaining("收尾");files.activity(()->false);
        var request=new org.springframework.mock.web.MockHttpServletRequest("HEAD","/api/apitest/files/"+f.fileId()+"/download");assertThat(new ApiFileController(files).head(f.fileId(),request).getHeaders().getContentLength()).isEqualTo(binary.length);files.delete(f.fileId());
    }
    @Test void optionsRemainCompatibleAndFilePreviewNeverSerializesBytes()throws Exception{
        var old=json.readValue("{\"bodyType\":\"RAW\",\"params\":[],\"auth\":null,\"extractors\":[]}",ApiRequestOptions.class);assertThat(old.fileBody()).isNull();assertThat(old.parts()).isNull();assertThat(new ApiRequestOptions("RAW",null,null,null,null,null,null).parts()).isNull();
        var f=upload(binary);var preview=runner.prepare(spec("/ok",opts("BINARY",new ApiRequestOptions.FileBody(f.fileId(),null,null),null,null),null),1L);assertThat(preview.valid()).isTrue();assertThat(json.writeValueAsString(preview)).contains(f.sha256()).doesNotContain(Base64.getEncoder().encodeToString(binary),"wireBody","filePayload");files.delete(f.fileId());
    }
    void assertWireSignature()throws Exception{
        String auth=header(wireHeaders.get(),"Authorization");var m=java.util.regex.Pattern.compile("timestamp=\"([0-9]+)\",nonce=\"([^\"]+)\",signature=\"([^\"]+)\"").matcher(auth);assertThat(m.find()).isTrue();var mac=javax.crypto.Mac.getInstance("HmacSHA1");mac.init(new javax.crypto.spec.SecretKeySpec("fake-secret".getBytes(StandardCharsets.UTF_8),"HmacSHA1"));mac.update(("fake-app\n"+m.group(1)+"\n"+m.group(2)+"\n").getBytes(StandardCharsets.UTF_8));mac.update(wire.get());mac.update((byte)'\n');assertThat(HexFormat.of().formatHex(mac.doFinal())).isEqualTo(m.group(3));
    }
    static String header(Map<String,List<String>> headers,String name){return headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).findFirst().map(e->e.getValue().getFirst()).orElse("");}
    static int indexOf(byte[] haystack,byte[] needle){outer:for(int i=0;i<=haystack.length-needle.length;i++){for(int j=0;j<needle.length;j++)if(haystack[i+j]!=needle[j])continue outer;return i;}return -1;}
}
