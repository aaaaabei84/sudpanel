package com.sudpanel.api;
import java.util.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
/** 路径仅作用于报告，不修改原始执行证据。 */
@Repository
public class ApiReportPolicy {
    private final JdbcTemplate jdbc;
    public record Policy(List<String> sensitiveJsonPaths){}
    public ApiReportPolicy(JdbcTemplate jdbc){this.jdbc=jdbc;jdbc.execute("CREATE TABLE IF NOT EXISTS api_report_policy(id INTEGER PRIMARY KEY CHECK(id=1),paths TEXT NOT NULL)");}
    public Policy get(){var values=jdbc.queryForList("SELECT paths FROM api_report_policy WHERE id=1",String.class);if(values.isEmpty())return new Policy(List.of());try{return new Policy(new ObjectMapper().readValue(values.getFirst(),new ObjectMapper().getTypeFactory().constructCollectionType(List.class,String.class)));}catch(Exception e){throw new IllegalStateException("报告脱敏配置损坏",e);}}
    public Policy save(Policy p){if(p==null||p.sensitiveJsonPaths()!=null&&p.sensitiveJsonPaths().stream().anyMatch(java.util.Objects::isNull))throw new IllegalArgumentException("敏感路径不能包含null");var paths=p.sensitiveJsonPaths()==null?List.<String>of():List.copyOf(p.sensitiveJsonPaths());if(paths.size()>100)throw new IllegalArgumentException("敏感路径最多100条");for(String s:paths)if(s.length()>1000||!s.isEmpty()&&!s.startsWith("/")||s.matches(".*~(?![01]).*"))throw new IllegalArgumentException("敏感路径需为JSON Pointer，根路径为空字符串");jdbc.update("INSERT INTO api_report_policy(id,paths) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET paths=excluded.paths",ApiRequestEngine.stringify(paths));return new Policy(paths);}
}
