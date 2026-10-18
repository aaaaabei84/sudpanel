package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.sudpanel.api.*;
import com.sudpanel.web.PerfEvidenceController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 真实 REST 参数绑定→冻结环境→loopback HTTP→核验→独立 SQLite 持久历史；不创建手机对象。 */
class PerfSettlementMockIntegrationTest {
 @TempDir Path directory;
 @Test void actualServerStartRosterCorrectsEightCandidateFalseAlarmAndHistorySurvivesRestart() throws Exception {
  var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("fixture.db")));
  var perf=new PerfRepository(jdbc);var evidence=new PerfEvidenceRepository(jdbc);var api=new ApiRepository(jdbc);var runner=new ApiRunner(api,new CodeDictionary(api),"");
  var json=new ObjectMapper();String id="mock-round-012345678901234567890123456789";var response=new AtomicReference<String>();var sent=new AtomicInteger();var captured=new AtomicReference<String>();
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/query",exchange->{sent.incrementAndGet();captured.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] body=response.get().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
  try {
   long game=perf.upsertGame("mock game","mock");String host="http://127.0.0.1:"+server.getAddress().getPort();
   var query=api.createCase(new ApiSpec(null,"read only mock query","POST",host+"/query","{}","{\"game_round_id\":\"old\",\"mg_id\":\"{{mg_id}}\",\"app_id\":\"{{app_id}}\",\"room_id\":\"{{room_id}}\"}","ret_code","200","0",2000,false,true,null,null));
   var env=new ApiRequestEngine.Environment(9,"frozen mock env","2026-10-14T00:00:00Z",new ApiVariables.VarSet(List.of(),List.of(),List.of(new ApiVariables.Var("mg_id","old-game"),new ApiVariables.Var("app_id","old-app"),new ApiVariables.Var("room_id","old-room"))),new ApiSigner.Config(false,null,null,null,null),true,"");
   var start=new ApiSpec(999999L,"frozen control","POST",host+"/never-send","{}","{}",null,"200",null,1000,false,true,null,null);
   var plan=new PerfExecutionPlan(1,"START","2026-10-14T00:00:00Z","NORMAL",1,5,0L,null,null,List.of(game),Map.of(game,Map.of("roomId","mock-room","mgId","9223372036854775807","appId","9223372036854775806")),Map.of("开局",new PerfExecutionPlan.Request("开局",start,env)),Map.of(),Map.of(),Map.of(),1000,1,1,3);
   long run=perf.insertRun("mock-no-device","NORMAL",String.valueOf(game),1,5,0L);perf.savePlan(run,plan);long loop=perf.insertLoop(run,game,1);perf.markLoopPlan(loop,plan.hash());
   var window=new PerfLoopLogWindow("boundary");window.accept("boundary\nnew gameRoundId="+id);perf.markLoopRoundEvidence(loop,window);jdbc.update("UPDATE perf_loop SET start_time='2026-10-14T00:00:00Z',end_time='2026-10-14T00:01:00Z' WHERE id=?",loop);
   String candidates="{\"data\":{\"user_infos\":["+String.join(",",List.of("A","B","C","D","E","F","G","H").stream().map(uid->"{\"uid\":\""+uid+"\"}").toList())+"]}}";
   evidence.record(run,loop,game,1,"推人机",new ApiExec(null,null,9L,"push",1,1,"POST",host+"/never-send",200,"0","ok","PASS",null,1L,"{}","{}","{}",candidates,null,"2026-10-14T00:00:00Z",null,"{}"));
   perf.recordSettlement(run,loop,Map.of("status","MISMATCH","checkedAt","2026-10-14T00:00:00Z","checks",List.of(),"warnings",List.of("legacy-eight-vs-four")));
   var service=new PerfSettlementService(perf,evidence,api,runner);var controller=new PerfEvidenceController(evidence,perf);ReflectionTestUtils.setField(controller,"settlement",service);var mvc=MockMvcBuilders.standaloneSetup(controller).build();
   var source=new PerfParticipationEvidence.Rules("data.game_start","game_round_id","room_id","mg_id","players","uid",true,"","");
   var rules=new PerfSettlementService.Rules("data.game_settle","game_round_id","room_id","mg_id","results","uid","battle_end_at","SECONDS","ret_code",List.of("0"),List.of(),List.of(),"EXACT",0L,source);
   var players=List.of("H","B","F","D").stream().map(uid->Map.of("uid",uid)).toList();var report=new LinkedHashMap<String,Object>(Map.of("game_round_id",id,"room_id","mock-room","mg_id",Long.MAX_VALUE,"battle_end_at",1791936030,"players",players,"results",players));
   response.set(json.writeValueAsString(Map.of("ret_code",0,"data",Map.of("game_start",report,"game_settle",report))));
   String request=json.writeValueAsString(new PerfSettlementService.Request(query.id(),rules));
   mvc.perform(post("/api/perf/loops/"+loop+"/settlement-check").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCESS")).andExpect(jsonPath("$.participantEvidence.candidateCount").value(8)).andExpect(jsonPath("$.participantEvidence.actualParticipantCount").value(4)).andExpect(jsonPath("$.semanticsVersion").value(2));
   assertThat(json.readTree(captured.get()).path("game_round_id").asText()).isEqualTo(id);
   assertThat(json.readTree(captured.get()).path("mg_id").asText()).isEqualTo("9223372036854775807");
   assertThat(json.readTree(captured.get()).path("app_id").asText()).isEqualTo("9223372036854775806");
   assertThat(json.readTree(captured.get()).path("room_id").asText()).isEqualTo("mock-room");
   response.set(json.writeValueAsString(Map.of("ret_code",0,"data",Map.of("game_settle",report))));
   mvc.perform(post("/api/perf/loops/"+loop+"/settlement-check").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"));
   var wrong=new LinkedHashMap<String,Object>(report);wrong.put("game_round_id","different-round");response.set(json.writeValueAsString(Map.of("ret_code",0,"data",Map.of("game_start",wrong,"game_settle",report))));
   mvc.perform(post("/api/perf/loops/"+loop+"/settlement-check").contentType(MediaType.APPLICATION_JSON).content(request)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("MISMATCH"));
   assertThat(sent).hasValue(3);assertThat(evidence.runSteps(run)).hasSize(4);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_exec",Integer.class)).isZero();
   var reopened=new PerfRepository(new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("fixture.db"))));var history=reopened.settlementChecks(loop);assertThat(history).hasSize(4);assertThat(history.get(3)).containsEntry("status","MISMATCH").doesNotContainKey("semanticsVersion");assertThat(history.get(3).get("warnings")).isEqualTo(List.of("legacy-eight-vs-four"));
  } finally {server.stop(0);}
 }
}
