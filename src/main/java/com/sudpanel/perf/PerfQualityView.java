package com.sudpanel.perf;

import java.util.*;

/** 报告只投影有限质量字段，不能把未来新增的原始文本隐式导出。 */
public final class PerfQualityView {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private PerfQualityView(){}
    public static String health(Object value){if(!(value instanceof Map<?,?> m))return "未记录";return "温度 "+number(m.get("temperatureC"))+"℃ / 供电 "+(m.get("powered") instanceof Boolean on?(on?"外部供电":"电池"):"未知")+" / thermal "+number(m.get("thermalStatus"));}
    public static String summary(Map<String,Object> q){if(!"RECORDED".equals(q.get("state")))return "旧来源未记录或证据尚未保存";return "起始["+health(q.get("startHealth"))+"]；结束["+health(q.get("endHealth"))+"]；连续性="+q.get("continuity")+"；点数="+number(q.get("sampleCount"))+"；实测间隔="+number(q.get("actualIntervalAvgMs"))+"ms；采集均耗时="+number(q.get("acquisitionAvgMs"))+"ms；跳过槽位="+number(q.get("skippedSlots"))+"；缺失率="+missing(q.get("missing"));}
    private static String missing(Object value){if(!(value instanceof Map<?,?> map))return "未知";var parts=new ArrayList<String>();for(String key:List.of("pss","cpu","threads","ws","tcp")){Object item=map.get(key);parts.add(key+":"+(item instanceof Map<?,?> entry?number(entry.get("percent")):"未知")+"%");}return String.join(" / ",parts);}
    public static String number(Object value){return value instanceof Number n?String.format(Locale.ROOT,"%.2f",n.doubleValue()).replaceAll("\\.00$",""):"未知";}
    public static Map<String,Object> sample(String text){try{if(text==null)return Map.of();Map<String,Object> raw=JSON.readValue(text,Map.class);var safe=new LinkedHashMap<String,Object>();for(String key:List.of("durationMs","offsetMs","intervalMs"))if(raw.get(key) instanceof Number)safe.put(key,raw.get(key));var states=new TreeMap<String,String>();if(raw.get("sources") instanceof Map<?,?> sources)for(String key:List.of("meminfo","status","top","threads","cores","tcp","process","pss","rss","cpu","ws","net")){if(sources.get(key) instanceof Map<?,?> source&&source.get("status") instanceof String status&&Set.of("OK","MISSING","DENIED","ERROR","INCOMPLETE","CHANGED").contains(status))states.put(key,status);}safe.put("sourceStatuses",states.toString());return safe;}catch(Exception ignored){return Map.of();}}
}
