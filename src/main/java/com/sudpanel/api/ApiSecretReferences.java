package com.sudpanel.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** Select only environment slots that the one-pass request resolver can consume. Never resolve template values recursively. */
final class ApiSecretReferences {
    private static final Pattern TOKEN=Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");
    static Map<String,Set<String>> used(ApiSpec spec,ApiVariables.VarSet vars) {
        var url=new HashSet<String>();var header=new HashSet<String>();var body=new HashSet<String>();
        scan(spec.url(),url);
        JsonNode options=new ObjectMapper().valueToTree(spec.options());
        if(options==null || options.isNull())options=new ObjectMapper().createObjectNode();
        if(options.path("headerRows").isArray())rows(options.path("headerRows"),header,"name","value");else scan(spec.headers(),header);
        rows(options.path("params"),url,"name","value");
        String kind=options.path("bodyType").asText("LEGACY");
        if(List.of("LEGACY","JSON","RAW").contains(kind))scan(spec.body(),body);
        if(kind.equals("URLENCODED"))rows(options.path("fields"),body,"name","value");
        if(kind.equals("MULTIPART") && options.path("parts").isArray())for(var part:options.path("parts"))if(!part.path("enabled").isBoolean()||part.path("enabled").asBoolean()){scan(part.path("name").asText(null),body);scan(part.path("contentType").asText(null),body);if(part.path("type").asText().equals("FILE"))scan(part.path("filename").asText(null),body);else scan(part.path("value").asText(null),body);}
        JsonNode auth=options.path("auth");String type=auth.path("type").asText("INHERIT");
        Set<String> group=type.equals("API_KEY")&&auth.path("in").asText().equals("QUERY")?url:header;
        switch(type){case "BEARER" -> scan(auth.path("token").asText(null),group);case "BASIC" -> {scan(auth.path("username").asText(null),group);scan(auth.path("password").asText(null),group);}case "API_KEY" -> {scan(auth.path("keyName").asText(null),group);scan(auth.path("keyValue").asText(null),group);}default -> {}}
        if(type.equals("SCRIPT")||type.equals("TEMPLATE"))scan(auth.path("authorizationTemplate").asText(null),header);
        if(type.equals("SCRIPT")||type.equals("TEMPLATE")) {
            // 仅预选明确字面get；计算出来的名称若仍为引用，worker会拒绝，不用引用文本代替密钥。
            var names=new HashSet<String>();names.addAll(url);names.addAll(header);names.addAll(body);var source=auth.path("script").asText("");
            var get=Pattern.compile("pm\\.(?:environment|variables)\\.(?:get|has)\\(\\s*[\"']([^\"']+)[\"']\\s*\\)").matcher(source);
            while(get.find())names.add(get.group(1));
            scan(source,names);scan(auth.path("authorizationTemplate").asText(null),names);
            for(var row:vars.base())if(names.contains(row.name()))url.add(row.name());
            for(var row:vars.header())if(names.contains(row.name()))header.add(row.name());
            for(var row:vars.body())if(names.contains(row.name()))body.add(row.name());
        }
        for(var rule:ApiBodyAssert.parse(spec.asserts()))if(!"schema".equalsIgnoreCase(rule.op()))scan(rule.expect(),body);
        var base=new HashSet<String>();for(var v:vars.base())if(v.value()!=null)base.add(v.name());
        var usedBase=new HashSet<>(url);usedBase.retainAll(base);url.removeAll(base);header.addAll(url);
        return Map.of("base",usedBase,"header",header,"body",body);
    }
    private static void rows(JsonNode rows,Set<String> group,String... fields){if(!rows.isArray())return;for(var row:rows)if(!row.path("enabled").isBoolean()||row.path("enabled").asBoolean())for(String field:fields)scan(row.path(field).asText(null),group);}
    private static void scan(String text,Set<String> group){if(text==null)return;var m=TOKEN.matcher(text);while(m.find()){String name=m.group(1).trim();if(!name.startsWith("!")&&!name.startsWith("$")&&!name.startsWith("run.")&&!name.startsWith("data."))group.add(name);}}
}
