package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 10k真实SQLite记录，验证AP18分页补齐和输出规模与大正文无关。 */
class ApiIncrementalScaleTest {
    @TempDir Path directory;
    @Test void tenThousandExecutionsCatchUpWithoutBodiesDuplicatesOrMetadataPayloads() throws Exception {
        var jdbc=new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:"+directory.resolve("scale.db")));
        var repo=new ApiRepository(jdbc);var batch=repo.createBatch("fictional-scale","REPEAT",List.of(1L),0,10000);
        repo.writeSnapshot(()->{jdbc.batchUpdate("INSERT INTO api_exec(batch_id,round_no,seq,case_name,method,url,verdict,elapsed_ms,req_body,resp_body,metadata,ts) VALUES(?,?,1,'fictional','GET','http://127.0.0.1/fictional','PASS',1,?,?,?,'2026-10-14T00:00:00Z')",new org.springframework.jdbc.core.BatchPreparedStatementSetter(){
            public int getBatchSize(){return 10000;}
            public void setValues(java.sql.PreparedStatement statement,int index)throws java.sql.SQLException{statement.setLong(1,batch.id());statement.setInt(2,index+1);statement.setString(3,"x".repeat(2048));statement.setString(4,"y".repeat(2048));statement.setString(5,index==0?"not-json":index==1?"{\"envId\":{\"hidden\":\"should-not-return\"},\"stepKey\":{\"hidden\":\"should-not-return\"}}":"{\"envId\":1,\"pollFinal\":true,\"cancelled\":false,\"dataRow\":{\"private\":\"should-not-return\"}}");}
        });return null;});
        var mapper=new ObjectMapper();var ids=new HashSet<Long>();Long cursor=null;int pages=0,min=Integer.MAX_VALUE,max=0;
        do {var page=repo.executionPage(batch.id(),cursor,50);pages++;assertThat(page.items()).hasSize(50);String payload=mapper.writeValueAsString(page);assertThat(payload).doesNotContain("should-not-return","xxx","yyy");
            min=Math.min(min,payload.length());max=Math.max(max,payload.length());
            for(var e:page.items()){assertThat(ids.add(e.id())).isTrue();assertThat(e.reqBody()).isNull();assertThat(e.respBody()).isNull();}
            if(pages==1){assertThat(page.items().getFirst().metadata()).isNull();assertThat(mapper.readTree(page.items().get(1).metadata()).path("envId").isNull()).isTrue();assertThat(mapper.readTree(page.items().get(2).metadata()).path("pollFinal").isBoolean()).isTrue();}
            cursor=page.nextAfterId();if(!page.hasMore())break;
        }while(pages<201);
        assertThat(pages).isEqualTo(200);assertThat(ids).hasSize(10000);assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_exec WHERE batch_id=?",Integer.class,batch.id())).isEqualTo(ids.size());
        assertThat(repo.executionPage(batch.id(),cursor,50).items()).isEmpty();assertThat(max).isLessThan(50000);assertThat(max-min).isLessThan(3000);
    }
}
