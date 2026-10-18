package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Test-only: controlled loopback HTTP/2 fixture. Certificates/private keys are generated
 * in @TempDir; fixture-password is an intentionally public test password, never a production credential. */
@Timeout(45)
class ApiHttpTlsProtocolTest {
    @TempDir Path temporary;
    List<Process> children=new ArrayList<>();
    @AfterEach void stop(){children.forEach(p->{p.destroy();try{if(!p.waitFor(2,TimeUnit.SECONDS))p.destroyForcibly();}catch(InterruptedException e){Thread.currentThread().interrupt();}});}
    void command(String... args)throws Exception{var p=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(temporary.resolve("certificate-tool.log").toFile()).start();if(!p.waitFor(15,TimeUnit.SECONDS)){p.destroyForcibly();throw new AssertionError("fixture certificate tool timed out");}assertThat(p.exitValue()).isZero();}
    static String testExecutable(String property, String environment, String fallback) {
        String configured = System.getProperty(property);
        if (configured == null || configured.isBlank()) configured = System.getenv(environment);
        if (configured == null || configured.isBlank()) configured = fallback;
        Path path = Path.of(configured);
        if (path.isAbsolute() || path.getNameCount() > 1) {
            if (Files.isExecutable(path)) return path.toAbsolutePath().normalize().toString();
        } else {
            for (String directory : System.getenv().getOrDefault("PATH", "").split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (directory.isBlank()) continue;
                Path candidate = Path.of(directory).resolve(configured);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toAbsolutePath().normalize().toString();
            }
        }
        throw new IllegalStateException("Missing test executable: " + fallback + "; set " + environment + " or -D" + property + " to an executable path");
    }
    String fixture(boolean mutual)throws Exception{
        Path script=temporary.resolve("http2-fixture.cjs");Files.writeString(script,"""
                const fs=require('node:fs'),http2=require('node:http2');
                const server=http2.createSecureServer({key:fs.readFileSync(process.argv[2]),cert:fs.readFileSync(process.argv[3]),ca:fs.readFileSync(process.argv[3]),allowHTTP1:true,requestCert:process.argv[4]==='true',rejectUnauthorized:process.argv[4]==='true'},(req,res)=>{res.end(JSON.stringify({protocol:req.httpVersion,clientAuthorized:!!req.socket.authorized}));});
                server.on('sessionError',()=>{});server.on('tlsClientError',()=>{});
                server.listen(0,'127.0.0.1',()=>process.stdout.write(String(server.address().port)+'\\n'));
                process.on('SIGTERM',()=>server.close(()=>process.exit(0)));
                """);
        var process=new ProcessBuilder(testExecutable("sudpanel.test.node", "NODE", "node"),script.toString(),temporary.resolve("fixture.pem").toString(),temporary.resolve("fixture.crt").toString(),String.valueOf(mutual)).redirectError(temporary.resolve("node-fixture.log").toFile()).start();children.add(process);
        var future=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return new BufferedReader(new InputStreamReader(process.getInputStream())).readLine();}catch(IOException e){throw new RuntimeException(e);}});String port=future.get(10,TimeUnit.SECONDS);assertThat(port).matches("[0-9]+");return "https://localhost:"+port;
    }
    ApiHttpProfiles.Input profile(String target,String ca,String p12,String password){return new ApiHttpProfiles.Input("TLS","fixture","localhost",java.net.URI.create(target).getPort(),ca,p12,password,null,null,null,null,null,null,null,null,null,null);}
    ApiSpec spec(String url,String protocol,String cert,boolean insecure){return new ApiSpec(null,"tls","GET",url,"{}",null,null,"200","",4000,insecure,true,null,null,new ApiRequestOptions("NONE",null,null,null,null,new ApiRequestOptions.Transport("NORMAL","DIRECT",null,null,"OFF",null,3,protocol,null,null,cert),true));}
    @Test void controlledAlpnHttp2StrictFallbackMutualTlsAndFailureDiagnostics()throws Exception{
        String keytool=Path.of(System.getProperty("java.home"),"bin","keytool").toString(),store=temporary.resolve("fixture.p12").toString();
        command(keytool,"-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-ext","SAN=dns:localhost","-ext","EKU=serverAuth,clientAuth","-validity","2","-storetype","PKCS12","-keystore",store,"-storepass","fixture-password","-keypass","fixture-password","-noprompt");
        command(keytool,"-exportcert","-rfc","-alias","fixture","-keystore",store,"-storepass","fixture-password","-file",temporary.resolve("fixture.crt").toString());
        command(testExecutable("sudpanel.test.openssl", "OPENSSL", "openssl"),"pkcs12","-in",store,"-nodes","-passin","pass:fixture-password","-out",temporary.resolve("fixture.pem").toString());
        String ca=Files.readString(temporary.resolve("fixture.crt")),p12=Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(store)));String target=fixture(false);
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+temporary.resolve("tls.db")));var repo=new ApiRepository(jdbc);var profiles=new ApiHttpProfiles(jdbc,repo);var runner=new ApiRunner(repo,new CodeDictionary(repo),"");runner.setAdvancedProfiles(profiles);
        assertThat(runner.send(spec(target,"HTTP_2",null,false),1L,null).verdict()).isEqualTo("ERROR");
        String id=(String)profiles.create(1,profile(target,ca,null,null)).get("id");var direct=java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_2).sslContext(profiles.tls(1,id,java.net.URI.create(target))).build().send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(target)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());assertThat(direct.version()).as("direct ALPN protocol").isEqualTo(java.net.http.HttpClient.Version.HTTP_2);var h2=runner.send(spec(target,"HTTP_2",id,false),1L,null);assertThat(h2.verdict()).as(h2.reason()).isEqualTo("PASS");assertThat(h2.respBody()).contains("\"protocol\":\"2.0\"");assertThat(h2.metadata()).contains("actualHttpVersion\":\"HTTP_2");
        var h1=runner.send(spec(target,"HTTP_1_1",id,false),1L,null);assertThat(h1.verdict()).as(h1.reason()).isEqualTo("PASS");assertThat(h1.respBody()).contains("\"protocol\":\"1.1\"");
        assertThat(profiles.list(1).toString()).contains("sha256","expiresAt","CN=localhost").doesNotContain("BEGIN CERTIFICATE","fixture-password");
        assertThat(runner.prepare(spec(target,"HTTP_2",id,true),1L).valid()).isFalse();assertThat(runner.prepare(spec(target.replace("localhost","127.0.0.1"),"HTTP_2",id,false),1L).error()).contains("域名/端口不匹配");
        long other=repo.createEnv("other").id();assertThat(runner.prepare(spec(target,"HTTP_2",id,false),other).valid()).isFalse();
        assertThatThrownBy(()->profiles.create(1,profile(target,ca,p12,"wrong-password"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("口令");
        String mutual=fixture(true);String onlyCa=(String)profiles.create(1,profile(mutual,ca,null,null)).get("id");assertThat(runner.send(spec(mutual,"HTTP_2",onlyCa,false),1L,null).verdict()).isEqualTo("ERROR");
        String client=(String)profiles.create(1,profile(mutual,ca,p12,"fixture-password")).get("id");var authenticated=runner.send(spec(mutual,"HTTP_2",client,false),1L,null);assertThat(authenticated.verdict()).as(authenticated.reason()).isEqualTo("PASS");assertThat(authenticated.respBody()).contains("\"clientAuthorized\":true");
        profiles.delete(1,client);assertThat(runner.prepare(spec(mutual,"HTTP_2",client,false),1L).valid()).isFalse();
        String expired=temporary.resolve("expired.p12").toString();command(keytool,"-genkeypair","-alias","expired","-keyalg","RSA","-keysize","2048","-dname","CN=localhost","-startdate","-2d","-validity","1","-storetype","PKCS12","-keystore",expired,"-storepass","fixture-password","-noprompt");assertThatThrownBy(()->profiles.create(1,profile(target,null,Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(expired))),"fixture-password"))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("过期");
    }
}
