package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** A raw draft can be incomplete; malformed input must fail before the real loopback mock receives anything. */
class ApiRawHeadersCompatibilityTest {
    @TempDir Path temporary;
    ApiRepository repository;ApiRunner runner;HttpServer server;String url;AtomicInteger hits;
    @BeforeEach void setup()throws Exception{
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+temporary.resolve("raw-headers.db")));repository=new ApiRepository(jdbc);runner=new ApiRunner(repository,new CodeDictionary(repository),"");hits=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",exchange->{hits.incrementAndGet();exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,2);exchange.getResponseBody().write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));exchange.close();});server.start();url="http://127.0.0.1:"+server.getAddress().getPort()+"/ok";
    }
    @AfterEach void close(){if(server!=null)server.stop(0);}
    ApiSpec spec(String raw,ApiRequestOptions options){return new ApiSpec(null,"raw draft","GET",url,raw,null,null,"200",null,1000,false,true,null,null,options);}
    ApiRequestOptions rows(List<ApiRequestOptions.HeaderRow> rows){return new ApiRequestOptions("NONE",null,null,null,null,null,false,rows);}
    @ParameterizedTest @ValueSource(strings={"Content-Type: application/json","{broken","[]","null","{\"X-Test\":\"ok\"} trailing"})
    void malformedRawDraftCanBeCreatedAndUpdatedButExecutionReturnsJsonErrorWithoutSending(String raw){
        for(ApiRequestOptions options:Arrays.asList(null,new ApiRequestOptions("NONE",null,null,null))){
            var created=repository.createCase(spec(raw,options));assertThat(created.headers()).isEqualTo(raw);
            var valid=repository.createCase(spec("{}",options));var updated=repository.updateCase(valid.id(),spec(raw,options));assertThat(updated.headers()).isEqualTo(raw);
            for(ApiSpec saved:List.of(created,updated)){
                var preview=runner.prepare(saved,1L);assertThat(preview.valid()).isFalse();assertThat(preview.error()).contains("JSON 对象");
                var result=runner.send(saved,1L,null);assertThat(result.verdict()).isEqualTo("ERROR");assertThat(result.reason()).contains("JSON 对象");assertThat(result.httpStatus()).isNull();
            }
        }
        assertThat(hits).hasValue(0);
    }
    @Test void invalidOrderedRowsStillRejectCreateAndUpdateWithoutChangingSavedCase(){
        var original=repository.createCase(spec("{}",null));
        for(var invalid:List.of(
                List.of(new ApiRequestOptions.HeaderRow("Host","bad",false)),
                List.of(new ApiRequestOptions.HeaderRow("X-Test","bad\r\nvalue",true)),
                List.of(new ApiRequestOptions.HeaderRow("Cookie","one",true),new ApiRequestOptions.HeaderRow("cookie","two",true)),
                List.of(new ApiRequestOptions.HeaderRow("bad name","value",true)))){
            var request=spec("Content-Type: application/json",rows(invalid));assertThatThrownBy(()->repository.createCase(request)).isInstanceOf(IllegalArgumentException.class);assertThatThrownBy(()->repository.updateCase(original.id(),request)).isInstanceOf(IllegalArgumentException.class);assertThat(repository.getCase(original.id()).headers()).isEqualTo("{}");assertThat(repository.getCase(original.id()).options()).isNull();
        }
        assertThat(hits).hasValue(0);
    }
    @Test void validRawRestrictedHeadersRemainRejectedAtSaveAndPreview(){
        for(String raw:List.of("{\"Host\":\"bad\"}","{\"Content-Length\":\"12\"}","{\"X-Test\":\"bad\\r\\nvalue\"}")){
            var request=spec(raw,null);assertThatThrownBy(()->repository.createCase(request)).isInstanceOf(IllegalArgumentException.class);assertThat(runner.prepare(request,1L).valid()).isFalse();
        }
        assertThat(hits).hasValue(0);
    }
    @Test void validOrderedModeIgnoresRetainedMalformedRawAndSendsOnce(){
        var request=repository.createCase(spec("Content-Type: application/json",rows(List.of(new ApiRequestOptions.HeaderRow("X-Test","ok",true)))));
        assertThat(runner.prepare(request,1L).valid()).isTrue();assertThat(runner.send(request,1L,null).verdict()).isEqualTo("PASS");assertThat(hits).hasValue(1);
    }
}
