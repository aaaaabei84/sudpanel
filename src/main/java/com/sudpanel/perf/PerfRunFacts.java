package com.sudpanel.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudpanel.adb.AdbClient;
import com.sudpanel.api.ApiPlanVault;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/** 时间线仅接受类型化有限理由；可比条件含本机身份摘要，和冻结计划一样加密存储。 */
public final class PerfRunFacts {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final JdbcTemplate jdbc;
    public PerfRunFacts(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public enum Type { START, RESUME, STOP_REQUESTED, INTERRUPTED, WAIT_STARTED, WAIT_PROBE, WAIT_FINISHED, RECOVERY_STARTED, RECOVERY_ATTEMPT, RECOVERY_FINISHED, FINALIZED, CONDITIONS_CHANGED }
    public enum Reason { STARTED, USER_REQUEST, PROCESS_RESTART, DEVICE_DISCONNECTED, DEVICE_RECONNECTED, ENV_UNREACHABLE, ENV_REACHABLE, WAIT_TIMEOUT, STOPPED, RECOVERED, RECOVERY_FAILED, RECOVERY_REQUIRED, REQUEST_FAILED, COMPLETED, ERROR, CONDITIONS_DIFFER, CONDITIONS_UNVERIFIED }
    public static void initialize(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_run_event(id INTEGER PRIMARY KEY AUTOINCREMENT,run_id INTEGER NOT NULL,type TEXT NOT NULL,stage TEXT NOT NULL,game_ordinal INTEGER,loop_no INTEGER,created_at TEXT NOT NULL,reason TEXT NOT NULL,duration_ms INTEGER)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS ix_perf_event_run ON perf_run_event(run_id,id)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS perf_run_conditions(run_id INTEGER PRIMARY KEY,conditions_json TEXT NOT NULL,mixed INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TRIGGER IF NOT EXISTS tr_perf_facts_delete AFTER DELETE ON perf_run BEGIN DELETE FROM perf_run_event WHERE run_id=OLD.id; DELETE FROM perf_run_conditions WHERE run_id=OLD.id; END");
    }
    public void event(long runId,Type type,String stage,Integer gameOrdinal,Integer loopNo,Reason reason,Long durationMs) {
        if(!Set.of("RUN","DEVICE_WAIT","ENV_WAIT","RECOVERY","FINALIZE","USER").contains(stage)) throw new IllegalArgumentException("无效恢复阶段");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM perf_run_event WHERE run_id=?",Long.class,runId)>=10000) throw new IllegalStateException("恢复时间线达到10000条上限");
        jdbc.update("INSERT INTO perf_run_event(run_id,type,stage,game_ordinal,loop_no,created_at,reason,duration_ms) SELECT id,?,?,?,?,?,?,? FROM perf_run WHERE id=?",type.name(),stage,gameOrdinal,loopNo,Instant.now().toString(),reason.name(),durationMs==null?null:Math.max(0,durationMs),runId);
    }
    public Map<String,Object> timeline(long runId) {
        long count=jdbc.queryForObject("SELECT COUNT(*) FROM perf_run_event WHERE run_id=?",Long.class,runId);
        var items=jdbc.queryForList("SELECT id AS sequence,type,stage,game_ordinal AS gameOrdinal,loop_no AS loopNo,created_at AS createdAt,reason,duration_ms AS durationMs FROM perf_run_event WHERE run_id=? ORDER BY id LIMIT 1000",runId);
        return Map.of("state",count==0?"NOT_RECORDED":"RECORDED","items",items,"total",count,"truncated",count>1000,"limitReached",count>=10000);
    }
    public static Map<String,String> device(AdbClient adb,String serial,String pkg) {
        var values=new LinkedHashMap<String,String>();
        try{if(serial==null||adb.devices().stream().noneMatch(device->serial.equals(device.serial())&&"device".equals(device.status())))return values;}catch(Exception unavailable){return values;}
        var layout=PerfDeviceLayout.read(adb,serial);
        values.put("deviceIdentity",hash(serial==null?"":serial));
        put(values,"model",layout.model());
        try { var result=adb.shell(serial,"getprop ro.build.version.release; getprop ro.build.version.security_patch; getprop ro.build.fingerprint",10);
            if(result!=null&&result.ok()) { var lines=result.out().lines().toList();if(lines.size()>=3){put(values,"romRelease",lines.get(0));put(values,"romPatch",lines.get(1));if(!lines.get(2).isBlank())put(values,"romIdentity",hash(lines.get(2)));} }
            if(pkg!=null&&pkg.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {var app=adb.shell(serial,"dumpsys package "+pkg,10);if(app!=null&&app.ok()){var v=Pattern.compile("versionName=([^\\r\\n]+)").matcher(app.out());if(v.find())put(values,"appVersion",v.group(1));var c=Pattern.compile("versionCode=(\\d+)").matcher(app.out());if(c.find())put(values,"appCode",c.group(1));}} }
        catch(Exception ignored) { /* 缺字段保持未知。 */ }
        return values;
    }
    private static void put(Map<String,String> out,String key,String value){if(value!=null&&!value.isBlank()&&!value.trim().equalsIgnoreCase("unknown")&&!value.trim().equalsIgnoreCase("null")&&value.trim().length()<=120)out.put(key,value.trim());}
    public static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception failure){throw new IllegalStateException(failure);}}
    public void freeze(long runId,PerfExecutionPlan plan) {
        var values=new TreeMap<String,String>();plan.settings().forEach((key,value)->{if(key.startsWith("condition."))values.put(key.substring(10),value);});
        values.put("mode",plan.mode());values.put("loopCount",String.valueOf(plan.loopCount()));values.put("trimBaseline",plan.settings().getOrDefault(PerfRepository.SETTING_PERF_TRIM_BASELINE,"true"));values.put("warmupLoops",plan.settings().getOrDefault("judge.warmupLoops","UNKNOWN"));values.put("sampleSeconds",String.valueOf(plan.sampleSeconds()));values.put("sampleIntervalMs",String.valueOf(plan.sampleIntervalMs()));values.put("earlyTapDelayMs",String.valueOf(plan.earlyTapDelayMs()));
        // 只比较形状与本机摘要，公开接口不会回传名单、业务ID或身份摘要。
        var roster=new ArrayList<Map<String,Object>>();
        plan.games().forEach((id,game)->{var row=new TreeMap<String,Object>();for(String key:List.of("humanCount","aiCount","aiLevel","bodyOverride","gameSeconds","machineUid","mgId","appId"))row.put(key,game.get(key));roster.add(row);});
        try { var seed=new LinkedHashMap<String,Object>();seed.put("games",roster);var push=plan.requests().get("推人机");if(push!=null){seed.put("pushBody",push.spec().body());seed.put("environment",push.environment());}values.put("rosterIdentity",hash(JSON.writeValueAsString(seed)));values.put("rosterSource","FROZEN_CONFIGURATION");values.put("globalAiCount",String.valueOf(plan.aiCount()));
            jdbc.update("INSERT INTO perf_run_conditions(run_id,conditions_json) VALUES(?,?)",runId,new ApiPlanVault(jdbc).encrypt("perf-conditions",runId,JSON.writeValueAsString(values))); }
        catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new IllegalStateException("可比条件保存失败",failure);}
    }
    private Map<String,String> conditions(long runId) {
        var rows=jdbc.queryForList("SELECT conditions_json FROM perf_run_conditions WHERE run_id=?",String.class,runId);
        if(rows.isEmpty())return Map.of();
        try{return JSON.readValue(new ApiPlanVault(jdbc).decrypt("perf-conditions",runId,rows.getFirst()),Map.class);}catch(Exception failure){throw new IllegalStateException("可比条件无法读取，不能猜测历史条件",failure);}
    }
    public Map<String,Object> summary(long runId) {
        var values=conditions(runId);var safe=new LinkedHashMap<String,Object>();
        boolean known=List.of("deviceIdentity","model","romRelease","romIdentity","appVersion","appCode","mode","sampleSeconds","sampleIntervalMs","rosterIdentity").stream().allMatch(key->values.containsKey(key)&&!values.get(key).isBlank());
        var flags=jdbc.queryForList("SELECT mixed FROM perf_run_conditions WHERE run_id=?",Integer.class,runId);int flag=flags.isEmpty()?0:flags.getFirst();boolean mixed=flag!=0;
        safe.put("state",values.isEmpty()?"NOT_RECORDED":flag==1?"MIXED":flag==2?"UNKNOWN":known?"KNOWN":"UNKNOWN");safe.put("comparable",known&&!mixed);
        for(String key:List.of("model","romRelease","romPatch","appVersion","appCode","mode","sampleSeconds","sampleIntervalMs","loopCount","trimBaseline","warmupLoops","earlyTapDelayMs","globalAiCount"))safe.put(key,values.get(key));
        safe.put("message",values.isEmpty()?"旧运行没有可比条件，不反推":flag==1?"续跑设备或应用条件已变化，不能作为同条件趋势":flag==2?"续跑条件未能完整读取，不能确认可比":known?"已冻结配置条件；名单与设备身份仅在本机比较，实际参赛完整性另行核验":"条件字段缺失，不能确认可比");
        return safe;
    }
    public Map<String,Object> compare(long reference,long other) {
        var left=conditions(reference);var right=conditions(other);var fields=new TreeSet<String>();fields.addAll(left.keySet());fields.addAll(right.keySet());
        var differences=fields.stream().filter(key->!Objects.equals(left.get(key),right.get(key))).toList();
        boolean comparable=Boolean.TRUE.equals(summary(reference).get("comparable"))&&Boolean.TRUE.equals(summary(other).get("comparable"))&&differences.isEmpty();
        return Map.of("comparable",comparable,"differences",differences,"reference",summary(reference),"candidate",summary(other),"message",comparable?"可比条件一致":"存在差异或未知条件，不合并为同条件趋势");
    }
    public Map<String,Object> trend(PerfRepository repo,long reference,long gameId,boolean onlyComparable) {
        var candidates=jdbc.queryForList("SELECT r.id,AVG(l.pss_avg_kb) AS pssAvgKb,AVG(l.cpu_avg) AS cpuAvg,COUNT(l.id) AS okLoops FROM perf_run r JOIN perf_loop l ON l.run_id=r.id WHERE l.game_id=? AND l.status='OK' GROUP BY r.id ORDER BY r.id DESC LIMIT 100",gameId);
        var items=new ArrayList<Map<String,Object>>();int excluded=0;
        for(var candidate:candidates){long id=((Number)candidate.get("id")).longValue();var comparison=compare(reference,id);if(onlyComparable&&!Boolean.TRUE.equals(comparison.get("comparable"))){excluded++;continue;}var item=new LinkedHashMap<String,Object>(candidate);item.put("comparable",comparison.get("comparable"));item.put("differences",comparison.get("differences"));item.put("conditionsState",summary(id).get("state"));items.add(item);}
        return Map.of("items",items,"excluded",excluded,"limit",100,"comparableOnly",onlyComparable,"message","趋势仅取各运行有效圈均值，不改变机器判据；未知或混合条件不能作为同条件比较");
    }
    public boolean changedDevice(long runId,Map<String,String> current) {
        var original=conditions(runId);if(original.isEmpty())return false;
        boolean changed=current.entrySet().stream().anyMatch(e->!Objects.equals(original.get(e.getKey()),e.getValue()));
        boolean unknown=List.of("deviceIdentity","model","romRelease","romIdentity","appVersion","appCode").stream().anyMatch(key->!current.containsKey(key));
        if(changed)jdbc.update("UPDATE perf_run_conditions SET mixed=1 WHERE run_id=?",runId);
        else if(unknown)jdbc.update("UPDATE perf_run_conditions SET mixed=2 WHERE run_id=? AND mixed=0",runId);
        return changed||unknown;
    }
}
