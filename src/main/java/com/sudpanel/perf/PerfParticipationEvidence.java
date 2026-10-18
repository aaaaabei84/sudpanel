package com.sudpanel.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.sudpanel.api.ApiBodyAssert;
import java.util.*;

/** 服务端开局报告与结算报告的一致性证据；推送请求仅提供候选，不能证明实际入局。 */
public final class PerfParticipationEvidence {
    private static final ObjectMapper STRICT_JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    public record Rules(String startRecordPath, String roundIdPath, String roomIdPath, String gameIdPath,
                 String playersPath, String playerIdPath, Boolean completeRoster,
                 String capacityPath, String capacitySource) {}
    record Roster(Set<String> ids, String state, String reason) {
        boolean valid() { return "COMPLETE".equals(state); }
    }
    record Evaluation(List<Map<String,Object>> checks, List<String> warnings, Map<String,Object> summary) {}

    static Evaluation evaluate(String response, String settlementBody, PerfSettlementService.Rules rules,
                               String roundId, Object roomId, Object gameId, Set<String> candidates) {
        var checks = new ArrayList<Map<String,Object>>(); var warnings = new ArrayList<String>();
        var summary = new LinkedHashMap<String,Object>();
        summary.put("candidateCount", candidates == null ? null : candidates.size());
        summary.put("candidateSource", candidates == null ? "NOT_RECORDED" : "LOCAL_PUSH_REQUEST_CANDIDATES");
        summary.put("capacity", null); summary.put("capacitySource", "NOT_RECORDED");
        summary.put("actualParticipantCount", null); summary.put("actualParticipantSource", "NOT_RECORDED");
        summary.put("sourceBoundary", "同一次服务端查询返回的开局与结算报告互相核对，不是独立于服务端的外部证据");
        try { STRICT_JSON.readTree(response); } catch (Exception malformed) {
            summary.put("settlementCount",null); summary.put("settlementSource","QUERY_SETTLEMENT_RECORD:"+safe(rules.recordPath()));
            add(checks,"players","UNKNOWN",null,null,"报告JSON无效或包含重复字段，无法证明唯一实际参赛名单");
            return new Evaluation(checks,warnings,summary);
        }
        Roster settled = roster(settlementBody, rules.playersPath(), rules.playerIdPath());
        summary.put("settlementCount", settled.valid() ? settled.ids().size() : null);
        summary.put("settlementSource", "QUERY_SETTLEMENT_RECORD:" + safe(rules.recordPath()));
        if (!settled.valid()) add(checks, "settlementPlayers", settled.state().equals("DUPLICATE") ? "FAIL" : "UNKNOWN", "完整且唯一的字符串 UID 名单", null, settled.reason());
        Rules source = rules.participation();
        boolean bound = false; JsonNode start = null;
        if (source != null && distinctRecords(source.startRecordPath(), rules.recordPath())) {
            start = one(response, source.startRecordPath());
            if (start != null && start.isObject() && !start.isEmpty()) {
                String body = start.toString();
                bound = bind(checks,"startRoundId",roundId,one(body,source.roundIdPath()),true)
                      & bind(checks,"startRoomId",roomId,one(body,source.roomIdPath()),true)
                      & bind(checks,"startGameId",gameId,one(body,source.gameIdPath()),false);
                summary.put("actualParticipantSource", "SAME_QUERY_START_REPORT:" + safe(source.startRecordPath()));
                if (bound && Boolean.TRUE.equals(source.completeRoster())) {
                    Roster actual = roster(body, source.playersPath(), source.playerIdPath());
                    if (actual.valid()) {
                        summary.put("actualParticipantCount", actual.ids().size());
                        if (settled.valid()) add(checks,"players",actual.ids().equals(settled.ids())?"PASS":"FAIL",List.copyOf(actual.ids()),List.copyOf(settled.ids()),"实际开局完整名单与结算名单 EXACT；推送顺序不参与判断");
                    } else add(checks,"players",actual.state().equals("DUPLICATE")?"FAIL":"UNKNOWN",null,null,"开局实际名单不可用："+actual.reason());
                } else if (bound) add(checks,"players","UNKNOWN",null,null,"开局名单未确认完整；候选子集或人数一致不能证明实际参赛名单");
                if (bound && !blank(source.capacityPath()) && !blank(source.capacitySource())) {
                    JsonNode cap = one(body,source.capacityPath());
                    if (cap != null && cap.isIntegralNumber() && cap.canConvertToInt() && cap.intValue()>0 && cap.intValue()<=256) {
                        summary.put("capacity",cap.intValue()); summary.put("capacitySource",source.capacitySource()+":"+source.capacityPath());
                        if (settled.valid()) add(checks,"capacity",settled.ids().size()<=cap.intValue()?"PASS":"FAIL",cap.intValue(),settled.ids().size(),"仅验证可靠容量上限，不能据此选出实际参赛者");
                        if (summary.get("actualParticipantCount") instanceof Integer count) add(checks,"startCapacity",count<=cap.intValue()?"PASS":"FAIL",cap.intValue(),count,"开局完整人数不得超过同来源容量上限");
                    } else add(checks,"capacity","UNKNOWN",null,null,"容量来源字段不是1～256的整数，未按游戏或样本规律猜测");
                }
            } else add(checks,"players","UNKNOWN",null,null,"同次查询缺少唯一开局报告，实际参赛名单尚未核验");
        } else add(checks,"players","UNKNOWN",null,null,source==null?"尚未配置实际参赛来源；实际推送仅为候选名单":"开局来源与结算记录路径相同或互相包含，禁止用结算名单自证");
        if (candidates != null && settled.valid()) add(checks,"candidateMembership",candidates.containsAll(settled.ids())?"PASS":"FAIL",List.copyOf(candidates),List.copyOf(settled.ids()),"仅核候选身份，不能代替实际参赛 EXACT");
        if ("SUBSET_ALLOWED".equals(rules.participantRule())) warnings.add("旧 SUBSET_ALLOWED 仅兼容读取；新核验不再凭候选子集判通过，必须取得完整实际参赛证据");
        if (blank(rules.participantRule())) add(checks,"playersRule","UNKNOWN",null,null,"尚未配置参赛核验规则");
        summary.put("completeRosterConfirmed",source!=null&&Boolean.TRUE.equals(source.completeRoster()));
        summary.put("startIdentityBound",bound);
        return new Evaluation(checks,warnings,summary);
    }
    static Roster roster(String body,String path,String uidPath) {
        JsonNode values=one(body,path); Set<String> ids=new LinkedHashSet<>();
        if(values==null||!values.isArray()||values.isEmpty()||values.size()>256||blank(uidPath))return new Roster(ids,"UNKNOWN","名单缺失、为空、超出256项或字段未配置");
        for(JsonNode player:values){JsonNode uid=one(player.toString(),uidPath);if(uid==null||!uid.isTextual()||uid.asText().isBlank()||uid.asText().length()>512)return new Roster(Set.of(),"UNKNOWN","UID必须是非空字符串；数值/混合类型不做隐式转换");if(!ids.add(uid.asText()))return new Roster(Set.of(),"DUPLICATE","名单出现重复UID");}
        return new Roster(Collections.unmodifiableSet(ids),"COMPLETE","");
    }
    private static boolean bind(List<Map<String,Object>> checks,String field,Object expected,JsonNode actual,boolean stringOnly){
        String value=actual!=null&&(actual.isTextual()||(!stringOnly&&actual.isIntegralNumber()))?actual.asText():null;
        String state=expected==null||String.valueOf(expected).isBlank()||value==null||value.isBlank()?"UNKNOWN":String.valueOf(expected).equals(value)?"PASS":"FAIL";
        add(checks,field,state,expected,value,"开局报告与本圈冻结身份精确绑定；整数不经浮点数转换");return "PASS".equals(state);
    }
    private static boolean distinctRecords(String first,String second){if(blank(first)||blank(second)||first.contains("*")||second.contains("*"))return false;first=normalize(first);second=normalize(second);if(blank(first)||blank(second)||"$".equals(first)||"$".equals(second))return false;return !first.equals(second)&&!first.startsWith(second+".")&&!first.startsWith(second+"[")&&!second.startsWith(first+".")&&!second.startsWith(first+"[");}
    private static String normalize(String path){path=path.trim();if(path.startsWith("$."))path=path.substring(2);path=String.join(".",Arrays.stream(path.split("\\.")).map(String::trim).filter(s->!s.isEmpty()).toList());
        var matcher=java.util.regex.Pattern.compile("\\[\\s*([+-]?[0-9]+)\\s*]").matcher(path);var result=new StringBuilder();
        while(matcher.find())try{matcher.appendReplacement(result,"["+Integer.parseInt(matcher.group(1))+"]");}catch(NumberFormatException invalid){return path;}
        matcher.appendTail(result);return result.toString();}
    private static JsonNode one(String body,String path){if(blank(path))return null;try{var values=ApiBodyAssert.readValues(body,path);return values.size()==1?values.getFirst():null;}catch(Exception error){return null;}}
    private static void add(List<Map<String,Object>> checks,String field,String status,Object expected,Object actual,String message){var row=new LinkedHashMap<String,Object>();row.put("field",field);row.put("status",status);row.put("expected",expected);row.put("actual",actual instanceof String s&&s.length()>4096?s.substring(0,4096)+"…":actual);row.put("actualTruncated",actual instanceof String s&&s.length()>4096);row.put("message",message);checks.add(row);}
    private static String safe(String value){return value==null?"":value;}
    private static boolean blank(String value){return value==null||value.isBlank();}
}
