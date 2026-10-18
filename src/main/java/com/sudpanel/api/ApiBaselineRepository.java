package com.sudpanel.api;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 基线保存独立快照，不随执行历史清理丢失；生成规则只返回草稿。 */
@Repository
public class ApiBaselineRepository {
    private final JdbcTemplate jdbc;private final ApiHistoryRepository history;
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public record Baseline(long id,String name,long sourceExecId,List<String> ignorePaths,String createdAt){}
    public record Generated(String expectStatus,String expectCode,List<ApiBodyAssert.Rule> rules,List<String> warnings){}
    public ApiBaselineRepository(JdbcTemplate jdbc,ApiHistoryRepository history){this.jdbc=jdbc;this.history=history;jdbc.execute("CREATE TABLE IF NOT EXISTS api_baseline(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,source_exec_id INTEGER NOT NULL,exec_json TEXT NOT NULL,ignore_paths TEXT NOT NULL,created_at TEXT NOT NULL)");}
    public List<Baseline> list(){return jdbc.query("SELECT id,name,source_exec_id,ignore_paths,created_at FROM api_baseline ORDER BY id DESC",(r,i)->new Baseline(r.getLong(1),r.getString(2),r.getLong(3),paths(r.getString(4)),r.getString(5)));}
    public synchronized Baseline create(String name,long execId,List<String> ignore){
        if(name==null||name.isBlank()||name.strip().length()>60)throw new IllegalArgumentException("基线名称为1至60字符");
        ApiExec e=history.detail(execId);var normalized=ignore==null?List.<String>of():new ArrayList<>(ignore);ApiHistoryCompare.compare(e,e,normalized);
        var holder=new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(c->{var ps=c.prepareStatement("INSERT INTO api_baseline(name,source_exec_id,exec_json,ignore_paths,created_at) VALUES(?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS);ps.setString(1,name.strip());ps.setLong(2,execId);ps.setString(3,write(e));ps.setString(4,write(normalized));ps.setString(5,Instant.now().toString());return ps;},holder);
        long id=Objects.requireNonNull(holder.getKey()).longValue();return list().stream().filter(b->b.id()==id).findFirst().orElseThrow();
    }
    public void delete(long id){if(jdbc.update("DELETE FROM api_baseline WHERE id=?",id)==0)throw new IllegalArgumentException("基线不存在");}
    public ApiHistoryCompare.Compared compare(long id,long execId){var row=row(id);return ApiPrivacy.compare(read(row.get("exec_json").toString(),ApiExec.class),history.detail(execId),paths(row.get("ignore_paths").toString()),jdbc,false);}
    private Map<String,Object> row(long id){return jdbc.queryForList("SELECT * FROM api_baseline WHERE id=?",id).stream().findFirst().orElseThrow(()->new IllegalArgumentException("基线不存在"));}
    public Generated assertions(long id){
        var row=row(id);ApiExec e=read(row.get("exec_json").toString(),ApiExec.class);var ignore=paths(row.get("ignore_paths").toString());
        var cmp=ApiHistoryCompare.compare(e,e,ignore);if(!cmp.complete())throw new IllegalArgumentException("此基线响应不完整或完整性未知，不能生成完整响应断言");
        JsonNode body=ApiHistoryCompare.parse(e.respBody());if(body==null)throw new IllegalArgumentException("基线响应不是JSON，不能生成JSON路径断言");
        var rules=new ArrayList<ApiBodyAssert.Rule>();var warnings=new ArrayList<String>();walk(body,"",ignore,rules,0);
        if(rules.size()>50){rules.subList(50,rules.size()).clear();warnings.add("仅生成前50条规则，其余路径已省略；请按业务需要编辑");}
        warnings.add("只生成可编辑草稿，未自动保存用例；数组按索引并校验长度，动态字段请编辑或忽略");
        return new Generated(e.httpStatus()==null?null:e.httpStatus().toString(),e.respCode(),List.copyOf(rules),List.copyOf(warnings));
    }
    private static void walk(JsonNode n,String path,List<String> ignore,List<ApiBodyAssert.Rule> out,int depth){
        if(out.size()>50||depth>128||ignore.stream().anyMatch(x->x.isEmpty()||path.equals(x)||path.startsWith(x+"/")))return;
        String type=n.isNull()?"null":n.isObject()?"object":n.isArray()?"array":n.isTextual()?"string":n.isBoolean()?"boolean":n.isIntegralNumber()?"integer":"number";
        out.add(new ApiBodyAssert.Rule(path,"type",type));
        if(n.isArray()){out.add(new ApiBodyAssert.Rule(path,"length",Integer.toString(n.size())));for(int i=0;i<n.size();i++)walk(n.get(i),path+"/"+i,ignore,out,depth+1);}
        else if(n.isObject())n.fields().forEachRemaining(f->walk(f.getValue(),path+"/"+f.getKey().replace("~","~0").replace("/","~1"),ignore,out,depth+1));
        else if(!n.isNull()){if(n.isTextual()&&!n.asText().equals(n.asText().trim())){String pattern="\\A"+java.util.regex.Pattern.quote(n.asText())+"\\z";if(pattern.length()<=SafeRegex.MAX_PATTERN_CHARS)out.add(new ApiBodyAssert.Rule(path,"regex",pattern));else throw new IllegalArgumentException("基线字符串含首尾空白且过长，无法生成准确相等规则，请手动配置");}else out.add(new ApiBodyAssert.Rule(path,"equals",n.isTextual()?n.asText():n.toString()));}
    }
    private static String write(Object x){try{return JSON.writeValueAsString(x);}catch(Exception e){throw new IllegalStateException(e);}}
    private static <T>T read(String x,Class<T> c){try{return JSON.readValue(x,c);}catch(Exception e){throw new IllegalStateException("基线快照损坏",e);}}
    private static List<String> paths(String s){try{return JSON.readValue(s,JSON.getTypeFactory().constructCollectionType(List.class,String.class));}catch(Exception e){throw new IllegalStateException("基线路径损坏",e);}}
}
