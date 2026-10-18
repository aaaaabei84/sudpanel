package com.sudpanel.api;

import com.sudpanel.api.script.ApiScriptSandbox;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** 请求级纯计算签名；临时写入不会回写环境或跨请求保留。 */
public final class ApiSignatureScripts {
    public static final int MAX_SCRIPT_BYTES=65536;
    private static final java.security.SecureRandom RANDOM=new java.security.SecureRandom();
    private ApiSignatureScripts() {}
    public record Context(ApiSpec spec,ApiRequestEngine.Environment environment,Set<String> secretOutputs,Map<String,Object> summary) {}
    public static void validate(ApiRequestOptions.Auth auth) {
        if ("SCRIPT".equals(auth.type())) {
            if(auth.script()==null||auth.script().isBlank())throw new IllegalArgumentException("签名脚本不能为空");
            if(auth.script().getBytes(StandardCharsets.UTF_8).length>MAX_SCRIPT_BYTES)throw new IllegalArgumentException("签名脚本最多64KiB（UTF-8）");
        }
        if("TEMPLATE".equals(auth.type())&&(auth.authorizationTemplate()==null||auth.authorizationTemplate().isBlank()))
            throw new IllegalArgumentException("Authorization模板不能为空");
        if(auth.authorizationTemplate()!=null&&auth.authorizationTemplate().length()>65536)
            throw new IllegalArgumentException("Authorization模板最多65536字符");
    }
    static ApiRequestEngine.Built build(ApiSpec original,ApiRequestEngine.Environment environment,Map<String,String> overrides,
            Map<String,String> run,Map<String,String> data,ApiFileStore files) {
        var options=original.options();
        if(options.bodyType()!=null&&!Set.of("JSON","RAW","NONE").contains(options.bodyType()))
            throw new IllegalArgumentException("签名脚本仅支持JSON、RAW或无请求体；文件和表单请使用原鉴权功能");
        Instant now=Instant.now(); long started=System.nanoTime();
        var dynamic=new LinkedHashMap<String,String>();
        var base=new ApiRequestEngine.Environment(environment.envId(),environment.envName(),environment.capturedAt(),
                ApiVariables.overrideBody(environment.vars(),overrides),environment.sign(),environment.strictVariables(),environment.inheritedProxy(),environment.cookieScope());
        Map<String,String> variables=flatten(base.vars());
        var visible=new LinkedHashMap<String,String>();
        if(run!=null)run.forEach((k,v)->visible.put("run."+k,jsonValue(v)));
        if(data!=null)data.forEach((k,v)->visible.put("data."+k,jsonValue(v)));
        long seed=RANDOM.nextLong();byte[] randomBytes=new byte[32];RANDOM.nextBytes(randomBytes);String randomKey=HexFormat.of().formatHex(randomBytes);
        var headers=originalHeaders(original);
        var first=execute(original,variables,visible,headers,original.body(),original.url(),now,seed,randomKey,started);
        var firstEnv=apply(base,first);var firstSpec=apply(original,first);
        String finalInput; String finalUrl;
        try(var resolved=ApiRequestEngine.buildCore(firstSpec,firstEnv,null,run,data,files,now,dynamic)) {finalInput=wireText(resolved.body());finalUrl=resolved.url();}
        // 每轮从相同变量/时间/随机源开始，第二轮只更换为实际最终正文。
        var second=execute(original,variables,visible,headers,finalInput,finalUrl,now,seed,randomKey,started);
        var effectiveEnv=apply(base,second);var effectiveSpec=apply(original,second);
        ApiRequestEngine.Built built=ApiRequestEngine.buildCore(effectiveSpec,effectiveEnv,null,run,data,files,now,dynamic);
        if(!finalInput.equals(wireText(built.body()))||!Objects.equals(finalUrl,built.url())) {
            built.close();throw new IllegalArgumentException("签名脚本修改正文后不稳定，无法保证签名与实际正文一致；请保持正文修改幂等，签名放在请求头");
        }
        if(!built.unresolved().isEmpty()) {built.close();throw new IllegalArgumentException("脚本签名请求仍有未解析变量："+String.join(", ",built.unresolved()));}
        var secrets=new LinkedHashSet<String>();secrets.addAll(first.secretOutputs());secrets.addAll(second.secretOutputs());
        // 脚本派生值也可能含密钥；全部临时输出按敏感值处理，默认展示不传播。
        for(var value:second.environmentWrites().values())if(value!=null&&!value.isEmpty())secrets.add(value);
        for(var value:second.globalsWrites().values())if(value!=null&&!value.isEmpty())secrets.add(value);
        for(var value:second.localWrites().values())if(value!=null&&!value.isEmpty())secrets.add(value);
        var summary=new LinkedHashMap<String,Object>();summary.put("mode","SCRIPT");summary.put("passes",2);
        summary.put("consoleCalls",first.consoleCount()+second.consoleCount());summary.put("consoleTextRetained",false);
        summary.put("bodyVerified",true);summary.put("elapsedMs",(System.nanoTime()-started)/1_000_000);
        var warnings=new ArrayList<>(built.warnings());warnings.add("请求级签名脚本已运行；变量修改仅本次有效，控制台正文不保留");
        var context=new Context(effectiveSpec,effectiveEnv,Set.copyOf(secrets),Map.copyOf(summary));
        return new ApiRequestEngine.Built(built.method(),built.url(),built.headers(),built.body(),built.authType(),built.bodyType(),warnings,
                built.unresolved(),built.variables(),built.wireBody(),built.bodyMetadata(),built.filePayload(),built.headerRows(),context);
    }
    private static ApiScriptSandbox.Result execute(ApiSpec original,Map<String,String> environment,Map<String,String> variables,
            List<Map<String,Object>> headers,String body,String url,Instant now,long seed,String randomKey,long started) {
        if(Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("签名脚本已取消");
        long remaining=original.timeoutOrDefault()-(System.nanoTime()-started)/1_000_000;
        if(remaining<=0)throw new IllegalArgumentException("签名脚本超过请求总时限");
        var input=new LinkedHashMap<String,Object>();input.put("environment",environment);input.put("globals",Map.of());input.put("variables",variables);
        input.put("body",wireText(body));input.put("headers",headers);input.put("method",original.method()==null?"GET":original.method().trim().toUpperCase(Locale.ROOT));
        input.put("url",url);input.put("timeMillis",now.toEpochMilli());input.put("randomSeed",seed);input.put("randomKey",randomKey);
        return ApiScriptSandbox.execute(original.options().auth().script(),input,Math.min(1500,remaining));
    }
    private static Map<String,String> flatten(ApiVariables.VarSet vars) {
        var out=new LinkedHashMap<String,String>();
        for(var group:List.of(vars.base(),vars.header(),vars.body()))for(var row:group) {
            if(row.name()==null||row.name().isBlank())continue;
            if(out.containsKey(row.name())&&!Objects.equals(out.get(row.name()),row.value()))
                throw new IllegalArgumentException("签名脚本无法确定跨组同名变量的取值："+row.name()+"；请统一值或改名");
            out.put(row.name(),row.value()==null?"":row.value());
        }
        return out;
    }
    private static List<Map<String,Object>> originalHeaders(ApiSpec spec) {
        var rows=spec.options().headerRows();
        if(rows==null)rows=ApiHeaders.fromMap(ApiRequestEngine.parseHeaders(spec.headers()));
        return rows.stream().map(h->{var m=new LinkedHashMap<String,Object>();m.put("name",h.name());m.put("value",h.value()==null?"":h.value());m.put("enabled",!Boolean.FALSE.equals(h.enabled()));return (Map<String,Object>)m;}).toList();
    }
    private static ApiRequestEngine.Environment apply(ApiRequestEngine.Environment base,ApiScriptSandbox.Result result) {
        var env=flatten(base.vars());
        result.environmentWrites().forEach((name,value)->{if(value==null)env.remove(name);else env.put(name,value);});
        var effective=new LinkedHashMap<String,String>();
        result.globalsWrites().forEach((name,value)->{if(value!=null)effective.put(name,value);});
        effective.putAll(env); // Postman查找优先级：本次local > environment > globals。
        result.localWrites().forEach((name,value)->{if(value!=null)effective.put(name,value);});
        var changed=new HashSet<String>();changed.addAll(result.globalsWrites().keySet());changed.addAll(result.environmentWrites().keySet());changed.addAll(result.localWrites().keySet());
        var vars=expanded(base.vars(),effective,changed);
        return new ApiRequestEngine.Environment(base.envId(),base.envName(),base.capturedAt(),vars,base.sign(),base.strictVariables(),base.inheritedProxy(),base.cookieScope());
    }
    private static ApiVariables.VarSet expanded(ApiVariables.VarSet source,Map<String,String> all,Set<String> changed) {
        var secretNames=new HashSet<String>();
        for(var group:List.of(source.base(),source.header(),source.body()))for(var row:group)if(Boolean.TRUE.equals(row.secret()))secretNames.add(row.name());
        var rows=new ArrayList<ApiVariables.Var>();
        all.forEach((name,value)->{if(value!=null)rows.add(new ApiVariables.Var(name,value,changed.contains(name)||secretNames.contains(name)));});
        return new ApiVariables.VarSet(List.copyOf(rows),List.copyOf(rows),List.copyOf(rows));
    }
    static ApiRequestEngine.Environment templateEnvironment(ApiRequestEngine.Environment base,Map<String,String> overrides) {
        var vars=ApiVariables.overrideBody(base.vars(),overrides);
        return new ApiRequestEngine.Environment(base.envId(),base.envName(),base.capturedAt(),expanded(vars,flatten(vars),Set.of()),base.sign(),base.strictVariables(),base.inheritedProxy(),base.cookieScope());
    }
    private static ApiSpec apply(ApiSpec original,ApiScriptSandbox.Result result) {
        var o=original.options();var rows=new ArrayList<ApiRequestOptions.HeaderRow>();
        for(var h:result.headers())rows.add(new ApiRequestOptions.HeaderRow(Objects.toString(h.get("name"),""),Objects.toString(h.get("value"),""),!Boolean.FALSE.equals(h.get("enabled"))));
        var options=new ApiRequestOptions(o.bodyType(),o.params(),o.auth(),o.extractors(),o.fields(),o.transport(),o.strictVariables(),o.fileBody(),o.parts(),List.copyOf(rows));
        return new ApiSpec(original.id(),original.name(),original.method(),result.urlUpdated()?result.url():original.url(),original.headers(),
                result.bodyUpdated()?result.body():original.body(),original.note(),original.expectStatus(),original.expectCode(),original.timeoutMs(),original.insecure(),original.enabled(),original.groupId(),original.asserts(),options);
    }
    private static String wireText(String value){return value==null?"":value;}
    private static String jsonValue(String value){try{var n=new com.fasterxml.jackson.databind.ObjectMapper().readTree(value);return n.isTextual()?n.asText():n.toString();}catch(Exception e){return value;}}
    public static ApiRequestEngine.Environment effectiveEnvironment(ApiRequestEngine.Built built,ApiRequestEngine.Environment fallback){return built.signatureContext()==null?fallback:built.signatureContext().environment();}
    public static ApiSpec effectiveSpec(ApiRequestEngine.Built built,ApiSpec fallback){return built.signatureContext()==null?fallback:built.signatureContext().spec();}
}
