package com.sudpanel.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Explicit local OpenAPI response selection; never sends the described HTTP request. */
public final class ApiOpenApiSchema {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ApiOpenApiSchema() {}
    public record Extracted(String schema, String mediaType, String status, List<String> warnings) {}

    public static Extracted extract(String document, String path, String method, String status) {
        if (document == null || document.length() > 2_000_000) throw new IllegalArgumentException("OpenAPI JSON 最多200万字符");
        if (path == null || method == null || status == null) throw new IllegalArgumentException("请指定路径、方法和响应状态码");
        try {
            JsonNode root = JSON.readTree(document);
            String version = root.path("openapi").asText();
            if (!version.startsWith("3.0.") && !version.startsWith("3.1.")) throw new IllegalArgumentException("仅支持 OpenAPI 3.0 / 3.1 JSON");
            JsonNode operation = root.path("paths").path(path).path(method.toLowerCase(java.util.Locale.ROOT));
            if (!operation.isObject()) throw new IllegalArgumentException("找不到指定的 OpenAPI 路径/方法");
            JsonNode response = resolve(root, operation.path("responses").path(status), new HashSet<>(), 0);
            if (!response.isObject()) throw new IllegalArgumentException("找不到指定响应状态，请按文档填写200、2XX或default");
            JsonNode content = response.path("content");
            String media = content.has("application/json") ? "application/json" : null;
            var names = content.fieldNames();
            while (media == null && names.hasNext()) { String name=names.next(); if (name.endsWith("+json")) media=name; }
            if (media == null || !content.path(media).has("schema")) throw new IllegalArgumentException("该响应没有 JSON Schema");
            JsonNode expanded = expand(root, content.path(media).path("schema"), new HashSet<>(), 0, new int[1], version.startsWith("3.0."), true);
            String text = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(expanded);
            ApiJsonSchema.validateConfiguration(text);
            return new Extracted(text, media, status, List.of("只提取所选响应，不执行接口；format仅作注释，外部/循环引用不自动读取。",
                    "Schema只约束结构，不代替业务码、实际对局归属或响应状态断言。"));
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("OpenAPI Schema 无法提取：" + error.getMessage()); }
    }

    private static JsonNode resolve(JsonNode root, JsonNode node, Set<String> visiting, int depth) {
        if (depth > 32) throw new IllegalArgumentException("OpenAPI 引用最多32层");
        if (!node.has("$ref")) return node;
        String ref = node.path("$ref").asText();
        if (!ref.startsWith("#/")) throw new IllegalArgumentException("只允许本文件 #/ 引用，禁止读取外部文件/URL");
        if (!visiting.add(ref)) throw new IllegalArgumentException("循环引用需改为有限 Schema 后导入");
        JsonNode target = root.at(URLDecoder.decode(ref.substring(1).replace("+", "%2B"), StandardCharsets.UTF_8));
        if (target.isMissingNode()) throw new IllegalArgumentException("本地引用不存在：" + ref);
        JsonNode resolved = resolve(root,target,visiting,depth+1); visiting.remove(ref); return resolved;
    }

    private static JsonNode expand(JsonNode root, JsonNode node, Set<String> visiting, int depth, int[] count, boolean old, boolean schemaNode) {
        if (depth > 32 || ++count[0] > 2000) throw new IllegalArgumentException("OpenAPI Schema 展开超过32层或2000节点");
        if (node.isObject()) {
            if (schemaNode && node.has("$ref")) {
                String ref=node.path("$ref").asText();
                if (!ref.startsWith("#/") || !visiting.add(ref)) throw new IllegalArgumentException("Schema 只支持有限本文件引用，外部或循环引用拒绝");
                JsonNode target=root.at(URLDecoder.decode(ref.substring(1).replace("+","%2B"),StandardCharsets.UTF_8));
                if (target.isMissingNode()) throw new IllegalArgumentException("Schema 本地引用不存在："+ref);
                JsonNode result=expand(root,target,visiting,depth+1,count,old,true); visiting.remove(ref);
                if (old || node.size()==1) return result;
                ObjectNode siblings=node.deepCopy();siblings.remove("$ref");
                return JSON.createObjectNode().set("allOf",JSON.createArrayNode().add(result).add(expand(root,siblings,visiting,depth+1,count,false,true)));
            }
            ObjectNode result=JSON.createObjectNode();
            var fields=node.fields();
            while(fields.hasNext()){
                var f=fields.next();String key=f.getKey();JsonNode value=f.getValue();
                if(schemaNode && Set.of("properties","patternProperties","$defs","definitions","dependentSchemas","dependencies").contains(key) && value.isObject()) {
                    ObjectNode map=JSON.createObjectNode();var entries=value.fields();
                    while(entries.hasNext()){var entry=entries.next();map.set(entry.getKey(),expand(root,entry.getValue(),visiting,depth+1,count,old,true));}
                    result.set(key,map);
                } else if(schemaNode && Set.of("allOf","anyOf","oneOf","prefixItems").contains(key) && value.isArray()) {
                    var array=JSON.createArrayNode();for(JsonNode child:value)array.add(expand(root,child,visiting,depth+1,count,old,true));result.set(key,array);
                } else if(schemaNode && Set.of("items","contains","additionalProperties","unevaluatedProperties","propertyNames","not","if","then","else","additionalItems","unevaluatedItems","contentSchema").contains(key)) {
                    result.set(key,expand(root,value,visiting,depth+1,count,old,true));
                } else { result.set(key,value.deepCopy()); }
            }
            if(schemaNode && result.path("required").isArray() && result.path("properties").isObject()) {
                var required=JSON.createArrayNode();for(var property:result.path("required"))if(!result.path("properties").path(property.asText()).path("writeOnly").asBoolean(false))required.add(property.deepCopy());result.set("required",required);
            }
            if (old && schemaNode) {
                boolean nullable=result.path("nullable").asBoolean(false);result.remove("nullable");
                for(String bound:List.of("Minimum","Maximum")) if(result.path("exclusive"+bound).isBoolean()) {
                    boolean exclusive=result.path("exclusive"+bound).asBoolean();result.remove("exclusive"+bound);
                    String numeric=bound.toLowerCase(java.util.Locale.ROOT);
                    if(exclusive && result.has(numeric)){result.set("exclusive"+bound,result.get(numeric));result.remove(numeric);}
                }
                if(nullable && result.path("type").isTextual())result.set("type",JSON.createArrayNode().add(result.path("type").asText()).add("null"));
            }
            return result;
        }
        if(node.isArray()){var array=JSON.createArrayNode();for(JsonNode child:node)array.add(expand(root,child,visiting,depth+1,count,old,schemaNode));return array;}
        return node.deepCopy();
    }
}
