package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApiFreezeFoundationTest {
    @TempDir Path directory;
    JdbcTemplate jdbc() { return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("fixture.db"))); }
    @Test void vaultBindsNamespaceIdentityAndKeyAndDoesNotStorePlaintext() throws Exception {
        var jdbc = jdbc(); var repo = new ApiRepository(jdbc); var vault = new ApiPlanVault(jdbc);
        String sealed = vault.encrypt("api-batch", 41, "fictional-private-value");
        assertThat(sealed).doesNotContain("fictional-private-value");
        assertThat(vault.decrypt("api-batch", 41, sealed)).isEqualTo("fictional-private-value");
        assertThat(new ApiPlanVault(jdbc()).decrypt("api-batch", "41", sealed)).isEqualTo("fictional-private-value");
        assertThatThrownBy(() -> vault.decrypt("perf-run", 41, sealed)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> vault.decrypt("api-batch", 42, sealed)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.size(vault.keyFile())).isEqualTo(32);
        assertThat(Files.getPosixFilePermissions(vault.keyFile())).isEqualTo(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        Files.delete(vault.keyFile());
        assertThatThrownBy(() -> vault.decrypt("api-batch", 41, sealed)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(vault.keyFile())).isFalse();
    }
    @Test void frozenEnvironmentAndRunCookieScopesRemainIndependent() throws Exception {
        var repo = new ApiRepository(jdbc()); var runner = new ApiRunner(repo,new CodeDictionary(repo),"");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/set")) exchange.getResponseHeaders().set("Set-Cookie", "session="+exchange.getRequestURI().getQuery()+"; Path=/");
            String value = exchange.getRequestHeaders().getFirst("Cookie");
            byte[] bytes = (value == null ? "none" : value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            var transport=new ApiRequestOptions.Transport("NEVER","DIRECT",null,null,"ENV",null);
            var options=new ApiRequestOptions("NONE",null,new ApiRequestOptions.Auth("NONE",null,null,null,null,null,null),null,null,transport,null);
            var peek=new ApiSpec(null,"peek","GET",base+"/peek","{}",null,null,"200","",2000,false,true,null,null,options);
            var setA=new ApiSpec(null,"set","GET",base+"/set?A","{}",null,null,"200","",2000,false,true,null,null,options);
            var setB=new ApiSpec(null,"set","GET",base+"/set?B","{}",null,null,"200","",2000,false,true,null,null,options);
            repo.setEnvStrict(1,true);var frozen=runner.snapshotEnvironment(peek,1L);
            assertThat(frozen.strictVariables()).isTrue();assertThat(frozen.inheritedProxy()).isEmpty();
            var a=frozen.withSessionScope("perf-run-1");var b=frozen.withSessionScope("perf-run-2");
            runner.resetCookieScope(a.cookieScope());runner.resetCookieScope(b.cookieScope());
            runner.executeWithEnvironment(setA,a,Map.of(),null,1,1);
            assertThat(runner.executeWithEnvironment(peek,a,Map.of(),null,1,2).respBody()).contains("session=A");
            assertThat(runner.executeWithEnvironment(peek,b,Map.of(),null,1,1).respBody()).isEqualTo("none");
            runner.executeWithEnvironment(setB,b,Map.of(),null,1,2);
            assertThat(runner.executeWithEnvironment(peek,frozen,Map.of(),null,1,1).respBody()).isEqualTo("none");
            runner.resetCookieScope(a.cookieScope());
            assertThat(runner.executeWithEnvironment(peek,a,Map.of(),null,1,3).respBody()).isEqualTo("none");
            assertThat(runner.executeWithEnvironment(peek,b,Map.of(),null,1,3).respBody()).contains("session=B");
            var restarted=new ApiRunner(repo,new CodeDictionary(repo),"");
            assertThat(restarted.executeWithEnvironment(peek,b,Map.of(),null,1,4).respBody()).isEqualTo("none");
            assertThat(repo.listRecentAdhoc(100)).isEmpty();
        } finally {server.stop(0);}
    }
}
