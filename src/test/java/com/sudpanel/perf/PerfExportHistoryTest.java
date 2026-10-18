package com.sudpanel.perf;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PerfExportHistoryTest {
 @TempDir Path dir;JdbcTemplate jdbc;PerfRepository repo;PerfEvidenceRepository evidence;PerfExportService exports;long game,run,loop;
 @BeforeEach void setup(){jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("perf.db")));repo=new PerfRepository(jdbc);evidence=new PerfEvidenceRepository(jdbc);exports=new PerfExportService(repo,evidence);game=repo.upsertGame("fictional","fictional");run=repo.insertRun("private-serial","NORMAL",String.valueOf(game),1,5,1L);loop=repo.insertLoop(run,game,1);}
 @Test void cursorFiltersDoNotDuplicateWhenNewRunsArriveAndActiveWaitsRemainFindable(){
  repo.readExportSnapshot(()->{for(int i=0;i<1000;i++){long id=repo.insertRun("fake",i%2==0?"NORMAL":"AI_ROOM",String.valueOf(game),1,5,1L);repo.finishRun(id,i%3==0?"WAIT_DEVICE":"COMPLETED");}});
  var filter=new PerfRepository.HistoryFilter(game,"NORMAL",null,null,null,null);var first=repo.history(filter,null,37);assertThat(first.items()).hasSize(37);assertThat(first.hasMore()).isTrue();
  long fresh=repo.insertRun("fake","NORMAL",String.valueOf(game),1,5,1L);var second=repo.history(filter,first.nextCursor(),37);
  Set<Object> seen=new HashSet<>();first.items().forEach(row->seen.add(row.get("id")));assertThat(second.items()).allMatch(row->!seen.contains(row.get("id"))&&!Objects.equals(row.get("id"),fresh));
  assertThat(repo.activeRuns()).anyMatch(row->"WAIT_DEVICE".equals(row.get("status")));
  assertThatThrownBy(()->repo.history(new PerfRepository.HistoryFilter(game,"AI_ROOM",null,null,null,null),first.nextCursor(),37)).hasMessageContaining("筛选");
 }
 @Test void cleanupFiltersAreIndependentAndUnknownIsExplicit(){
  evidence.cleanup(run,loop,game,"FAILED","Bearer secret-should-not-export",true);
  assertThat(repo.loopsOf(run,game,"FAILED")).hasSize(1);assertThat(repo.getLoop(loop)).containsEntry("cleanupStatus","FAILED");
  assertThat(repo.history(new PerfRepository.HistoryFilter(null,null,null,null,null,"FAILED"),null,10).items()).hasSize(1);
  long unknown=repo.insertLoop(run,game,2);assertThat(repo.loopsOf(run,game,"NOT_RECORDED")).extracting(row->row.get("id")).containsExactly(unknown);
 }
 @Test void samplesStreamWithBlankMissingCellsAndZipContainsNoLocalSecrets() throws Exception {
  jdbc.update("INSERT INTO perf_sample(loop_id,seq,pss_kb,ts) VALUES(?,?,?,?)",loop,0,123,"2026-10-14T00:00:00Z");
  jdbc.update("INSERT INTO perf_sample(loop_id,seq,pss_kb,ts) VALUES(?,?,?,?)",loop,1,null,"2026-10-14T00:00:01Z");
  evidence.record(run,loop,game,1,"开局",PerfEvidenceRepositoryTest.result("PASS","{}"));
  var output=new ByteArrayOutputStream();exports.samples(run,game,loop,output);String csv=output.toString(StandardCharsets.UTF_8);assertThat(csv.lines().count()).isEqualTo(3);assertThat(csv).contains(",1,2026-10-14T00:00:01Z,,");
  var zipped=new ByteArrayOutputStream();exports.zip(run,game,zipped);Map<String,String> files=new LinkedHashMap<>();try(var input=new ZipInputStream(new ByteArrayInputStream(zipped.toByteArray()),StandardCharsets.UTF_8)){for(var entry=input.getNextEntry();entry!=null;entry=input.getNextEntry())files.put(entry.getName(),new String(input.readAllBytes(),StandardCharsets.UTF_8));}
  assertThat(files.keySet()).containsExactly("summary.csv","samples.csv","steps.csv","sampling-quality.csv","metadata.json");assertThat(files.toString()).doesNotContain("private-serial","Authorization","fictional-reason","game_round_id\":");assertThat(files.get("metadata.json")).contains("sampleCount\" : 2","LEGACY_UNKNOWN");
  assertThatThrownBy(()->exports.samples(run,game,999L,new ByteArrayOutputStream())).hasMessageContaining("圈不属于");
 }
 @Test void htmlAndCircleCsvIncludeCleanupAndStepsWithoutActualMessages() {
  evidence.record(run,loop,game,1,"开局",PerfEvidenceRepositoryTest.result("PASS","{}"));evidence.cleanup(run,loop,game,"FAILED","Bearer secret-should-not-export",true);
  jdbc.update("UPDATE perf_loop SET invalid_reason='access_token=must-hide' WHERE id=?",loop);
  var reports=new PerfReportService(repo,org.mockito.Mockito.mock(com.sudpanel.config.AppProperties.class),evidence);
  String html=reports.html(run,game),csv=reports.csv(run,game);assertThat(html).contains("接口步骤与清房证据","开局:PASS","FAILED").doesNotContain("private-serial","Authorization","fictional-reason","must-hide","secret-should-not-export");
  assertThat(csv).contains("清房状态","接口步骤摘要","开局:PASS","FAILED").doesNotContain("Authorization","fictional-reason","must-hide");
 }
 @Test void hundredThousandSamplesAreWrittenIncrementallyWithoutBuildingAnOutputList() throws Exception {largeStream(100_000);}
 @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="perfMillion",matches="true") void millionSamplesStream() throws Exception {largeStream(1_000_000);}
 private void largeStream(int count) throws Exception {
  jdbc.update("WITH RECURSIVE n(x) AS (SELECT 0 UNION ALL SELECT x+1 FROM n WHERE x<?) INSERT INTO perf_sample(loop_id,seq,pss_kb,ts) SELECT ?,x,123,'2026-10-14T00:00:00Z' FROM n",count-1,loop);
  class Counter extends OutputStream {long lines,bytes;@Override public void write(int b){bytes++;if(b=='\n')lines++;}@Override public void write(byte[] b,int off,int len){bytes+=len;for(int i=off;i<off+len;i++)if(b[i]=='\n')lines++;}}
  var output=new Counter();exports.samples(run,game,null,output);assertThat(output.lines).isEqualTo(count+1L);assertThat(repo.sampleCount(run,game,null)).isEqualTo(count);assertThat(output.bytes).isGreaterThan(count*30L);
 }
}
