package com.sudpanel.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ApiStatisticsDrilldownTest {
    @TempDir Path directory;
    ApiRepository repo; ApiHistoryRepository history; JdbcTemplate jdbc; long batch; long target;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("stats.db")));
        repo=new ApiRepository(jdbc);history=new ApiHistoryRepository(jdbc,repo);
        batch=repo.createBatch("fixture","ONCE",List.of(1L),0,1).id();
        target=insert(batch,7L,"FAIL",409,"7","expected fixture failure","2026-10-15T12:00:01Z","{\"envId\":2,\"dataRowIndex\":3,\"dataIteration\":4}","[{\"path\":\"data.id\",\"op\":\"equals\",\"ok\":false}]");
        insert(batch,8L,"PASS",200,"0",null,"2026-10-15T12:01:00Z","{\"envId\":1,\"dataRowIndex\":4,\"dataIteration\":4}","[]");
        insert(batch+1,7L,"FAIL",409,"7","expected fixture failure","2026-10-15T12:00:01Z","{\"envId\":2,\"dataRowIndex\":3,\"dataIteration\":4}","[]");
    }
    long insert(Long batch,Long caseId,String verdict,Integer status,String code,String reason,String time,String metadata,String asserts) {
        return repo.insertExec(new ApiExec(null,batch,caseId,"fictional",1,1,"GET","http://example.invalid/x",status,code,"fixture",verdict,reason,10L,"{}","","","",null,time,asserts,metadata)).id();
    }
    ApiHistoryRepository.Page query(String dimension,String key,Long before,int limit) {
        return history.history(new ApiHistoryRepository.Query(before,limit,null,null,null,null,null,batch,null,null,null,dimension,key));
    }
    @Test void everyDimensionFindsTheExactBatchRecordAndOnlySummaryFields() {
        for(String[] pair:List.of(new String[]{"CASE","7"},new String[]{"ENV","2"},new String[]{"DATA_ROW","3:4"},new String[]{"MINUTE","2026-10-15T12:00:00Z"},new String[]{"HTTP_CODE","409"},new String[]{"BUSINESS_CODE","7"},new String[]{"REASON","expected fixture failure"},new String[]{"ASSERTION","data.id [equals]"})) {
            var result=query(pair[0],pair[1],null,10);
            assertThat(result.items()).extracting(ApiExec::id).containsExactly(target);
            assertThat(result.items().getFirst().respBody()).isNull();assertThat(result.items().getFirst().assertsResult()).isNull();
        }
    }
    @Test void unknownsBrokenAssertionsAndLiteralSqlDoNotBroadenTheSelection() {
        long unknown=insert(batch,null,"ERROR",null,null,null,"2026-10-15T12:02:00Z","bad","[42,\"bad\",null]");
        assertThat(query("ENV","unknown",null,10).items()).extracting(ApiExec::id).containsExactly(unknown);
        assertThat(query("CASE","unknown",null,10).items()).extracting(ApiExec::id).containsExactly(unknown);
        assertThat(query("DATA_ROW","unknown",null,10).items()).extracting(ApiExec::id).containsExactly(unknown);
        assertThat(query("HTTP_CODE","NO_RESPONSE",null,10).items()).extracting(ApiExec::id).containsExactly(unknown);
        assertThat(query("BUSINESS_CODE","UNRECORDED",null,10).items()).extracting(ApiExec::id).containsExactly(unknown);
        assertThat(query("ASSERTION","data.id [equals]",null,10).items()).extracting(ApiExec::id).containsExactly(target);
        assertThat(query("REASON","' OR 1=1 --",null,10).items()).isEmpty();
        assertThatThrownBy(()->query("UNSAFE","id",null,10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->query("DATA_ROW","-1:2",null,10)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void cursorKeepsStatisticsScopeWithoutDuplicates() {
        long other=insert(batch,7L,"FAIL",500,"9","other","2026-10-15T12:02:00Z",null,"bad");
        var first=query("CASE","7",null,1);assertThat(first.items()).extracting(ApiExec::id).containsExactly(other);
        var second=query("CASE","7",first.nextBeforeId(),1);assertThat(second.items()).extracting(ApiExec::id).containsExactly(target);assertThat(second.nextBeforeId()).isNull();
    }
}
