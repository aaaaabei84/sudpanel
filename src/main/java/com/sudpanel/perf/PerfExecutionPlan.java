package com.sudpanel.perf;

import com.sudpanel.api.ApiRequestEngine;
import com.sudpanel.api.ApiSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 本机持久快照包含鉴权，仅执行器可读；公开摘要不返回变量、密钥、坐标或原始模板。 */
public record PerfExecutionPlan(int version, String origin, String capturedAt, String mode,
        int loopCount, int sampleSeconds, long earlyTapDelayMs, Integer aiCount, Long coordinateGroupId,
        List<Long> gameIds, Map<Long, Map<String,Object>> games, Map<String, Request> requests,
        Map<String,String> settings, Map<String,String> resources, Map<String,List<Integer>> coordinates,
        long sampleIntervalMs, long tapSettleMs, long playingWaitMs, int maxConsecutiveInvalid) {
    private static final ObjectMapper JSON=com.fasterxml.jackson.databind.json.JsonMapper.builder().disable(com.fasterxml.jackson.databind.MapperFeature.AUTO_DETECT_IS_GETTERS).build();
    public record Request(String role, ApiSpec spec, ApiRequestEngine.Environment environment) {}
    public PerfExecutionPlan {
        if (version != 1) throw new IllegalArgumentException("不支持的性能冻结计划版本");
        gameIds = List.copyOf(gameIds);
        var copiedGames = new LinkedHashMap<Long,Map<String,Object>>();
        games.forEach((id, game) -> copiedGames.put(id, Collections.unmodifiableMap(new LinkedHashMap<>(game))));
        games = Collections.unmodifiableMap(copiedGames);
        requests = Collections.unmodifiableMap(new LinkedHashMap<>(requests));
        settings = Collections.unmodifiableMap(new LinkedHashMap<>(settings));
        resources = Collections.unmodifiableMap(new LinkedHashMap<>(resources));
        var copiedCoords = new LinkedHashMap<String,List<Integer>>();
        coordinates.forEach((key,value) -> copiedCoords.put(key,List.copyOf(value)));
        coordinates = Collections.unmodifiableMap(copiedCoords);
    }
    public String json() { try { return JSON.writeValueAsString(this); } catch(Exception e) { throw new IllegalStateException("性能快照序列化失败",e); } }
    public String hash() { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json().getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    public static PerfExecutionPlan parse(String text) { try { return JSON.readValue(text,PerfExecutionPlan.class); } catch(Exception e) { throw new IllegalStateException("性能冻结计划损坏，不能猜测配置续跑",e); } }
    public Map<String,Object> summary() {
        var result = new LinkedHashMap<String,Object>();
        result.put("state","FROZEN"); result.put("version",version); result.put("origin",origin);
        result.put("capturedAt",capturedAt); result.put("hash",hash()); result.put("mode",mode);
        var effectiveGames=new ArrayList<Map<String,Object>>();
        games.forEach((id,game)->{var row=new LinkedHashMap<String,Object>();row.put("gameId",id);row.put("name",game.get("name"));
            boolean override=game.get("bodyOverride")!=null&&!String.valueOf(game.get("bodyOverride")).isBlank();
            Object ais=game.get("aiCount")==null?aiCount:game.get("aiCount");Object humans=game.get("humanCount");
            row.put("rosterSource",override?"CUSTOM_BODY":ais!=null||humans!=null?"GENERATED":"CASE_BODY");
            row.put("humanCount",override?null:humans==null&&ais!=null?1:humans);row.put("aiCount",override?null:ais==null&&humans!=null?0:ais);
            row.put("gameSeconds","NORMAL".equals(mode)?5:game.get("gameSeconds")==null?sampleSeconds:game.get("gameSeconds"));effectiveGames.add(row);});
        result.put("effectiveGames",effectiveGames);
        result.put("gameIds",gameIds); result.put("coordinateGroupId",coordinateGroupId);
        result.put("settings",Map.of("trimBaseline",!"false".equalsIgnoreCase(settings.getOrDefault(PerfRepository.SETTING_PERF_TRIM_BASELINE,"")),
                "sampleIntervalMs",sampleIntervalMs,"maxConsecutiveInvalid",maxConsecutiveInvalid));
        result.put("caseSources",requests.values().stream().map(r -> Map.of("role",r.role(),"caseId",r.spec().id(),
                "name",r.spec().name()==null||r.spec().name().isBlank()?"未命名用例":r.spec().name(),"envId",r.environment().envId(),"envName",r.environment().envName())).toList());
        var limits=new LinkedHashMap<String,String>();settings.forEach((key,value)->{if(key.startsWith("judge."))limits.put(key.substring(6),value);});result.put("judgeLimits",limits);
        result.put("cookiePolicy","每个运行独立空Cookie会话，只在该运行中复用；显式续跑重新初始化空会话，不读取环境或其他运行的Cookie");
        return result;
    }
    public com.sudpanel.config.AppProperties judgeProperties(com.sudpanel.config.AppProperties current) {
        if(!settings.containsKey("judge.cpuThreshold")) return current;
        return new com.sudpanel.config.AppProperties(current.adbPath(),current.waitMs(),current.dbPath(),current.resourceIdFile(),
            loopCount,sampleSeconds,current.perfListSampleSeconds(),sampleIntervalMs,earlyTapDelayMs,tapSettleMs,playingWaitMs,
            Double.parseDouble(settings.get("judge.cpuThreshold")),Double.parseDouble(settings.get("judge.memGrowthPercent")),Double.parseDouble(settings.get("judge.memGrowthAbsMb")),
            Integer.parseInt(settings.get("judge.warmupLoops")),maxConsecutiveInvalid,current.notifyWebhook(),current.logcatTailLines(),
            Double.parseDouble(settings.get("judge.attrSharePercent")),Double.parseDouble(settings.get("judge.attrMinR2")),Double.parseDouble(settings.get("judge.reclaimableCacheMb")));
    }

    public int[] coordinate(String key) { var value=coordinates.get(key); return value==null?null:new int[]{value.get(0),value.get(1)}; }
}
