package com.sudpanel.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 响应字段检查与取值；valueJson 始终是字符串，前端无需转换大整数。 */
public final class ApiResponseTools {
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ApiResponseTools() {}

    public static final Limits LIMITS=new Limits(500,32,4096,160);
    public record Limits(int maxFields,int maxDepth,int maxValueChars,int previewChars) {}
    public record Field(String path,String type,String valueJson,String preview,boolean selectable,boolean truncated,boolean typeSelectable) {
        public Field(String path,String type,String valueJson,String preview,boolean selectable,boolean truncated){this(path,type,valueJson,preview,selectable,truncated,true);}
    }
    /** truncated只表示结构字段确有省略；大值省略独立列在truncationReasons及Field.truncated。 */
    public record Inspection(boolean validJson,List<Field> fields,String error,boolean truncated,List<String> truncationReasons,Limits limits) {
        public Inspection(boolean validJson,List<Field>fields,String error,boolean truncated){this(validJson,fields,error,truncated,List.of(),LIMITS);}
    }
    public record PathInspection(boolean found,List<Field> fields,String error,List<String> truncationReasons,Limits limits) {}
    public record Extraction(String name,String source,String path,boolean ok,boolean required,String valueJson,String error,boolean secret) {
        public Extraction(String name,String source,String path,boolean ok,boolean required,String valueJson,String error){this(name,source,path,ok,required,valueJson,error,false);}
    }

    public static Inspection inspect(String body) {
        if(ApiStructuredBody.overLimit(body))return new Inspection(false,List.of(),ApiStructuredBody.MESSAGE,false,List.of("STRUCTURED_BODY_LIMIT"),LIMITS);
        try {
            JsonNode root=JSON.readTree(body==null?"":body);
            if(root==null)return new Inspection(false,List.of(),"响应体为空",false);
            var fields=new ArrayList<Field>();var reasons=new java.util.LinkedHashSet<String>();
            flatten(root,"","",fields,0,reasons);
            return inspection(true,fields,null,reasons);
        }catch(Exception e){return new Inspection(false,List.of(),"响应体不是有效 JSON",false);}
    }
    public static Inspection inspect(String body,Map<String,List<String>>headers) {
        var original=inspect(body);var fields=new ArrayList<>(original.fields());var reasons=new java.util.LinkedHashSet<>(original.truncationReasons());
        if(headers!=null)for(var header:headers.entrySet()) {
            if(header.getKey()==null||header.getValue()==null)continue;
            if(fields.size()>=LIMITS.maxFields()){reasons.add("FIELD_LIMIT");break;}
            fields.add(field("header:"+header.getKey(),JSON.getNodeFactory().textNode(String.join(", ",header.getValue())),reasons));
        }
        return inspection(original.validJson(),fields,original.error(),reasons);
    }
    private static Inspection inspection(boolean valid,List<Field>fields,String error,java.util.Set<String> reasons){return new Inspection(valid,List.copyOf(fields),error,reasons.contains("FIELD_LIMIT")||reasons.contains("DEPTH_LIMIT"),List.copyOf(reasons),LIMITS);}
    private static Field field(String path,JsonNode node,java.util.Set<String>reasons) {
        String value=node.toString();boolean large=value.length()>LIMITS.maxValueChars();if(large)reasons.add("LARGE_VALUE");
        return new Field(path,type(node),large?null:value,value.length()<=LIMITS.previewChars()?value:value.substring(0,LIMITS.previewChars())+"…",!large,large,true);
    }
    private static void flatten(JsonNode node,String path,String pointer,List<Field>out,int depth,java.util.Set<String>reasons) {
        if(out.size()>=LIMITS.maxFields()){reasons.add("FIELD_LIMIT");return;}
        if(depth>LIMITS.maxDepth()){reasons.add("DEPTH_LIMIT");return;}
        out.add(field(path.isEmpty()?"$":path,node,reasons));
        if(node.isObject()) {
            var it=node.fields();while(it.hasNext()){var e=it.next();String part=e.getKey();String childPointer=pointer+"/"+part.replace("~","~0").replace("/","~1");String childPath=part.matches("[A-Za-z_][A-Za-z0-9_]*")&&!path.startsWith("/")?(path.isEmpty()?part:path+"."+part):childPointer;
                flatten(e.getValue(),childPath,childPointer,out,depth+1,reasons);
                if(out.size()>=LIMITS.maxFields()&&it.hasNext()){reasons.add("FIELD_LIMIT");break;}
            }
        }else if(node.isArray())for(int i=0;i<node.size();i++){
            flatten(node.get(i),path.startsWith("/")?pointer+"/"+i:path+"["+i+"]",pointer+"/"+i,out,depth+1,reasons);
            if(out.size()>=LIMITS.maxFields()&&i+1<node.size()){reasons.add("FIELD_LIMIT");break;}
        }
    }
    /** 手输路径不受自动展开深度32限制；单值仍使用同一大值上限。 */
    public static PathInspection readPath(String body,Map<String,List<String>>headers,String path) {
        if(path==null||path.isBlank()||path.length()>4096)throw new IllegalArgumentException("路径不能为空且最多4096字符");
        var reasons=new java.util.LinkedHashSet<String>();var fields=new ArrayList<Field>();
        try {
            if(path.startsWith("header:")){String name=path.substring(7);var value=headers==null?null:headers.entrySet().stream().filter(e->e.getKey()!=null&&e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(null);
                if(value==null)return new PathInspection(false,List.of(),"响应头不存在",List.of(),LIMITS);
                fields.add(field(path,JSON.getNodeFactory().textNode(String.join(", ",value)),reasons));
            }else {
                var values=ApiBodyAssert.readValues(body,path);int count=Math.min(values.size(),LIMITS.maxFields());
                for(int i=0;i<count;i++)fields.add(field(path,values.get(i),reasons));
                if(values.size()>count)reasons.add("FIELD_LIMIT");
            }
            return new PathInspection(!fields.isEmpty(),List.copyOf(fields),fields.isEmpty()?"路径不存在":null,List.copyOf(reasons),LIMITS);
        }catch(Exception e){return new PathInspection(false,List.of(),e.getMessage(),List.of(),LIMITS);}
    }

    private static String type(JsonNode node) {
        if (node.isNull()) return "null";
        if (node.isTextual()) return "string";
        if (node.isIntegralNumber()) return "integer";
        if (node.isNumber()) return "number";
        if (node.isBoolean()) return "boolean";
        return node.isArray() ? "array" : "object";
    }

    public static void validateExtractors(List<ApiExtractor> extractors) {
        if (extractors == null) return;
        if (extractors.size() > 50) throw new IllegalArgumentException("提取规则最多 50 条");
        var names = new java.util.HashSet<String>();
        for (ApiExtractor rule : extractors) {
            if (rule == null || rule.name() == null || !rule.name().matches("[A-Za-z_][A-Za-z0-9_.-]*")
                    || rule.name().startsWith("run.") || !names.add(rule.name()))
                throw new IllegalArgumentException("提取变量名无效或重复，填写 run. 后面的名称");
            if (!java.util.Set.of("JSON", "HEADER").contains(rule.source() == null ? "JSON" : rule.source()))
                throw new IllegalArgumentException("提取来源仅支持 JSON / HEADER");
            if ("HEADER".equals(rule.source()) && (rule.path() == null || rule.path().isBlank()))
                throw new IllegalArgumentException("响应头提取需要填写名称");
        }
    }

    /** 每步先清掉目标名，失败时不会沿用之前的同名值。 */
    public static List<Extraction> extract(String body, Map<String, List<String>> headers,
            List<ApiExtractor> rules, Map<String, String> variables) {
        if (rules == null || rules.isEmpty()) return List.of();
        validateExtractors(rules);
        rules.forEach(rule -> variables.remove(rule.name()));
        List<Extraction> out = new ArrayList<>();
        JsonNode bodyRoot=null;IllegalArgumentException bodyFailure=null;boolean bodyRead=false;
        for (ApiExtractor rule : rules) {
            boolean required = !Boolean.FALSE.equals(rule.required());
            String source = rule.source() == null ? "JSON" : rule.source();
            try {
                JsonNode node;
                if ("HEADER".equals(source)) {
                    String value = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(rule.path()))
                            .map(e -> String.join(", ", e.getValue())).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("响应头不存在"));
                    node = JSON.getNodeFactory().textNode(value);
                } else {
                    if(!bodyRead){bodyRead=true;try{bodyRoot=ApiBodyAssert.readValues(body,"").getFirst();}catch(IllegalArgumentException failure){bodyFailure=failure;}}
                    if(bodyFailure!=null)throw bodyFailure;
                    List<JsonNode> values = ApiBodyAssert.readValues(bodyRoot, rule.path());
                    if (values.size() != 1) throw new IllegalArgumentException("提取需要单个值，请使用明确的数组下标");
                    node = values.getFirst();
                }
                if (node.isNull()) throw new IllegalArgumentException("提取值为 null");
                variables.put(rule.name(), node.toString());
                if(variables instanceof ApiRunVariables marked)marked.secret(rule.name(),Boolean.TRUE.equals(rule.secret()));
                out.add(new Extraction(rule.name(), source, rule.path(), true, required, node.toString(), null,Boolean.TRUE.equals(rule.secret())));
            } catch (Exception e) {
                out.add(new Extraction(rule.name(), source, rule.path(), false, required, null, e.getMessage()));
            }
        }
        return out;
    }
}
