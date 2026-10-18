package com.sudpanel.api.configarchive;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sudpanel.api.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 只追加的可移植配置包。引用在写入前校验，任何写入失败整批回滚。 */
@Service
public class ApiConfigArchive {
    private final ApiRepository repo;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"empty"}) private abstract static class VarMixin {}
    static { JSON.addMixIn(ApiVariables.VarSet.class,VarMixin.class); }
    public record Environment(Long id, String name, ApiVariables.VarSet vars, ApiSigner.Config sign, Boolean strictVariables) {
        public Environment(Long id,String name,ApiVariables.VarSet vars,ApiSigner.Config sign){this(id,name,vars,sign,null);}
    }
    public record Bundle(int schema, String exportedAt, long defaultEnvId, List<Environment> environments,
                         List<ApiCaseGroup> groups, List<ApiSpec> cases, List<ApiScenario> scenarios,
                         List<String> warnings, boolean credentialsIncluded) {}
    public record Counts(int environments, int groups, int cases, int scenarios) {}
    public record Preview(boolean valid, List<String> errors, List<String> warnings, Counts counts) {}
    public record Imported(Counts counts, Map<Long,Long> environmentIds, Map<Long,Long> groupIds,
                           Map<Long,Long> caseIds, Map<Long,Long> scenarioIds, List<String> warnings) {}
    public ApiConfigArchive(ApiRepository repo, JdbcTemplate jdbc) {
        this.repo = repo; this.jdbc=jdbc; tx = new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(jdbc.getDataSource())));
    }
    public Bundle export(boolean includeSecrets) {
        return repo.readSnapshot(() -> {
            var envs = repo.listEnvs().stream().map(e -> new Environment(e.id(),e.name(),repo.getVars(e.id()),repo.getSignConfig(e.id()),e.strictVariables())).toList();
            var bundle = new Bundle(1,Instant.now().toString(),repo.defaultEnvId(),envs,repo.listCaseGroups(),repo.listCases(),repo.listScenarios(),
                    List.of(includeSecrets ? "包含凭据，仅供本机私密迁移；本机秘密/证书/鉴权配置及附件引用不含材料，换机需重新建立引用或补传" : "变量值、已知凭据及导入备注/脚本原文已移除；自由文本和自定义字段请分享前检查"),includeSecrets);
            if(bundle.cases().stream().anyMatch(c->c.options()!=null&&(c.options().fileBody()!=null||c.options().parts()!=null&&!c.options().parts().isEmpty()))) {
                var warnings=new ArrayList<String>(bundle.warnings());warnings.add("此配置包只有fileId引用，不包含文件原字节；跨机导入须补传并替换引用。完整本机备份才包含原字节");
                bundle=new Bundle(bundle.schema(),bundle.exportedAt(),bundle.defaultEnvId(),bundle.environments(),bundle.groups(),bundle.cases(),bundle.scenarios(),warnings,bundle.credentialsIncluded());
            }
            if (includeSecrets) return bundle;
            var explicitSecrets=new java.util.LinkedHashSet<String>();
            for(var env:envs) { if(env.sign()!=null && env.sign().appSecret()!=null && !env.sign().appSecret().isBlank() && !env.sign().appSecret().startsWith("secret://"))explicitSecrets.add(env.sign().appSecret());
                for(var list:List.of(env.vars().base(),env.vars().header(),env.vars().body()))for(var v:list)if(Boolean.TRUE.equals(v.secret()) && v.value()!=null&&!v.value().isEmpty()&&!v.value().startsWith("secret://"))explicitSecrets.add(v.value()); }
            for(var spec:bundle.cases()){long id=repo.resolveEnvForSpec(spec,null);var environment=new ApiRequestEngine.Environment(id,repo.getEnv(id).name(),bundle.exportedAt(),repo.getVars(id),repo.getSignConfig(id));explicitSecrets.addAll(ApiPrivacy.known(environment,spec,Map.of()));}
            // 备注可编辑，不能只依赖首字符标记；目录来源与任意位置的标记共同兜底。
            var importedGroups=new HashSet<Long>();
            for(var group:bundle.groups()) {
                if(group.importPath()!=null&&!group.importPath().isEmpty()
                        ||group.importNote()!=null&&!group.importNote().isBlank())importedGroups.add(group.id());
            }
            ObjectNode tree = JSON.valueToTree(bundle);
            for (JsonNode e : tree.path("environments")) {
                for (String scope : List.of("base","header","body")) for (JsonNode v : e.path("vars").path(scope)) ((ObjectNode)v).put("value","");
                ((ObjectNode)e.path("sign")).put("appSecret","");
            }
            for(JsonNode g:tree.path("groups")) if(g.hasNonNull("importNote"))((ObjectNode)g).put("importNote","");
            for (JsonNode c : tree.path("cases")) {
                ObjectNode o=(ObjectNode)c;
                if(o.path("note").asText().contains(ApiInterchange.POSTMAN_NOTE_PREFIX)
                        ||o.hasNonNull("groupId")&&importedGroups.contains(o.path("groupId").asLong()))
                    o.put("note","导入描述和脚本原文已省略；完整本机迁移请显式包含凭据");
                if(o.path("options").path("parts").isArray())for(JsonNode p:o.path("options").path("parts"))if(p.hasNonNull("value"))((ObjectNode)p).put("value","");
                for (String k : List.of("headers","body")) if (o.path(k).isTextual()) o.put(k, scrubText(o.path(k).asText()));
                if (o.path("url").isTextual()) o.put("url",scrubUrl(o.path("url").asText()));
                JsonNode options=o.get("options"); if (options!=null && options.isObject()) scrub(options,false);
            }
            scrubKnown(tree,explicitSecrets);
            try { return JSON.treeToValue(tree,Bundle.class); } catch(Exception e) { throw new IllegalStateException(e); }
        });
    }
    private static boolean sensitive(String k) { return k.matches("(?i).*(authorization|cookie|token|secret|password|passwd|api[_-]?key|access[_-]?key|sign).*"); }
    private static void scrub(JsonNode n, boolean all) {
        if(n.isObject()) {
            ObjectNode o=(ObjectNode)n;
            // Auth credentials and structured form/query pairs have schema-specific names.
            if (o.has("type") && o.path("script").isTextual()) o.put("script", "throw new Error('分享包未包含签名脚本，请重新填写');");
            if (o.has("type") && o.path("authorizationTemplate").isTextual()) o.put("authorizationTemplate", "{{AUTHORIZATION_REDACTED}}");
            if (o.has("type") && (o.has("token")||o.has("password")||o.has("keyValue"))) for(String k:List.of("token","password","keyValue")) if(o.has(k)) o.put(k,"");
            if(o.has("name")&&sensitive(o.path("name").asText())&&o.has("value")) o.put("value","");
            var names=new ArrayList<String>();o.fieldNames().forEachRemaining(names::add);
            for(String k:names) { JsonNode v=o.get(k); if((all||sensitive(k)) && !Set.of("cookieMode","cookieDataRows","cookieSession","authorizationTemplate").contains(k) && v.isValueNode() && !v.isNull() && !(k.equals("secret") && v.isBoolean())) o.put(k,""); else scrub(v,all||sensitive(k)); }
        } else if(n.isArray()) n.forEach(v->scrub(v,all));
    }
    private static void scrubKnown(JsonNode n,java.util.Set<String> values) {
        if(n.isObject()){var o=(ObjectNode)n;var fields=new ArrayList<String>();o.fieldNames().forEachRemaining(fields::add);for(String field:fields){var value=o.get(field);if(value.isTextual())o.put(field,ApiPrivacy.replace(value.asText(),values));else scrubKnown(value,values);}}
        else if(n.isArray()){var a=(com.fasterxml.jackson.databind.node.ArrayNode)n;for(int i=0;i<a.size();i++){var value=a.get(i);if(value.isTextual())a.set(i,com.fasterxml.jackson.databind.node.TextNode.valueOf(ApiPrivacy.replace(value.asText(),values)));else scrubKnown(value,values);}}
    }
    private static String scrubUrl(String text) {
        text=text.replaceFirst("(?i)^(https?://)[^/?#@]+@","$1");
        int query=text.indexOf('?'),hash=text.indexOf('#');if(query<0||hash>=0&&query>hash)return text;
        return text.substring(0,query+1)+scrubForm(text.substring(query+1,hash<0?text.length():hash))+(hash<0?"":text.substring(hash));
    }
    private static String scrubForm(String text){String[] values=text.split("&",-1);for(int i=0;i<values.length;i++){int eq=values[i].indexOf('=');if(eq<0)continue;String name=values[i].substring(0,eq);try{name=java.net.URLDecoder.decode(name,java.nio.charset.StandardCharsets.UTF_8);}catch(IllegalArgumentException ignored){}if(sensitive(name))values[i]=values[i].substring(0,eq+1);}return String.join("&",values);}
    private static String scrubText(String text) {
        try { JsonNode n=JSON.readTree(text);scrub(n,false);return JSON.writeValueAsString(n); }
        catch(Exception e) { return scrubForm(text).replaceAll("(?im)^((?:authorization|proxy-authorization|cookie|[^:\\r\\n]*(?:token|secret|password|api[_-]?key))\\s*:)\\s*[^\\r\\n]*","$1 "); }
    }
    private Bundle parse(String content) {
        if(content==null||content.length()>10_000_000) throw new IllegalArgumentException("配置包为空或超过10MB");
        try { Bundle value=JSON.readValue(content,Bundle.class);if(value==null)throw new IllegalArgumentException();return value; } catch(Exception e) { throw new IllegalArgumentException("配置包JSON格式或字段无效"); }
    }
    public Preview preview(String content) {
        try { return validate(parse(content)); } catch(RuntimeException e) {return new Preview(false,List.of(e.getMessage()==null?"配置包存在空对象或非法字段":e.getMessage()),List.of(),new Counts(0,0,0,0));}
    }
    private Preview validate(Bundle b) {
        var errors=new ArrayList<String>();var warnings=new ArrayList<String>();
        if(b.schema()!=1) errors.add("不支持的配置包schema："+b.schema()+"，当前仅支持1");
        if(b.environments()==null||b.groups()==null||b.cases()==null||b.scenarios()==null) return new Preview(false,List.of("配置包缺少集合"),warnings,new Counts(0,0,0,0));
        if(b.environments().size()>1000||b.cases().size()>10000||b.groups().size()>2000||b.scenarios().size()>2000) errors.add("配置包记录数超出导入上限");
        if(b.environments().contains(null)||b.groups().contains(null)||b.cases().contains(null)||b.scenarios().contains(null))return new Preview(false,List.of("配置包集合不可包含空对象"),warnings,new Counts(0,0,0,0));
        var env=ids(b.environments().stream().map(Environment::id).toList(),"环境",errors);var group=ids(b.groups().stream().map(ApiCaseGroup::id).toList(),"分组",errors);
        var cases=ids(b.cases().stream().map(ApiSpec::id).toList(),"用例",errors);ids(b.scenarios().stream().map(ApiScenario::id).toList(),"场景",errors);
        if(!env.contains(b.defaultEnvId())) errors.add("默认环境引用不存在");
        for(var e:b.environments()) {if(e.vars()==null||e.sign()==null) errors.add("环境缺少变量或签名配置");else for(var list:Arrays.asList(e.vars().base(),e.vars().header(),e.vars().body()))if(list==null||list.stream().anyMatch(v->v==null||v.name()==null||v.name().isBlank()))errors.add("环境变量集合或变量名称无效");name(e.name(),errors);}
        for(var g:b.groups()) {name(g.name(),errors);if(g.envId()!=null&&!env.contains(g.envId()))errors.add("分组环境引用不存在："+g.id());}
        for(var c:b.cases()) { if(c.method()==null||!Set.of("GET","POST","PUT","PATCH","DELETE","HEAD","OPTIONS").contains(c.method().toUpperCase(Locale.ROOT))||c.url()==null||c.url().isBlank())errors.add("用例方法或URL无效："+c.id()); if(c.groupId()!=null&&!group.contains(c.groupId())) errors.add("用例分组引用不存在："+c.id());
            try{ApiRequestEngine.validateOptions(c.options());ApiBodyAssert.validateRules(c.asserts());}catch(RuntimeException ex){errors.add("用例"+c.id()+"："+ex.getMessage());}}
        for(var s:b.scenarios()) { name(s.name(),errors);if(s.steps()==null||s.steps().isEmpty()||s.steps().size()>200) {errors.add("场景步骤数量无效");continue;}
            boolean cleanup=false;var keys=new HashSet<String>();
            for(var step:s.steps()) {if(step==null){errors.add("空场景步骤");continue;}if(!cases.contains(step.caseId()))errors.add("场景用例引用不存在："+s.id());if(step.envId()!=null&&!env.contains(step.envId()))errors.add("场景环境引用不存在："+s.id());
                if(step.key()==null||step.key().isBlank()||!keys.add(step.key()))errors.add("场景步骤key为空或重复");
                if(cleanup&&!step.cleanup())errors.add("清理步骤必须位于场景末尾");cleanup|=step.cleanup();
                if(step.onFailure()!=null&&!Set.of("STOP","CONTINUE").contains(step.onFailure()))errors.add("场景失败策略无效");
                try{ApiStepPolicy.validate(step);ApiResponseTools.validateExtractors(step.extractors());}catch(RuntimeException ex){errors.add(ex.getMessage());}
            }
        }
        var fileIds=new LinkedHashSet<String>();for(var c:b.cases()){var o=c.options();if(o==null)continue;if(o.fileBody()!=null)fileIds.add(o.fileBody().fileId());if(o.parts()!=null)for(var p:o.parts())if(p!=null&&p.fileId()!=null)fileIds.add(p.fileId());}
        boolean hasFiles=!jdbc.queryForList("SELECT name FROM sqlite_master WHERE type='table' AND name='api_file'",String.class).isEmpty();
        for(String id:fileIds){boolean exists=hasFiles&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM api_file WHERE id=?)",Boolean.class,id));warnings.add("文件引用 "+id+(exists?"：本机存在，配置包仍不包含文件原字节":"：待补传；导入后须重新上传并替换fileId，发送前会阻止空体请求"));}
        if(b.environments().stream().anyMatch(e->java.util.stream.Stream.of(e.vars().base(),e.vars().header(),e.vars().body()).flatMap(List::stream).anyMatch(v->v.value()!=null&&v.value().startsWith("secret://"))))warnings.add("包内有本机秘密引用，导入新环境后需在该环境重建引用；失配时会阻止发送，不会退回空凭据");
        if(!b.credentialsIncluded()) warnings.add("包内凭据已移除；导入后需重新配置变量值和鉴权");
        if(b.cases().stream().anyMatch(c->c.groupId()==null))warnings.add("未分组用例将放入新的导入默认组，保持原默认环境且不改变本机默认环境");
        return new Preview(errors.isEmpty(),List.copyOf(errors),List.copyOf(warnings),new Counts(b.environments().size(),b.groups().size()+(b.cases().stream().anyMatch(c->c.groupId()==null)?1:0),b.cases().size(),b.scenarios().size()));
    }
    private static Set<Long> ids(List<Long> ids,String label,List<String> errors) {var set=new HashSet<Long>();for(Long id:ids)if(id==null||id<=0||!set.add(id))errors.add(label+"ID为空、非正数或重复");return set;}
    private static void name(String n,List<String> errors){if(n==null||n.isBlank()||n.length()>60)errors.add("环境/分组/场景名称需为1至60字符");}
    private static String unique(String name,Set<String> names){String base=name.strip();String value=base;for(int i=2;!names.add(value);i++){String suffix=" (导入"+i+")";value=base.substring(0,Math.min(base.length(),60-suffix.length()))+suffix;}return value;}
    public Imported commit(String content) {
        Bundle b=parse(content);Preview p=validate(b);if(!p.valid())throw new IllegalArgumentException(String.join("；",p.errors()));
        return tx.execute(status->{
            var envMap=new LinkedHashMap<Long,Long>();var groupMap=new LinkedHashMap<Long,Long>();var caseMap=new LinkedHashMap<Long,Long>();var scenarioMap=new LinkedHashMap<Long,Long>();
            var envNames=new HashSet<>(repo.listEnvs().stream().map(ApiEnv::name).toList());var groupNames=new HashSet<>(repo.listCaseGroups().stream().map(ApiCaseGroup::name).toList());var scenarioNames=new HashSet<>(repo.listScenarios().stream().map(ApiScenario::name).toList());
            for(var e:b.environments()) {long id=repo.createEnv(unique(e.name(),envNames)).id();envMap.put(e.id(),id);repo.saveEnvironmentSettings(id,e.sign(),e.vars());repo.setEnvStrict(id,e.strictVariables());}
            for(var g:b.groups()){long id=repo.createCaseGroup(unique(g.name(),groupNames),envMap.get(g.envId()==null?b.defaultEnvId():g.envId())).id();groupMap.put(g.id(),id);repo.saveGroupImportInfo(id,g.importPath(),g.importNote());}
            Long fallback=b.cases().stream().anyMatch(c->c.groupId()==null)?repo.createCaseGroup(unique("导入默认组",groupNames),envMap.get(b.defaultEnvId())).id():null;
            for(var c:b.cases())caseMap.put(c.id(),repo.createCase(new ApiSpec(null,c.name(),c.method(),c.url(),c.headers(),c.body(),c.note(),c.expectStatus(),c.expectCode(),c.timeoutMs(),c.insecure(),c.enabled(),c.groupId()==null?fallback:groupMap.get(c.groupId()),c.asserts(),c.options())).id());
            for(var s:b.scenarios()) {var steps=s.steps().stream().map(x->new ApiScenario.Step(UUID.randomUUID().toString(),x.name(),caseMap.get(x.caseId()),x.envId()==null?null:envMap.get(x.envId()),x.onFailure(),x.cleanup(),x.extractors(),x.independent(),x.delayMs(),x.poll())).toList();scenarioMap.put(s.id(),repo.saveScenario(null,new ApiScenario(null,unique(s.name(),scenarioNames),steps)).id());}
            return new Imported(p.counts(),envMap,groupMap,caseMap,scenarioMap,p.warnings());
        });
    }
}
