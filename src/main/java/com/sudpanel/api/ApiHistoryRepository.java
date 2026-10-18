package com.sudpanel.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 历史列表只读摘要列；id游标稳定分页，明细另取，避免一次加载全部报文。 */
@Repository
public class ApiHistoryRepository {
    private final JdbcTemplate jdbc;
    private final ApiRepository repo;
    private final ApiReportPolicy reportPolicy;
    private static final String ENV_ID = safeMetadata("envId", true);
    private static final String ENV_NAME = safeMetadata("envName", false);
    private static final String SAFE_METADATA = "json_object('envId'," + ENV_ID + ",'envName'," + ENV_NAME
            + ",'scenarioId'," + safeMetadata("scenarioId", true) + ",'scenarioName'," + safeMetadata("scenarioName", false)
            + ",'stepKey'," + safeMetadata("stepKey", false) + ",'phase'," + safeMetadata("phase", false)
            + ",'dataRowIndex'," + safeMetadata("dataRowIndex", true) + ",'dataIteration'," + safeMetadata("dataIteration", true) + ")";
    private static String safeMetadata(String key, boolean integer) {
        String value = "json_extract(metadata,'$." + key + "')";
        return "CASE WHEN json_valid(metadata) THEN CASE WHEN typeof(" + value + ")='" + (integer ? "integer" : "text") + "'"
                + (integer ? " AND " + value + ">=0" : "") + " THEN " + value + " END END";
    }
    public record Query(Long beforeId, Integer limit, String q, String verdict, String method, Long caseId,
                        Long envId, Long batchId, String from, String to, Boolean adhoc, String statsDimension, String statsKey) {
        public Query(Long beforeId,Integer limit,String q,String verdict,String method,Long caseId,Long envId,Long batchId,String from,String to,Boolean adhoc) {
            this(beforeId,limit,q,verdict,method,caseId,envId,batchId,from,to,adhoc,null,null);
        }
    }
    public record Page(List<ApiExec> items, Long nextBeforeId) {}
    public record ReasonCount(String reason,long count,String display) { public ReasonCount(String reason,long count){this(reason,count,null);} }
    public record GroupStats(String key, String name, long sampleCount, long totalCount, long passCount, long failCount,
                             long errorCount, long skipCount, Long minMs, Long p50Ms, Long p90Ms, Long p95Ms, Long p99Ms, Long maxMs) {}
    public record Stats(long sampleCount, long totalCount, long passCount, long failCount, long errorCount, long skipCount,
                        Long minMs, Long p50Ms, Long p90Ms, Long p95Ms, Long p99Ms, Long maxMs,
                        List<GroupStats> byCase, List<GroupStats> byEnv, List<ReasonCount> reasons,
                        List<GroupStats> byDataRow, List<ReasonCount> httpCodes, List<ReasonCount> businessCodes,
                        List<ReasonCount> assertionFailures, List<GroupStats> trend) {}

    public ApiHistoryRepository(JdbcTemplate jdbc, ApiRepository repo) {
        this.jdbc = jdbc; this.repo = repo; this.reportPolicy = new ApiReportPolicy(jdbc);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_api_exec_history_case ON api_exec(case_id,id DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_api_exec_history_verdict ON api_exec(verdict,id DESC)");
    }

    public Page history(Query query) {
        var q = query == null ? new Query(null, null, null, null, null, null, null, null, null, null, null) : query;
        int limit = q.limit() == null ? 30 : q.limit();
        if (limit < 1 || limit > 100) throw bad("历史每页数量为1至100");
        for (Long id : new Long[] {q.beforeId(), q.caseId(), q.envId(), q.batchId()}) if (id != null && id <= 0) throw bad("历史筛选ID必须为正整数");
        if (Boolean.TRUE.equals(q.adhoc()) && q.batchId() != null) throw bad("单条试跑筛选不能同时指定批次");
        StringBuilder sql = new StringBuilder("""
                SELECT id,batch_id,case_id,CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.caseName')='text' THEN json_extract(metadata,'$.safeDisplay.caseName') ELSE case_name END ELSE case_name END AS case_name,round_no,seq,method,CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.url')='text' THEN json_extract(metadata,'$.safeDisplay.url') ELSE url END ELSE url END AS url,http_status,CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.respCode')='text' THEN json_extract(metadata,'$.safeDisplay.respCode') ELSE resp_code END ELSE resp_code END AS resp_code,
                    CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.codeMeaning')='text' THEN json_extract(metadata,'$.safeDisplay.codeMeaning') ELSE code_meaning END ELSE code_meaning END AS code_meaning,verdict,CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.reason')='text' THEN json_extract(metadata,'$.safeDisplay.reason') ELSE reason END ELSE reason END AS reason,elapsed_ms,CASE WHEN json_valid(metadata) THEN CASE WHEN json_type(metadata,'$.safeDisplay.error')='text' THEN json_extract(metadata,'$.safeDisplay.error') ELSE error END ELSE error END AS error,ts,
                """ + SAFE_METADATA + " AS safe_metadata FROM api_exec WHERE 1=1");
        var args = new ArrayList<Object>();
        if (q.beforeId() != null) add(sql, args, "id < ?", q.beforeId());
        if (q.caseId() != null) add(sql, args, "case_id = ?", q.caseId());
        if (q.envId() != null) add(sql, args, ENV_ID + " = ?", q.envId());
        if (q.batchId() != null) add(sql, args, "batch_id = ?", q.batchId());
        if (q.adhoc() != null) sql.append(Boolean.TRUE.equals(q.adhoc()) ? " AND batch_id IS NULL" : " AND batch_id IS NOT NULL");
        statsFilter(q, sql, args);
        if (q.verdict() != null && !q.verdict().isBlank()) {
            String value = q.verdict().toUpperCase(Locale.ROOT);
            if (!Set.of("PASS", "FAIL", "ERROR", "SKIP").contains(value)) throw bad("历史判定筛选无效");
            add(sql, args, "verdict = ?", value);
        }
        if (q.method() != null && !q.method().isBlank()) {
            String value = q.method().toUpperCase(Locale.ROOT);
            if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(value)) throw bad("历史方法筛选无效");
            add(sql, args, "UPPER(method) = ?", value);
        }
        Instant from = instant(q.from()), to = instant(q.to());
        if (from != null && to != null && from.isAfter(to)) throw bad("历史开始时间不能晚于结束时间");
        if (from != null) add(sql, args, "julianday(ts) >= julianday(?)", from.toString());
        if (to != null) add(sql, args, "julianday(ts) <= julianday(?)", to.toString());
        if (q.q() != null && !q.q().isBlank()) {
            if (q.q().length() > 200) throw bad("搜索关键字最多200字符");
            String value = "%" + q.q().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql.append(" AND (LOWER(COALESCE(case_name,'')) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(url,'')) LIKE ? ESCAPE '\\' OR LOWER(COALESCE(reason,'')) LIKE ? ESCAPE '\\')");
            args.add(value); args.add(value); args.add(value);
        }
        sql.append(" ORDER BY id DESC LIMIT ?"); args.add(limit + 1);
        var results = jdbc.query(sql.toString(), (rs, i) -> summary(rs), args.toArray());
        boolean more = results.size() > limit;
        var page = List.copyOf(more ? results.subList(0, limit) : results);
        return new Page(page, more ? page.getLast().id() : null);
    }

    public ApiExec detail(long id) { if (id <= 0) throw bad("执行记录ID必须为正整数"); return repo.getExec(id); }

    private static void statsFilter(Query query, StringBuilder sql, List<Object> args) {
        String dimension=query.statsDimension(), key=query.statsKey();
        if(dimension==null && key==null)return;
        if(dimension==null || key==null || key.length()>4096 || query.batchId()==null)
            throw bad("统计跳转须指定批次、维度和最多4096字符的精确值");
        switch(dimension) {
            case "CASE", "ENV" -> {
                String column=dimension.equals("CASE")?"case_id":ENV_ID;
                if(key.equals("unknown"))sql.append(" AND ").append(column).append(" IS NULL");
                else { long id;try{id=Long.parseLong(key);}catch(Exception e){throw bad("统计分组ID无效");}if(id<=0)throw bad("统计分组ID无效");add(sql,args,column+" = ?",id); }
            }
            case "DATA_ROW" -> {
                String row=safeMetadata("dataRowIndex",true),iteration=safeMetadata("dataIteration",true);
                if(key.equals("unknown")){sql.append(" AND ").append(row).append(" IS NULL");break;}
                String[] parts=key.split(":",-1);if(parts.length!=2)throw bad("数据行分组无效");
                for(int i=0;i<2;i++) {
                    String column=i==0?row:iteration;
                    if(parts[i].equals("unknown"))sql.append(" AND ").append(column).append(" IS NULL");
                    else{long index;try{index=Long.parseLong(parts[i]);}catch(Exception e){throw bad("数据行索引无效");}if(index<0)throw bad("数据行索引无效");add(sql,args,column+" = ?",index);}
                }
            }
            case "MINUTE" -> {
                Instant start=instant(key);if(start==null)throw bad("分钟分组时间无效");
                add(sql,args,"julianday(ts) >= julianday(?)",start.toString());add(sql,args,"julianday(ts) < julianday(?)",start.plusSeconds(60).toString());
            }
            case "HTTP_CODE" -> {
                sql.append(" AND verdict IN ('FAIL','ERROR')");
                if(key.equals("NO_RESPONSE"))sql.append(" AND http_status IS NULL");
                else{int code;try{code=Integer.parseInt(key);}catch(Exception e){throw bad("HTTP码分组无效");}add(sql,args,"http_status = ?",code);}
            }
            case "BUSINESS_CODE" -> {
                sql.append(" AND verdict IN ('FAIL','ERROR')");
                add(sql,args,"COALESCE(resp_code,'UNRECORDED') = ?",key);
            }
            case "REASON" -> {
                sql.append(" AND verdict IN ('FAIL','ERROR')");add(sql,args,"COALESCE(reason,error,'未记录原因') = ?",key);
            }
            case "ASSERTION" -> {
                int split=key.lastIndexOf(" [");if(split<0 || !key.endsWith("]"))throw bad("断言分组无效");
                String path=key.substring(0,split),op=key.substring(split+2,key.length()-1);
                sql.append(" AND verdict IN ('FAIL','ERROR') AND EXISTS (SELECT 1 FROM json_each(CASE WHEN json_valid(asserts_result) THEN CASE WHEN json_type(asserts_result)='array' THEN asserts_result ELSE '[]' END ELSE '[]' END) rule WHERE CASE WHEN rule.type='object' THEN json_type(rule.value,'$.ok')='false' AND COALESCE(json_extract(rule.value,'$.path'),'')=? AND COALESCE(json_extract(rule.value,'$.op'),'')=? ELSE 0 END)");
                args.add(path);args.add(op);
            }
            default -> throw bad("统计跳转维度无效");
        }
    }

    public ApiHistoryCompare.Compared compare(long left,long right,List<String> ignore,boolean includeSecrets){return ApiPrivacy.compare(detail(left),detail(right),ignore,jdbc,includeSecrets);}

    public ApiHistoryDraft.Draft draft(long id) { return ApiHistoryDraft.restore(detail(id), repo); }

    public Map<String, Object> exportJson(long batchId, boolean includeSecrets) {
        return repo.readSnapshot(() -> ApiHistoryExport.json(repo.getBatch(batchId), stats(batchId), reportExecs(batchId, includeSecrets), includeSecrets, reportPolicy.get().sensitiveJsonPaths()));
    }
    public String exportJunit(long batchId, boolean includeSecrets) {
        return repo.readSnapshot(() -> ApiHistoryExport.junit(repo.getBatch(batchId), reportExecs(batchId, includeSecrets), includeSecrets, reportPolicy.get().sensitiveJsonPaths()));
    }
    private List<ApiExec> reportExecs(long batchId, boolean includeSecrets) {
        int limit = includeSecrets ? 2000 : 10000;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM api_exec WHERE batch_id=?", Long.class, batchId);
        if (count != null && count > limit) throw bad("此批次超过报告上限" + limit + "条，请缩小回归批次；历史分页仍可查看全部记录");
        return repo.listExecsByBatch(batchId).stream().map(e->includeSecrets?e:ApiPrivacy.exportInput(e,jdbc)).toList();
    }

    private static ApiExec summary(ResultSet rs) throws SQLException {
        return new ApiExec(rs.getLong("id"), nullableLong(rs, "batch_id"), nullableLong(rs, "case_id"), rs.getString("case_name"),
                nullableInt(rs, "round_no"), nullableInt(rs, "seq"), rs.getString("method"), rs.getString("url"), nullableInt(rs, "http_status"),
                rs.getString("resp_code"), rs.getString("code_meaning"), rs.getString("verdict"), rs.getString("reason"), nullableLong(rs, "elapsed_ms"),
                null, null, null, null, rs.getString("error"), rs.getString("ts"), null, rs.getString("safe_metadata"));
    }

    /** 仅取延时和分组所需列，不读取报文；响应采样明确排除ERROR/SKIP/无响应/非法负耗时。 */
    public Stats stats(long batchId) {
        repo.getBatch(batchId);
        return repo.readSnapshot(() -> {
            var total = new Accumulator("all", "全部");
            var cases = new LinkedHashMap<String, Accumulator>();
            var envs = new LinkedHashMap<String, Accumulator>();
            var rows = new LinkedHashMap<String, Accumulator>(); var trend = new LinkedHashMap<String, Accumulator>();
            var http = new LinkedHashMap<String,Long>();var business = new LinkedHashMap<String,Long>();var assertions = new LinkedHashMap<String,Long>();
            jdbc.query("SELECT case_id,case_name,verdict,http_status,elapsed_ms,resp_code,asserts_result,ts," + ENV_ID + " AS env_id," + ENV_NAME + " AS env_name," + safeMetadata("dataRowIndex",true) + " AS data_row," + safeMetadata("dataIteration",true) + " AS data_iteration FROM api_exec WHERE batch_id=? ORDER BY id", rs -> {
                Long caseId = nullableLong(rs, "case_id"), envId = nullableLong(rs, "env_id"), elapsed = nullableLong(rs, "elapsed_ms");
                String caseKey = caseId == null ? "unknown" : caseId.toString(), envKey = envId == null ? "unknown" : envId.toString();
                String caseName = rs.getString("case_name"), envName = rs.getString("env_name"), verdict = rs.getString("verdict");
                boolean sample = rs.getObject("http_status") != null && Set.of("PASS", "FAIL").contains(verdict == null ? "" : verdict) && elapsed != null && elapsed >= 0;
                total.add(verdict, sample ? elapsed : null);
                cases.computeIfAbsent(caseKey, k -> new Accumulator(k, caseName == null ? "未记录用例" : caseName)).add(verdict, sample ? elapsed : null);
                envs.computeIfAbsent(envKey, k -> new Accumulator(k, envName == null ? "未记录环境" : envName)).add(verdict, sample ? elapsed : null);
                Long row = nullableLong(rs,"data_row"), iteration=nullableLong(rs,"data_iteration");
                String dataKey=row==null?"unknown":row+":"+(iteration==null?"unknown":iteration);
                rows.computeIfAbsent(dataKey,k->new Accumulator(k,row==null?"未记录数据行":"数据行"+row+" / 第"+iteration+"轮")).add(verdict,sample?elapsed:null);
                String ts=rs.getString("ts"), bucket;try{bucket=Instant.parse(ts).truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString();}catch(Exception invalidTime){bucket="unknown";}
                trend.computeIfAbsent(bucket,k->new Accumulator(k,k)).add(verdict,sample?elapsed:null);
                if("FAIL".equals(verdict)||"ERROR".equals(verdict)) {
                    http.merge(rs.getObject("http_status")==null?"NO_RESPONSE":rs.getString("http_status"),1L,Long::sum);
                    business.merge(rs.getString("resp_code")==null?"UNRECORDED":rs.getString("resp_code"),1L,Long::sum);
                    var result=ApiHistoryCompare.parse(rs.getString("asserts_result"));
                    if(result!=null&&result.isArray())for(var assertion:result)if(assertion.path("ok").isBoolean()&&!assertion.path("ok").asBoolean())assertions.merge(assertion.path("path").asText("")+" ["+assertion.path("op").asText("")+"]",1L,Long::sum);
                }
            }, batchId);
            List<ReasonCount> reasons = jdbc.query("""
                    SELECT COALESCE(NULLIF(reason,''),NULLIF(error,''),'未记录原因') AS cause,COUNT(*) AS n, MIN(CASE WHEN json_valid(metadata) THEN COALESCE(NULLIF(json_extract(metadata,'$.safeDisplay.reason'),''),NULLIF(json_extract(metadata,'$.safeDisplay.error'),''),COALESCE(NULLIF(reason,''),NULLIF(error,''),'未记录原因')) ELSE COALESCE(NULLIF(reason,''),NULLIF(error,''),'未记录原因') END) AS display
                    FROM api_exec WHERE batch_id=? AND verdict IN ('FAIL','ERROR')
                    GROUP BY cause ORDER BY n DESC,cause LIMIT 10
                    """, (rs, i) -> new ReasonCount(rs.getString("cause"),rs.getLong("n"),rs.getString("display")), batchId);
            GroupStats all = total.finish();
            return new Stats(all.sampleCount(), all.totalCount(), all.passCount(), all.failCount(), all.errorCount(), all.skipCount(),
                    all.minMs(), all.p50Ms(), all.p90Ms(), all.p95Ms(), all.p99Ms(), all.maxMs(),
                    cases.values().stream().map(Accumulator::finish).toList(), envs.values().stream().map(Accumulator::finish).toList(), reasons,
                    rows.values().stream().map(Accumulator::finish).toList(), top(http),top(business),top(assertions),trend.values().stream().sorted(java.util.Comparator.comparing(a->a.key)).map(Accumulator::finish).toList());
        });
    }

    private static final class Accumulator {
        final String key, name;
        final List<Long> samples = new ArrayList<>();
        long total, pass, fail, error, skip;
        Accumulator(String key, String name) { this.key = key; this.name = name; }
        void add(String verdict, Long elapsed) {
            total++; if ("PASS".equals(verdict)) pass++; else if ("FAIL".equals(verdict)) fail++; else if ("ERROR".equals(verdict)) error++; else if ("SKIP".equals(verdict)) skip++;
            if (elapsed != null) samples.add(elapsed);
        }
        GroupStats finish() {
            samples.sort(Long::compareTo);
            return new GroupStats(key, name, samples.size(), total, pass, fail, error, skip, samples.isEmpty() ? null : samples.getFirst(),
                    percentile(.50), percentile(.90), percentile(.95), percentile(.99), samples.isEmpty() ? null : samples.getLast());
        }
        Long percentile(double p) { return samples.isEmpty() ? null : samples.get((int) Math.ceil(samples.size() * p) - 1); }
    }

    private static List<ReasonCount> top(Map<String,Long> counts){return counts.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).limit(10).map(e->new ReasonCount(e.getKey(),e.getValue())).toList();}

    private static void add(StringBuilder sql, List<Object> args, String clause, Object value) { sql.append(" AND ").append(clause); args.add(value); }
    private static Instant instant(String value) { if (value == null || value.isBlank()) return null; try { return Instant.parse(value); } catch (Exception e) { throw bad("时间筛选需为带时区的 ISO8601 时间"); } }
    private static Long nullableLong(ResultSet rs, String name) throws SQLException { long value = rs.getLong(name); return rs.wasNull() ? null : value; }
    private static Integer nullableInt(ResultSet rs, String name) throws SQLException { int value = rs.getInt(name); return rs.wasNull() ? null : value; }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
