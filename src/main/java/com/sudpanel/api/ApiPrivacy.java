package com.sudpanel.api;

import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.jdbc.core.JdbcTemplate;

/** Presentation only. Never pass a masked request back to the engine or overwrite original evidence. */
public final class ApiPrivacy {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final java.util.regex.Pattern SENSITIVE=java.util.regex.Pattern.compile("(?i).*(authorization|cookie|token|password|passwd|secret|api[_-]?key|signature).* ".trim());
    private ApiPrivacy(){}
    public static Set<String> known(ApiRequestEngine.Environment env,ApiSpec spec,Map<String,String> run) {
        var values=new LinkedHashSet<String>();
        if(env!=null){
            if(env.sign()!=null)add(values,env.sign().appSecret());
            for(var list:List.of(env.vars().base(),env.vars().header(),env.vars().body()))for(var v:list)
                if(Boolean.TRUE.equals(v.secret()) || sensitive(v.name()))add(values,v.value());
        }
        if(spec!=null && spec.options()!=null && spec.options().auth()!=null){var a=spec.options().auth();for(String template:java.util.Arrays.asList(a.token(),a.password(),a.keyValue(),a.authorizationTemplate())){
            if(template==null)continue;String resolved=template;
            if(env!=null){var substituted=ApiVariables.apply(env.vars(),template,template,null);resolved="API_KEY".equals(a.type())&&"QUERY".equals(a.in())?substituted.url():substituted.headers();}
            if(!resolved.equals(template)||!resolved.contains("{{"))add(values,resolved);
            var matcher=java.util.regex.Pattern.compile("\\{\\{\\s*run\\.([^{}]+?)\\s*\\}\\}").matcher(template);while(matcher.find())collect(ApiHistoryCompare.parse(run.get(matcher.group(1).trim())),values,true);
        }}
        if(run instanceof ApiRunVariables marked)for(String name:marked.secretNames())collect(ApiHistoryCompare.parse(run.get(name)),values,true);
        return values;
    }
    public static ApiExec attach(ApiExec execution,Set<String> known,JdbcTemplate jdbc) {
        var values=new LinkedHashSet<>(known);headers(execution.reqHeaders(),values);headers(execution.respHeaders(),values);
        JsonNode meta=ApiHistoryCompare.parse(execution.metadata());var raw=meta!=null&&meta.isObject()?((ObjectNode)meta).deepCopy():JSON.createObjectNode();
        JsonNode extracts=raw.path("extractions");if(extracts.isArray())for(var e:extracts)if(e.path("secret").asBoolean(false))collect(ApiHistoryCompare.parse(e.path("valueJson").asText()),values,true);
        if(raw.path("authType").asText().equals("API_KEY")){ApiHistoryCompare.headers(execution.reqHeaders()).values().forEach(v->java.util.Arrays.stream(v.split("\\r?\\n")).forEach(value->add(values,value)));queryValues(execution.url(),values);}
        raw.remove("safeDisplay");raw.remove("secretMask");
        ObjectNode safe=JSON.valueToTree(execution);safe.set("metadata",mask(raw,values));
        // JSON-in-string fields must be parsed first so escaped values are masked without corrupting JSON.
        for(String field:List.of("url","reason","error","reqBody","respBody","reqHeaders","respHeaders","assertsResult","caseName","codeMeaning","respCode")){
            JsonNode original=safe.get(field);if(original!=null&&original.isTextual())safe.put(field,replace(original.asText(),values));
        }
        safe.remove("metadata");safe.set("metadata",mask(raw,values));
        raw.set("safeDisplay",safe);
        if(!values.isEmpty()){
            String id=UUID.randomUUID().toString();
            try{String sealed=new ApiPlanVault(jdbc).encrypt("exec-mask",id,JSON.writeValueAsString(values));raw.set("secretMask",JSON.valueToTree(Map.of("id",id,"sealed",sealed)));}
            catch(Exception e){throw new IllegalStateException("敏感标记无法加密保存，已中止存证；请求可能已到达服务器",e);}
        }
        return copy(execution,raw.toString());
    }
    public static Set<String> inventory(ApiExec execution,JdbcTemplate jdbc) {
        var values=new LinkedHashSet<String>();JsonNode meta=ApiHistoryCompare.parse(execution.metadata());
        if(meta!=null && meta.has("secretMask")){
            var seal=meta.get("secretMask");try{var plain=new ApiPlanVault(jdbc).decrypt("exec-mask",seal.path("id").asText(),seal.path("sealed").asText());JsonNode list=JSON.readTree(plain);if(!list.isArray())throw new IllegalArgumentException();for(var value:list)add(values,value.asText());}
            catch(Exception e){throw new IllegalStateException("历史敏感标记不可读取，请恢复配对密钥后导出；不会降级为未遮罩报告",e);}
        }
        return values;
    }
    /** Transient export input only; never persist the returned plain inventory. */
    public static ApiExec exportInput(ApiExec execution,JdbcTemplate jdbc) {
        var values=inventory(execution,jdbc);if(values.isEmpty())return execution;
        ObjectNode meta=(ObjectNode)Objects.requireNonNull(ApiHistoryCompare.parse(execution.metadata()));meta.putPOJO("privateKnownSecrets",values);return copy(execution,meta.toString());
    }
    public static ApiHistoryCompare.Compared compare(ApiExec left,ApiExec right,List<String> ignore,JdbcTemplate jdbc,boolean includeSecrets) {
        var result=ApiHistoryCompare.compare(left,right,ignore);if(includeSecrets)return result;
        var values=inventory(left,jdbc);values.addAll(inventory(right,jdbc));headers(left.reqHeaders(),values);headers(right.reqHeaders(),values);headers(left.respHeaders(),values);headers(right.respHeaders(),values);
        return new ApiHistoryCompare.Compared(result.leftId(),result.rightId(),result.complete(),result.warnings().stream().map(w->replace(w,values)).toList(),result.differences().stream().map(d->new ApiHistoryCompare.Difference(d.section(),replace(d.path(),values),d.kind(),replace(d.left(),values),replace(d.right(),values))).toList());
    }
    public static ApiRequestEngine.Prepared prepared(ApiRequestEngine.Prepared prepared,Set<String> values) {
        var headers=new LinkedHashMap<String,String>();prepared.headers().forEach((k,v)->headers.put(k,sensitive(k)?"***":replace(v,values)));
        return new ApiRequestEngine.Prepared(prepared.valid(),prepared.method(),replace(prepared.url(),values),headers,replace(prepared.body(),values),prepared.envId(),prepared.envName(),prepared.capturedAt(),prepared.warnings().stream().map(w->replace(w,values)).toList(),prepared.unresolved(),prepared.variables(),replace(prepared.error(),values),prepared.diagnostics(),prepared.bodyMetadata()==null?null:JSON.convertValue(mask(JSON.valueToTree(prepared.bodyMetadata()),values),new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){}),prepared.headerRows().stream().map(h->new ApiRequestOptions.HeaderRow(h.name(),sensitive(h.name())?"***":replace(h.value(),values),h.enabled())).toList());
    }
    public static String replace(String text,Collection<String> values) {
        if(text==null)return null;
        var expanded=new TreeSet<String>(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo));
        for(String value:values){if(value==null||value.isEmpty())continue;expanded.add(value);String encoded=java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8);expanded.add(encoded);expanded.add(encoded.replace("+","%20"));expanded.add(value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"));try{String quoted=JSON.writeValueAsString(value);expanded.add(quoted.substring(1,quoted.length()-1));}catch(Exception ignored){}}
        for(String value:expanded)if(!value.isEmpty())text=text.replace(value,"***");return text;
    }
    private static JsonNode mask(JsonNode node,Set<String> values){
        if(node.isTextual())return TextNode.valueOf(replace(node.asText(),values));
        if(node.isObject()){var out=JSON.createObjectNode();node.fields().forEachRemaining(e->out.set(e.getKey(),mask(e.getValue(),values)));return out;}
        if(node.isArray()){var out=JSON.createArrayNode();node.forEach(v->out.add(mask(v,values)));return out;}return node.deepCopy();
    }
    public static boolean sensitive(String name){return name!=null&&SENSITIVE.matcher(name).matches();}
    private static void headers(String text,Set<String> values){ApiHistoryCompare.headers(text).forEach((k,v)->{if(sensitive(k))for(String line:v.split("\\r?\\n")){add(values,line);int i=line.indexOf(' ');if(i>0)add(values,line.substring(i+1));if(k.equalsIgnoreCase("set-cookie")){try{java.net.HttpCookie.parse(line).forEach(cookie->add(values,cookie.getValue()));}catch(Exception ignored){}}else if(k.equalsIgnoreCase("cookie"))for(String pair:line.split(";")){int equals=pair.indexOf('=');if(equals>=0)add(values,pair.substring(equals+1).trim());}}});}
    private static void queryValues(String url,Set<String> values){if(url==null)return;int query=url.indexOf('?');if(query<0)return;String text=url.substring(query+1).split("#",2)[0];for(String pair:text.split("&")){int equals=pair.indexOf('=');if(equals>=0){String value=pair.substring(equals+1);add(values,value);try{add(values,java.net.URLDecoder.decode(value,java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}}}}

    private static void collect(JsonNode node,Set<String> values,boolean all){if(node==null)return;if(node.isValueNode()){if(!node.isNull())add(values,node.asText());}else node.forEach(v->collect(v,values,all));}
    private static void add(Set<String> values,String value){if(value!=null&&!value.isBlank()&&!value.startsWith("secret://"))values.add(value);}
    private static ApiExec copy(ApiExec e,String metadata){return new ApiExec(e.id(),e.batchId(),e.caseId(),e.caseName(),e.roundNo(),e.seq(),e.method(),e.url(),e.httpStatus(),e.respCode(),e.codeMeaning(),e.verdict(),e.reason(),e.elapsedMs(),e.respBody(),e.respHeaders(),e.reqHeaders(),e.reqBody(),e.error(),e.ts(),e.assertsResult(),metadata);}
}
