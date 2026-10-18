package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApiPersistentExecutionTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;ApiRepository repo;ApiRunner runner;HttpServer server;ExecutorService pool;String base;
    AtomicInteger count=new AtomicInteger(),cleanups=new AtomicInteger();CountDownLatch arrived=new CountDownLatch(1),release=new CountDownLatch(1);
    @BeforeEach void setup() throws Exception {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("state.db")));
        repo=new ApiRepository(jdbc);runner=new ApiRunner(repo,new CodeDictionary(repo),"");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);pool=Executors.newCachedThreadPool();server.setExecutor(pool);
        server.createContext("/",x->{try {String path=x.getRequestURI().getPath();String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if(path.equals("/block")){arrived.countDown();release.await(8,TimeUnit.SECONDS);}
            if(path.equals("/cleanup"))cleanups.incrementAndGet();
            String response=path.equals("/poll")?"{\"ready\":"+(count.incrementAndGet()>=3)+"}":path.equals("/never")?"{\"ready\":false}":body.isEmpty()?"{}":body;
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
        }catch(Exception ignored){}finally{x.close();}});server.start();base="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void close(){release.countDown();server.stop(0);pool.shutdownNow();}
    ApiSpec spec(String path,String body,String assertions){return new ApiSpec(null,path,"POST",base+path,"{}",body,null,"200",null,7000,false,true,null,assertions,new ApiRequestOptions("JSON",null,null,null));}
    ApiScenario.Step step(String key,ApiSpec s,boolean cleanup,ApiScenario.Poll poll,Long delay){return new ApiScenario.Step(key,key,s.id(),null,"STOP",cleanup,null,false,delay,poll);}
    List<ApiExec> waitBatch(ApiBatch batch) throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);while(runner.isRunning(batch.id())&&System.nanoTime()<until)Thread.sleep(10);assertThat(runner.isRunning(batch.id())).isFalse();return repo.listExecsByBatch(batch.id());}
    String until="[{\"path\":\"ready\",\"op\":\"equals\",\"expect\":true}]";
    @Test void finitePollStoresExactlyThreeAttemptsAndFinalSuccessIsNotRetried() throws Exception {
        var poll=repo.createCase(spec("/poll","{}",null));var cleanup=repo.createCase(spec("/cleanup","{}",null));
        var scenario=repo.saveScenario(null,new ApiScenario(null,"poll",List.of(step("poll",poll,false,new ApiScenario.Poll(5,5L,3000L,until),null),step("cleanup",cleanup,true,null,null))));
        var batch=runner.startScenario(scenario.id(),"poll",0,1);var execs=waitBatch(batch);
        assertThat(count).hasValue(3);assertThat(execs).extracting(ApiExec::verdict).containsExactly("FAIL","FAIL","PASS","PASS");
        for(int i=0;i<3;i++)assertThat(new ObjectMapper().readTree(execs.get(i).metadata()).path("attempt").asInt()).isEqualTo(i+1);
        assertThat(runner.retryPreview(batch.id()).failedSteps()).isZero();assertThat(cleanups).hasValue(1);
    }
    @Test void maximumAttemptsDoesNotProduceAnExtraHttpRequest() throws Exception {
        var poll=repo.createCase(spec("/never","{}",null));var scenario=repo.saveScenario(null,new ApiScenario(null,"never",List.of(step("never",poll,false,new ApiScenario.Poll(2,0L,3000L,until),null))));
        var results=waitBatch(runner.startScenario(scenario.id(),"never",0,1));assertThat(results).hasSize(2);assertThat(results.getLast().metadata()).contains("\"pollFinal\":true");
    }
    @Test void cancellingAsyncRequestIsFastIdempotentAndPreservesUncertainServerOutcome() throws Exception {
        var input=spec("/block","{}",null);String id=java.util.UUID.randomUUID().toString();
        var started=runner.submitRequest(input,null,Map.of("ignore","secret"),id);assertThat(started.requestId()).isEqualTo(id);assertThat(arrived.await(2,TimeUnit.SECONDS)).isTrue();
        assertThat(runner.hasExecutionOwner()).isTrue();long t=System.nanoTime();runner.cancelRequest(id);runner.cancelRequest(id);
        while(runner.request(id).finishedAt()==null&&System.nanoTime()-t<TimeUnit.SECONDS.toNanos(1))Thread.sleep(5);
        var cancelled=runner.request(id);assertThat(cancelled.state()).isEqualTo("CANCELLED");assertThat(cancelled.serverOutcomeUnknown()).isTrue();assertThat(cancelled.exec().metadata()).contains("\"cancelled\":true").doesNotContain("secret");
        assertThat(runner.hasExecutionOwner()).isFalse();assertThat(runner.submitRequest(input,null,Map.of("ignore","secret"),id).exec().id()).isEqualTo(cancelled.exec().id());
        assertThatThrownBy(()->runner.submitRequest(spec("/different","{}",null),null,null,id)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void immediateBatchStopCancelsCurrentRequestButRunsCleanupAndSkipsDownstream() throws Exception {
        var block=repo.createCase(spec("/block","{}",null));var next=repo.createCase(spec("/next","{}",null));var cleanup=repo.createCase(spec("/cleanup","{}",null));
        var scenario=repo.saveScenario(null,new ApiScenario(null,"cancel",List.of(step("block",block,false,null,null),step("next",next,false,null,null),step("cleanup",cleanup,true,null,null))));
        var batch=runner.startScenario(scenario.id(),"cancel",0,1);assertThat(arrived.await(2,TimeUnit.SECONDS)).isTrue();long t=System.nanoTime();runner.stop(batch.id(),true);
        var results=waitBatch(batch);assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t)).isLessThan(1000);assertThat(results).extracting(ApiExec::verdict).containsExactly("ERROR","SKIP","PASS");
        assertThat(results.getFirst().metadata()).contains("\"cancelled\":true");assertThat(repo.getBatch(batch.id()).status()).isEqualTo(ApiBatch.STOPPED);assertThat(cleanups).hasValue(1);
    }
    @Test void stoppingPollingWaitReturnsQuicklyAndStillCleansUp() throws Exception {
        var poll=repo.createCase(spec("/never","{}",null));var cleanup=repo.createCase(spec("/cleanup","{}",null));
        var scenario=repo.saveScenario(null,new ApiScenario(null,"wait",List.of(step("never",poll,false,new ApiScenario.Poll(5,5000L,20000L,until),null),step("cleanup",cleanup,true,null,null))));
        var batch=runner.startScenario(scenario.id(),"wait",0,1);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(repo.listExecsByBatch(batch.id()).isEmpty()&&System.nanoTime()<until)Thread.sleep(5);
        long t=System.nanoTime();runner.stop(batch.id(),false);var results=waitBatch(batch);assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-t)).isLessThan(1000);assertThat(results).hasSize(3);assertThat(results.get(1).metadata()).contains("WAIT_CANCELLED");assertThat(cleanups).hasValue(1);
    }
    @Test void encryptedPlanSurvivesDatabaseReopenShowsDifferencesAndNeverAutoReplays() throws Exception {
        repo.saveVars(1,List.of(),List.of(),List.of(new ApiVariables.Var("private","fictional-secret")));
        var original=repo.createCase(spec("/original","{\"private\":\"{{private}}\"}",null));var batch=runner.startBatch("freeze",List.of(original.id()),0,1);waitBatch(batch);
        var changed=spec("/changed","{}",null);repo.updateCase(original.id(),changed);
        var reopened=new ApiRepository(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("state.db"))));
        var restarted=new ApiRunner(reopened,new CodeDictionary(reopened),"");assertThat(restarted.hasExecutionOwner()).isFalse();
        assertThat(reopened.getExecutionPlan(batch.id()).steps().getFirst().spec().url()).endsWith("/original");
        assertThat(reopened.getExecutionPlan(batch.id()).steps().getFirst().environment().vars().body().getFirst().value()).isEqualTo("fictional-secret");
        assertThat(jdbc.queryForObject("SELECT sealed_plan FROM api_batch_plan WHERE batch_id=?",String.class,batch.id())).doesNotContain("fictional-secret","/original");
        assertThat(new ObjectMapper().writeValueAsString(restarted.plan(batch.id()))).doesNotContain("fictional-secret");
        assertThat(restarted.planDifference(batch.id()).changes()).extracting(ApiPlanReview.Change::field).contains("url","body");
        assertThatThrownBy(()->restarted.replay(batch.id(),"CURRENT",null,false)).hasMessageContaining("差异");
        var replay=restarted.replay(batch.id(),"ORIGINAL",null,false);long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(restarted.isRunning(replay.id())&&System.nanoTime()<until)Thread.sleep(5);
        assertThat(reopened.listExecsByBatch(replay.id()).getFirst().url()).endsWith("/original");
    }
    @Test void failedDataRowsReplayWholeScenarioWithOriginalValuesAndIteration() throws Exception {
        var issue=repo.createCase(spec("/issue","{\"value\":{{data.value}}}","[{\"path\":\"value\",\"op\":\"equals\",\"expect\":1}]"));var cleanup=repo.createCase(spec("/cleanup","{}",null));
        var scenario=repo.saveScenario(null,new ApiScenario(null,"rows",List.of(step("issue",issue,false,null,null),step("cleanup",cleanup,true,null,null))));
        var first=runner.startDataBatch("rows",null,scenario.id(),new ApiDataSet.Input("JSON","[{\"value\":1},{\"value\":2}]"),0,2);waitBatch(first);
        var preview=runner.retryPreview(first.id());assertThat(preview.failedSteps()).isEqualTo(2);assertThat(preview.rows()).isEqualTo(2);assertThat(preview.wholeScenario()).isTrue();
        var results=waitBatch(runner.retryFailed(first.id(),"ORIGINAL",null,false));assertThat(results).hasSize(4);assertThat(results.get(0).reqBody()).contains("2");assertThat(results.get(2).metadata()).contains("\"dataIteration\":2");assertThat(cleanups).hasValue(6);
    }
    @Test void incrementalSummaryCursorNeverReturnsBodiesAndCanCatchUp() throws Exception {
        var c=repo.createCase(spec("/plain","{\"private\":\"fixture\"}",null));var b=runner.startBatch("pages",List.of(c.id()),0,5);waitBatch(b);
        var first=repo.executionPage(b.id(),null,2);assertThat(first.items()).hasSize(2);assertThat(first.hasMore()).isTrue();
        var next=repo.executionPage(b.id(),first.nextAfterId(),100);assertThat(next.items()).hasSize(3);assertThat(next.hasMore()).isFalse();
        assertThat(first.items()).allSatisfy(e->{assertThat(e.reqBody()).isNull();assertThat(e.respBody()).isNull();assertThat(e.reqHeaders()).isNull();});
        assertThat(runner.progress(b.id()).get("lastExecId")).isEqualTo(next.items().getLast().id());
        assertThatThrownBy(()->repo.executionPage(b.id(),null,101)).isInstanceOf(IllegalArgumentException.class);
    }
}
