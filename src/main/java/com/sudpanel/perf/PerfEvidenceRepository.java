package com.sudpanel.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudpanel.api.ApiExec;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** 性能链路独立取证，不写手工接口历史；本机详情包含实际请求响应，不能当脱敏导出使用。 */
@Repository
@DependsOn("perfRepository")
public class PerfEvidenceRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    public PerfEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS perf_step_exec (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, run_id INTEGER NOT NULL, loop_id INTEGER,
                      game_id INTEGER, loop_no INTEGER NOT NULL, step TEXT NOT NULL,
                      exec_json TEXT NOT NULL, created_at TEXT NOT NULL
                    )
                    """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS ix_perf_step_loop ON perf_step_exec(loop_id,id)");
            jdbc.execute("CREATE INDEX IF NOT EXISTS ix_perf_step_run ON perf_step_exec(run_id,id)");
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS perf_loop_cleanup (
                      loop_id INTEGER PRIMARY KEY, run_id INTEGER NOT NULL, game_id INTEGER,
                      status TEXT NOT NULL, reason TEXT, evidence_missing INTEGER NOT NULL DEFAULT 0,
                      updated_at TEXT NOT NULL
                    )
                    """);
            // 使用触发器而非依赖每条新连接的 PRAGMA foreign_keys，兼容现有显式级联删除。
            jdbc.execute("""
                    CREATE TRIGGER IF NOT EXISTS tr_perf_evidence_delete_run AFTER DELETE ON perf_run BEGIN
                      DELETE FROM perf_step_exec WHERE run_id=OLD.id;
                      DELETE FROM perf_loop_cleanup WHERE run_id=OLD.id;
                    END
                    """);
            jdbc.execute("""
                    CREATE TRIGGER IF NOT EXISTS tr_perf_evidence_delete_game AFTER DELETE ON perf_game BEGIN
                      DELETE FROM perf_step_exec WHERE game_id=OLD.id;
                      DELETE FROM perf_loop_cleanup WHERE game_id=OLD.id;
                    END
                    """);
            jdbc.execute("""
                    CREATE TRIGGER IF NOT EXISTS tr_perf_evidence_delete_loop AFTER DELETE ON perf_loop BEGIN
                      DELETE FROM perf_loop_cleanup WHERE loop_id=OLD.id;
                    END
                    """);
        });
    }

    public long record(long runId, Long loopId, Long gameId, int loopNo, String step, ApiExec exec) {
        String serialized;
        try { serialized = json.writeValueAsString(exec); }
        catch (Exception error) { throw new IllegalStateException("接口步骤证据序列化失败", error); }
        return jdbc.execute((ConnectionCallback<Long>) connection -> {
            try (var insert = connection.prepareStatement("""
                    INSERT INTO perf_step_exec(run_id,loop_id,game_id,loop_no,step,exec_json,created_at)
                    VALUES(?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, runId); insert.setObject(2, loopId); insert.setObject(3, gameId);
                insert.setInt(4, loopNo); insert.setString(5, step); insert.setString(6, serialized);
                insert.setString(7, Instant.now().toString()); insert.executeUpdate();
                try (var keys = insert.getGeneratedKeys()) {
                    if (!keys.next()) throw new IllegalStateException("接口步骤证据未返回主键");
                    return keys.getLong(1);
                }
            }
        });
    }

    public void cleanup(long runId, long loopId, Long gameId, String status, String reason, boolean evidenceMissing) {
        if (!List.of("SUCCESS", "FAILED").contains(status)) throw new IllegalArgumentException("清房状态不合法");
        jdbc.update("""
                INSERT INTO perf_loop_cleanup(loop_id,run_id,game_id,status,reason,evidence_missing,updated_at)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(loop_id) DO UPDATE SET status=excluded.status,
                reason=excluded.reason,evidence_missing=excluded.evidence_missing,updated_at=excluded.updated_at
                """, loopId, runId, gameId, status, reason, evidenceMissing ? 1 : 0, Instant.now().toString());
    }

    public Map<String, Object> loopEvidence(long loopId) {
        List<Map<String, Object>> steps = steps("loop_id", loopId);
        List<Map<String, Object>> cleanups = jdbc.queryForList(
                "SELECT status,reason,evidence_missing,updated_at FROM perf_loop_cleanup WHERE loop_id=?", loopId);
        Map<String, Object> cleanup = new LinkedHashMap<>();
        cleanup.put("status", "NOT_RECORDED");
        cleanup.put("reason", "未记录清房证据，可能是旧记录、未执行收尾或取证写入失败");
        cleanup.put("evidenceMissing", null);
        if (!cleanups.isEmpty()) {
            Map<String, Object> row = cleanups.getFirst();
            cleanup.put("status", row.get("status")); cleanup.put("reason", row.get("reason"));
            cleanup.put("evidenceMissing", ((Number) row.get("evidence_missing")).intValue() != 0);
            cleanup.put("updatedAt", row.get("updated_at"));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loopId", loopId); result.put("recorded", !steps.isEmpty() || !cleanups.isEmpty());
        result.put("steps", steps); result.put("cleanup", cleanup);
        result.put("privacy", "本机取证详情保留实际请求响应，可能包含鉴权和业务数据；不是脱敏导出");
        return result;
    }

    public List<Map<String, Object>> runSteps(long runId) { return steps("run_id", runId); }

    private List<Map<String, Object>> steps(String column, long id) {
        return jdbc.query("SELECT * FROM perf_step_exec WHERE " + column + "=? ORDER BY id", (rs, index) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id")); row.put("runId", rs.getLong("run_id"));
            row.put("loopId", rs.getObject("loop_id")); row.put("gameId", rs.getObject("game_id"));
            row.put("loopNo", rs.getInt("loop_no")); row.put("step", rs.getString("step"));
            row.put("createdAt", rs.getString("created_at"));
            try {
                JsonNode exec = json.readTree(rs.getString("exec_json"));
                for (String field : List.of("verdict", "reason", "httpStatus", "elapsedMs", "ts"))
                    row.put(field, exec.hasNonNull(field) ? json.convertValue(exec.get(field), Object.class) : null);
                JsonNode meta=null;try{meta=json.readTree(exec.path("metadata").asText("{}"));}catch(Exception ignored){}
                if(meta!=null && meta.path("safeDisplay").path("reason").isTextual())row.put("reason",meta.path("safeDisplay").path("reason").asText());
                row.put("truncated", truncated(exec));
            } catch (Exception invalid) { row.put("evidenceUnreadable", true); }
            return row;
        }, id);
    }

    public void forEachStep(long runId,Long gameId,java.util.function.Consumer<Map<String,Object>> consumer) {
        jdbc.query("SELECT id,run_id,loop_id,game_id,loop_no,step,exec_json FROM perf_step_exec WHERE run_id=?"+(gameId==null?"":" AND game_id=?")+" ORDER BY id",(org.springframework.jdbc.core.RowCallbackHandler)rs->{
            var row=new LinkedHashMap<String,Object>();row.put("id",rs.getLong("id"));row.put("runId",rs.getLong("run_id"));row.put("loopId",rs.getObject("loop_id"));row.put("gameId",rs.getObject("game_id"));row.put("loopNo",rs.getInt("loop_no"));row.put("step",rs.getString("step"));
            try {var exec=json.readTree(rs.getString("exec_json"));for(String field:List.of("verdict","httpStatus","elapsedMs"))row.put(field,exec.hasNonNull(field)?json.convertValue(exec.get(field),Object.class):null);row.put("truncated",truncated(exec));}
            catch(Exception invalid){row.put("evidenceUnreadable",true);}consumer.accept(row);
        },gameId==null?new Object[]{runId}:new Object[]{runId,gameId});
    }

    public Map<String, Object> detail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM perf_step_exec WHERE id=?", id);
        if (rows.isEmpty()) throw new NoSuchElementException("接口步骤证据不存在");
        Map<String, Object> row = rows.getFirst();
        try {
            JsonNode exec = json.readTree((String) row.get("exec_json"));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("id", id); detail.put("step", row.get("step")); detail.put("exec", exec);
            detail.put("truncated", truncated(exec));
            detail.put("privacy", "本机实际报文，可能包含鉴权与业务数据；不是脱敏导出");
            return detail;
        } catch (Exception invalid) { throw new IllegalStateException("接口步骤证据无法读取", invalid); }
    }

    private boolean truncated(JsonNode exec) {
        try {
            JsonNode metadata = json.readTree(exec.path("metadata").asText("{}"));
            if (metadata != null && (metadata.path("requestBodyTruncated").asBoolean()
                    || metadata.path("responseBodyTruncated").asBoolean())) return true;
        } catch (Exception ignored) { }
        return exec.path("reqBody").asText("").contains("…（已截断，原文 ")
                || exec.path("respBody").asText("").contains("…（已截断，原文 ");
    }
}
