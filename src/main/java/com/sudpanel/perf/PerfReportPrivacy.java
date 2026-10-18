package com.sudpanel.perf;

import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 分享报告隐藏已知凭据及常见鉴权文本，本机步骤详情仍保留完整取证。 */
final class PerfReportPrivacy {
 private PerfReportPrivacy(){}
 static String redact(String report,PerfExecutionPlan plan) {
  var secrets=new TreeSet<String>(Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo));
  if(plan!=null)for(var request:plan.requests().values()){
   var env=request.environment();if(env.sign()!=null)add(secrets,env.sign().appSecret());
   var auth=request.spec().options()==null?null:request.spec().options().auth();if(auth!=null){add(secrets,auth.token());add(secrets,auth.password());add(secrets,auth.keyValue());}
   for(var values:List.of(env.vars().base(),env.vars().header(),env.vars().body()))for(var value:values)if(Boolean.TRUE.equals(value.secret())||secretName(value.name()))add(secrets,value.value());
   try {var headers=new ObjectMapper().readTree(request.spec().headers());if(headers!=null&&headers.isObject())headers.fields().forEachRemaining(entry->{if(secretName(entry.getKey()))add(secrets,entry.getValue().asText());});}catch(Exception ignored){}
  }
  for(String secret:secrets){report=report.replace(secret,"[已隐藏]");String escaped=secret.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");report=report.replace(escaped,"[已隐藏]");}
  return report.replaceAll("(?i)\\bBearer\\s+[^\\s<>,\\\"']+","Bearer [已隐藏]")
   .replaceAll("(?i)((?:password|app[_-]?secret|access[_-]?token|api[_-]?key)\\s*[:=]\\s*[\\\"']?)[^\\s<>,\\\"']+","$1[已隐藏]");
 }
 private static boolean secretName(String name){return name!=null&&name.toLowerCase(Locale.ROOT).matches(".*(?:authorization|cookie|password|secret|token|api[_-]?key).*");}
 private static void add(Set<String> secrets,String value){if(value!=null&&!value.isBlank())secrets.add(value);}
}
