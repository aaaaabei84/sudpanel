package com.sudpanel.api;

import java.util.*;

/** 用户头按行保留顺序；HTTP受限头不能通过关闭行绕过保存期校验。 */
public final class ApiHeaders {
    private ApiHeaders() {}
    private static final Set<String> RESTRICTED=Set.of("host","content-length","connection","expect","upgrade","http2-settings","transfer-encoding","trailer",":authority");
    private static final Set<String> SINGLE=Set.of("authorization","proxy-authorization","cookie","content-type","user-agent");
    public static void validate(String name,String value) {
        if(name==null||name.length()>256||!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))throw new IllegalArgumentException("请求头名称无效");
        if(RESTRICTED.contains(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("请求头 "+name+" 由HTTP客户端管理，不能手动设置");
        if(value!=null&&value.length()>65536)throw new IllegalArgumentException("单请求头值最多65536字符");
        if(value!=null&&(value.indexOf('\r')>=0||value.indexOf('\n')>=0||value.indexOf('\0')>=0))throw new IllegalArgumentException("请求头值不能包含换行或NUL");
    }
    public static void validateRows(List<ApiRequestOptions.HeaderRow> rows) {
        if(rows==null)return;
        if(rows.size()>200)throw new IllegalArgumentException("请求头最多200行");
        var seen=new HashSet<String>();long bytes=0;
        for(var row:rows){if(row==null)throw new IllegalArgumentException("请求头行不能为null");if(row.name()!=null&&row.name().contains("{{")){validate("X-Template-Name",row.value());}else validate(row.name(),row.value());
            bytes+=row.name().getBytes(java.nio.charset.StandardCharsets.UTF_8).length+(row.value()==null?0:row.value().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);if(bytes>262144)throw new IllegalArgumentException("请求头累计最多256KiB");
            String key=row.name().toLowerCase(Locale.ROOT);
            if(!Boolean.FALSE.equals(row.enabled())&&SINGLE.contains(key)&&!seen.add(key))throw new IllegalArgumentException("请求头 "+row.name()+" 只能有一个启用值；普通自定义头可按行重复");}
    }
    public static void validateSaved(String raw, ApiRequestOptions options) {
        if (options != null && options.headerRows() != null) { validateRows(options.headerRows()); return; }
        // Legacy templates may use names such as {{header_name}}; validate their final name at preview/send.
        if (raw == null || raw.isBlank()) return;
        com.fasterxml.jackson.databind.JsonNode node;
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            node = mapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(raw);
        } catch (Exception legacyDraft) {
            // 原始模式保留可保存的未完成草稿；预览/执行仍由parseHeaders拒绝，且不会发网络请求。
            return;
        }
        if (node == null || !node.isObject()) return;
        var fields = node.fields();
        while (fields.hasNext()) { var field = fields.next();
            String name=field.getKey(), value=field.getValue().isTextual()?field.getValue().asText():field.getValue().toString();
            if (!name.contains("{{")) validate(name,value);
            else if (value.indexOf('\r')>=0 || value.indexOf('\n')>=0 || value.indexOf('\0')>=0) throw new IllegalArgumentException("请求头值不能包含换行或NUL");
        }
    }
    public static List<ApiRequestOptions.HeaderRow> fromMap(Map<String,String> headers) {return headers.entrySet().stream().map(e->new ApiRequestOptions.HeaderRow(e.getKey(),e.getValue(),true)).toList();}
}
