package com.sudpanel.perf;

import com.sudpanel.api.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** 按显式响应契约核验本圈归属；状态码、HTTP或人数的偶然规律不作为归属证据。 */
@Service
public class PerfSettlementService {
    public record Rules(String recordPath,String roundIdPath,String roomIdPath,String gameIdPath,String playersPath,
            String playerIdPath,String timestampPath,String timestampUnit,String businessCodePath,List<String> successCodes,
            List<String> pendingCodes,List<String> noDataCodes,String participantRule,Long timeToleranceMs,PerfParticipationEvidence.Rules participation) {
        /** 旧调用/已存规则仍可读取；新执行以 V2 实际参赛证据语义计算，不重写旧历史。 */
        public Rules(String recordPath,String roundIdPath,String roomIdPath,String gameIdPath,String playersPath,
                String playerIdPath,String timestampPath,String timestampUnit,String businessCodePath,List<String> successCodes,
                List<String> pendingCodes,List<String> noDataCodes,String participantRule,Long timeToleranceMs) {
            this(recordPath,roundIdPath,roomIdPath,gameIdPath,playersPath,playerIdPath,timestampPath,timestampUnit,
                 businessCodePath,successCodes,pendingCodes,noDataCodes,participantRule,timeToleranceMs,null);
        }
    }
    public record Request(long caseId,Rules rules) {}
    private final PerfRepository perf; private final PerfEvidenceRepository evidence;
    private final ApiRepository api; private final ApiRunner runner;
    private final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    public PerfSettlementService(PerfRepository perf,PerfEvidenceRepository evidence,ApiRepository api,ApiRunner runner) {
        this.perf=perf;this.evidence=evidence;this.api=api;this.runner=runner;
    }
    public Map<String,Object> check(long loopId,Request request) {
        if(request==null||request.caseId()<1||request.rules()==null)throw new IllegalArgumentException("请选择查询用例并填写响应核验规则");
        Rules rules=request.rules();
        if(blank(rules.businessCodePath())||rules.successCodes()==null||rules.successCodes().isEmpty()||rules.successCodes().size()>20||blank(rules.recordPath())||blank(rules.roundIdPath()))throw new IllegalArgumentException("必须配置业务码、成功码、记录路径及对局ID路径");
        if(!blank(rules.participantRule())&&!List.of("EXACT","SUBSET_ALLOWED").contains(rules.participantRule()))throw new IllegalArgumentException("玩家规则仅支持EXACT/SUBSET_ALLOWED");
        for(String path:Arrays.asList(rules.recordPath(),rules.roundIdPath(),rules.roomIdPath(),rules.gameIdPath(),rules.playersPath(),rules.playerIdPath(),rules.timestampPath(),rules.businessCodePath()))if(path!=null&&path.length()>512)throw new IllegalArgumentException("响应路径最多512字符");
        if(rules.participation()!=null){var p=rules.participation();for(String path:Arrays.asList(p.startRecordPath(),p.roundIdPath(),p.roomIdPath(),p.gameIdPath(),p.playersPath(),p.playerIdPath(),p.capacityPath()))if(path!=null&&path.length()>512)throw new IllegalArgumentException("参赛证据路径最多512字符");if(p.capacitySource()!=null&&p.capacitySource().length()>256)throw new IllegalArgumentException("容量来源说明最多256字符");}
        for(var codes:Arrays.asList(rules.successCodes(),rules.pendingCodes(),rules.noDataCodes()))if(codes!=null&&(codes.size()>20||codes.stream().anyMatch(value->value==null||value.length()>256)))throw new IllegalArgumentException("状态码列表最多20项，每项最多256字符");
        long tolerance=rules.timeToleranceMs()==null?120_000:rules.timeToleranceMs();if(tolerance<0||tolerance>86_400_000)throw new IllegalArgumentException("时间容差须为0～86400000毫秒");
        var loop=perf.getLoop(loopId);if(loop==null)throw new IllegalArgumentException("圈不存在");long runId=((Number)loop.get("runId")).longValue();
        var idEvidence=(Map<?,?>)loop.get("roundIdEvidence");String roundId=(String)loop.get("gameRoundId");
        if(roundId==null||!"UNIQUE".equals(idEvidence.get("state")))throw new IllegalStateException("本圈ID缺失、冲突或日志不完整，不能自动核验归属");
        PerfExecutionPlan plan=perf.plan(runId);if(plan==null||loop.get("planHash")==null||!plan.hash().equals(loop.get("planHash")))throw new IllegalStateException("本圈没有可核实的冻结来源环境");
        if(plan.requests().values().stream().anyMatch(bound->Objects.equals(bound.spec().id(),request.caseId())))throw new IllegalArgumentException("推人机、开局、结束或清房用例不能作为结算查询，请选择独立的只读查询用例");
        var source=plan.requests().get("开局");if(source==null)throw new IllegalStateException("本圈没有冻结开局环境");
        ApiSpec spec=api.getCase(request.caseId());if(spec==null)throw new IllegalArgumentException("查询用例不存在");
        spec=fillRound(spec,roundId);
        var game=plan.games().get(((Number)loop.get("gameId")).longValue());
        if(game==null)throw new IllegalStateException("本圈没有可核实的冻结游戏配置");
        var overrides=new LinkedHashMap<String,String>();
        for(String key:List.of("mg_id","app_id","room_id")) {
            Object value=game.get(switch(key){case "mg_id"->"mgId";case "app_id"->"appId";default->"roomId";});
            if(value!=null&&!String.valueOf(value).isBlank())overrides.put(key,String.valueOf(value));
        }
        overrides.put("game_round_id",roundId);overrides.put("round_id",roundId);overrides.put("gameRoundId",roundId);
        ApiExec exec=runner.executeWithEnvironment(spec,source.environment().withSessionScope("perf-run-"+runId),overrides,null,0,0);
        Map<String,Object> summary=evaluate(loop,plan,rules,exec,tolerance,pushedPlayers(loopId));
        Long stepId=null;boolean missing=false;
        try {stepId=evidence.record(runId,loopId,((Number)loop.get("gameId")).longValue(),((Number)loop.get("loopNo")).intValue(),"结算归属核验",exec);}
        catch(Exception storage){missing=true;}
        summary.put("stepId",stepId);summary.put("evidenceMissing",missing);summary.put("queryCaseId",request.caseId());summary.put("sourcePlanHash",plan.hash());
        try {perf.recordSettlement(runId,loopId,summary);}catch(Exception storage){summary.put("evidenceMissing",true);((List<String>)summary.get("warnings")).add("核验摘要写入失败，本次结果无法保证重启后保留");}
        return summary;
    }
    ApiSpec fillRound(ApiSpec spec,String id) {
        try {
            JsonNode tree=json.valueToTree(spec);boolean[] changed={false};fillNode(tree,id,changed);
            if(!changed[0])throw new IllegalArgumentException("查询模板未包含可识别对局ID字段或占位符，请先配置查询用例");
            return json.treeToValue(tree,ApiSpec.class);
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("查询模板无法填入对局ID",e);}
    }
    private void fillNode(JsonNode node,String id,boolean[] changed) {
        if(node.isObject()) {
            var object=(ObjectNode)node;var names=new ArrayList<String>();object.fieldNames().forEachRemaining(names::add);
            for(String key:names){JsonNode value=object.get(key);if(value.isTextual()) {
                String text=value.asText(),filled=text.replaceAll("\\{\\{\\s*(?:game_round_id|round_id|gameRoundId|run\\.round_id)\\s*}}",java.util.regex.Matcher.quoteReplacement(id));
                if(!text.equals(filled)){object.put(key,filled);changed[0]=true;}
                if("body".equals(key))try{JsonNode body=json.readTree(filled);if(body!=null){replaceIdFields(body,id,changed);object.put(key,body.toString());}}catch(Exception ignored){}
            }else fillNode(value,id,changed);}
        }else if(node.isArray())node.forEach(value->fillNode(value,id,changed));
    }
    private void replaceIdFields(JsonNode node,String id,boolean[] changed) {
        if(node.isObject()){var object=(ObjectNode)node;var names=new ArrayList<String>();object.fieldNames().forEachRemaining(names::add);for(String key:names){if(List.of("game_round_id","gameRoundId","round_id").contains(key)){object.put(key,id);changed[0]=true;}else replaceIdFields(object.get(key),id,changed);}}
        else if(node.isArray())node.forEach(value->replaceIdFields(value,id,changed));
    }
    private Set<String> pushedPlayers(long loopId) {
        try {
            var steps=(List<Map<String,Object>>)evidence.loopEvidence(loopId).get("steps");Map<String,Object> last=null;
            for(var step:steps)if("推人机".equals(step.get("step"))&&"PASS".equals(step.get("verdict")))last=step;
            if(last==null)return null;var detail=evidence.detail(((Number)last.get("id")).longValue());if(Boolean.TRUE.equals(detail.get("truncated")))return null;
            JsonNode exec=(JsonNode)detail.get("exec");Set<String> players=new LinkedHashSet<>();
            for(JsonNode value:ApiBodyAssert.readValues(exec.path("reqBody").asText(),"data.user_infos[*].uid")){if(players.size()>=256||!value.isTextual()||value.asText().isBlank()||value.asText().length()>512||!players.add(value.asText()))return null;}
            return players.isEmpty()?null:players;
        }catch(Exception e){return null;}
    }
    Map<String,Object> evaluate(Map<String,Object> loop,PerfExecutionPlan plan,Rules rules,ApiExec exec,long tolerance,Set<String> pushed) {
        var warnings=new ArrayList<String>();var checks=new ArrayList<Map<String,Object>>();Map<String,Object> participantSummary=Map.of();String status="UNKNOWN";
        if(ApiExec.ERROR.equals(exec.verdict())||exec.httpStatus()==null||exec.httpStatus()<200||exec.httpStatus()>=300)status="ERROR";
        else try {
            JsonNode code=one(exec.respBody(),rules.businessCodePath());String business=code==null?null:code.asText();
            if(business!=null&&rules.pendingCodes()!=null&&rules.pendingCodes().contains(business))status="PENDING";
            else if(business!=null&&rules.noDataCodes()!=null&&rules.noDataCodes().contains(business))status="NO_DATA";
            else if(business==null||!rules.successCodes().contains(business)){status="ERROR";warnings.add("响应业务码未满足显式成功规则");}
            else {
                var records=ApiBodyAssert.readValues(exec.respBody(),rules.recordPath());
                if(records.size()!=1)throw new IllegalArgumentException("记录不唯一");
                JsonNode record=records.getFirst();
                if(!ApiExec.PASS.equals(exec.verdict())){status="ERROR";warnings.add("查询用例断言未通过，不能认定结算成功");}
                else if(record.isNull()||(record.isContainerNode()&&record.isEmpty()))status="NO_DATA";
                else {
                    String body=record.toString();var game=plan.games().get(((Number)loop.get("gameId")).longValue());
                    compare(checks,"roundId",String.valueOf(loop.get("gameRoundId")),one(body,rules.roundIdPath()));
                    compareOptional(checks,warnings,"roomId",game.get("roomId"),body,rules.roomIdPath());
                    compareOptional(checks,warnings,"gameId",game.get("mgId"),body,rules.gameIdPath());
                    var participation=PerfParticipationEvidence.evaluate(exec.respBody(),body,rules,String.valueOf(loop.get("gameRoundId")),game.get("roomId"),game.get("mgId"),pushed);
                    checks.addAll(participation.checks());warnings.addAll(participation.warnings());
                    participantSummary=participation.summary();
                    if(blank(rules.timestampPath())||blank(rules.timestampUnit()))warnings.add("未配置结算时间路径或单位，时间归属尚未核验");
                    else try {
                        JsonNode value=one(body,rules.timestampPath());long ts=switch(rules.timestampUnit()){case "SECONDS"->Math.multiplyExact(Long.parseLong(value.asText()),1000);case "MILLIS"->Long.parseLong(value.asText());case "ISO"->Instant.parse(value.asText()).toEpochMilli();default->throw new IllegalArgumentException();};
                        long start=Instant.parse(String.valueOf(loop.get("startTime"))).toEpochMilli(),end=loop.get("endTime")==null?Instant.now().toEpochMilli():Instant.parse(String.valueOf(loop.get("endTime"))).toEpochMilli();
                        add(checks,"timestamp",ts>=start-tolerance&&ts<=end+tolerance?"PASS":"FAIL",List.of(start-tolerance,end+tolerance),ts,"按圈时间范围与配置容差比较");
                    }catch(Exception invalid){warnings.add("结算时间缺失或无法按配置单位解析");}
                    boolean failed=checks.stream().anyMatch(c->"FAIL".equals(c.get("status")));
                    boolean incomplete=checks.stream().anyMatch(c->"UNKNOWN".equals(c.get("status")))||warnings.stream().anyMatch(w->w.contains("尚未核验")||w.contains("无法")||w.contains("缺失"));
                    status=failed?"MISMATCH":incomplete?"UNKNOWN":"SUCCESS";
                }
            }
        }catch(Exception invalid){warnings.add("响应无法按配置规则解析，归属未知");status="UNKNOWN";}
        var out=new LinkedHashMap<String,Object>();out.put("status",status);out.put("checks",checks);out.put("warnings",warnings);out.put("checkedAt",Instant.now().toString());out.put("semanticsVersion",2);out.put("participantEvidence",participantSummary);return out;
    }
    private void compareOptional(List<Map<String,Object>> checks,List<String> warnings,String field,Object expected,String body,String path){if(blank(path)||expected==null||String.valueOf(expected).isBlank())warnings.add(field+"来源或响应路径缺失，身份尚未核验");else compare(checks,field,String.valueOf(expected),one(body,path));}
    private void compare(List<Map<String,Object>> checks,String field,String expected,JsonNode actual){add(checks,field,actual!=null&&actual.isValueNode()&&expected.equals(actual.asText())?"PASS":"FAIL",expected,actual==null?null:actual.asText(),"按字符串精确比较，不转为浮点数");}
    private void add(List<Map<String,Object>> checks,String field,String state,Object expected,Object actual,String message){var row=new LinkedHashMap<String,Object>();row.put("field",field);row.put("status",state);row.put("expected",expected);row.put("actual",actual instanceof String value&&value.length()>4096?value.substring(0,4096)+"…":actual);row.put("actualTruncated",actual instanceof String value&&value.length()>4096);row.put("message",message);checks.add(row);}
    private JsonNode one(String body,String path){if(blank(path))return null;try{var values=ApiBodyAssert.readValues(body,path);return values.size()==1?values.getFirst():null;}catch(Exception e){return null;}}
    private static boolean blank(String value){return value==null||value.isBlank();}
}
