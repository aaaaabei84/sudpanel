package com.sudpanel.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 只返回字段名和结构差异，冻结计划中的凭据、数据行和变量值不出现在审阅响应。 */
public final class ApiPlanReview {
    private ApiPlanReview() {}
    public record StepView(int seq,Long caseId,String name,String method,long envId,String envName,String stepKey,boolean cleanup,boolean polling) {}
    public record Summary(long batchId,int schema,String kind,String name,String capturedAt,int stepCount,int rowCount,int repeatCount,int intervalSeconds,Long scenarioId,String scenarioName,Long sourceBatchId,List<StepView> steps,List<String> dataColumns) {}
    public record Change(Integer seq,Long caseId,String field,String status) {}
    public record Difference(long batchId,List<Change> changes,boolean changed,boolean currentAvailable,List<String> warnings) {}
    public record RetryPreview(long batchId,boolean available,int failedSteps,int rows,boolean wholeScenario,List<Integer> originalRounds,Difference difference,List<String> warnings) {}
    static Summary summary(long id,ApiExecutionPlan p) {
        var columns=new java.util.LinkedHashSet<String>();p.rows().forEach(row->columns.addAll(row.data().keySet()));
        return new Summary(id,p.schema(),p.kind(),p.name(),p.capturedAt(),p.steps().size(),p.rows().size(),p.repeatCount(),p.intervalSeconds(),p.scenarioId(),p.scenarioName(),p.sourceBatchId(),p.steps().stream().map(s->new StepView(s.seq(),s.spec().id(),s.spec().displayName(),s.spec().method(),s.environment().envId(),s.environment().envName(),s.scenarioStep()==null?null:s.scenarioStep().key(),s.scenarioStep()!=null&&s.scenarioStep().cleanup(),s.scenarioStep()!=null&&s.scenarioStep().poll()!=null)).toList(),List.copyOf(columns));
    }
    static Difference difference(long id,ApiExecutionPlan p,ApiRepository repo,String proxy) {
        var changes=new ArrayList<Change>();var warnings=new ArrayList<String>();boolean available=true;
        for(var step:p.steps()) {
            ApiSpec current;
            try {current=repo.getCase(step.spec().id());}catch(Exception e){changes.add(new Change(step.seq(),step.spec().id(),"case","DELETED"));available=false;continue;}
            var old=fields(step.spec());var now=fields(current);
            old.forEach((key,value)->{if(!Objects.equals(value,now.get(key)))changes.add(new Change(step.seq(),step.spec().id(),key,"CHANGED"));});
            try {
                long effective=repo.resolveEnvForSpec(current,step.scenarioStep()==null?null:step.scenarioStep().envId());
                if(effective!=step.environment().envId())changes.add(new Change(step.seq(),step.spec().id(),"environment.binding","CHANGED"));
                var env=repo.getEnv(effective);
                if(step.environment().inheritedProxy()!=null&&!Objects.equals(proxy,step.environment().inheritedProxy()))changes.add(new Change(step.seq(),step.spec().id(),"environment.inheritedProxy","CHANGED"));
                if(!Objects.equals(env.name(),step.environment().envName()))changes.add(new Change(step.seq(),step.spec().id(),"environment.name","CHANGED"));
                if(!Objects.equals(env.strictVariables(),step.environment().strictVariables()))changes.add(new Change(step.seq(),step.spec().id(),"environment.strictVariables","CHANGED"));
                if(!Objects.equals(repo.getVars(env.id()),step.environment().vars()))changes.add(new Change(step.seq(),step.spec().id(),"environment.variables","CHANGED"));
                if(!Objects.equals(repo.getSignConfig(env.id()),step.environment().sign()))changes.add(new Change(step.seq(),step.spec().id(),"environment.sign","CHANGED"));
            } catch(Exception e){changes.add(new Change(step.seq(),step.spec().id(),"environment","DELETED"));available=false;}
        }
        if(p.scenarioId()!=null)try {
            var scenario=repo.getScenario(p.scenarioId());
            if(!Objects.equals(scenario.steps(),p.steps().stream().map(ApiExecutionPlan.Step::scenarioStep).toList()))changes.add(new Change(null,null,"scenario.steps","CHANGED"));
            if(!Objects.equals(scenario.name(),p.scenarioName()))changes.add(new Change(null,null,"scenario.name","CHANGED"));
        }catch(Exception e){changes.add(new Change(null,null,"scenario","DELETED"));available=false;}
        warnings.add("差异只显示字段名，不返回变量、签名和数据行值；动态时间、随机值将在新执行时重新生成");
        warnings.add("Cookie会话不属于冻结计划，重放使用新的空批次会话");
        return new Difference(id,List.copyOf(changes),!changes.isEmpty(),available,List.copyOf(warnings));
    }
    private static Map<String,Object> fields(ApiSpec s){
        var values=new LinkedHashMap<String,Object>();values.put("name",s.name());values.put("method",s.method());values.put("url",s.url());values.put("headers",s.headers());values.put("body",s.body());values.put("note",s.note());values.put("expectStatus",s.expectStatus());values.put("expectCode",s.expectCode());values.put("timeoutMs",s.timeoutMs());values.put("insecure",s.insecure());values.put("enabled",s.enabled());values.put("groupId",s.groupId());values.put("asserts",s.asserts());values.put("options",s.options());return values;
    }
}
