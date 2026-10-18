package com.sudpanel.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

/** CSV 保留字符串，JSON 保留原类型与长整数；值不写回环境。 */
public final class ApiDataSet {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    public record Input(String format, String content) {}
    public record Data(List<String> columns, List<Map<String, String>> rows) {}
    private ApiDataSet() {}
    public static Data parse(Input input) {
        if (input == null || input.content() == null || input.content().isBlank()) throw new IllegalArgumentException("请填写数据集");
        if (input.content().length() > 2 * 1024 * 1024) throw new IllegalArgumentException("数据集最多 2Mi 字符");
        String text = input.content().startsWith("\uFEFF") ? input.content().substring(1) : input.content();
        var rows = new ArrayList<Map<String, String>>();
        var columns = new java.util.LinkedHashSet<String>();
        if ("JSON".equals(input.format())) {
            try {
                var array = JSON.readTree(text);
                if (!array.isArray()) throw new IllegalArgumentException("JSON 数据集必须为对象数组");
                for (var node : array) {
                    if (!node.isObject()) throw new IllegalArgumentException("每行 JSON 数据须为对象");
                    var row = new LinkedHashMap<String, String>();
                    node.fields().forEachRemaining(e -> { validateName(e.getKey()); columns.add(e.getKey()); row.put(e.getKey(), e.getValue().toString()); });
                    rows.add(Map.copyOf(row));
                }
            } catch (IllegalArgumentException e) { throw e; }
            catch (Exception e) { throw new IllegalArgumentException("JSON 数据集格式无效"); }
        } else if ("CSV".equals(input.format())) {
            List<List<String>> table = csv(text);
            if (table.isEmpty()) throw new IllegalArgumentException("CSV 需要表头与数据行");
            var header = table.getFirst().stream().map(String::trim).toList();
            for (String name : header) { validateName(name); if (!columns.add(name)) throw new IllegalArgumentException("CSV 表头重复：" + name); }
            for (int i = 1; i < table.size(); i++) {
                var values = table.get(i);
                if (values.size() != header.size()) throw new IllegalArgumentException("CSV 第 " + (i + 1) + " 行列数与表头不同");
                var row = new LinkedHashMap<String, String>();
                for (int j = 0; j < header.size(); j++) row.put(header.get(j), JSON.getNodeFactory().textNode(values.get(j)).toString());
                rows.add(Map.copyOf(row));
            }
        } else throw new IllegalArgumentException("数据格式仅支持 CSV / JSON");
        if (rows.isEmpty() || rows.size() > 1000) throw new IllegalArgumentException("数据集需要 1～1000 行");
        if (columns.isEmpty() || columns.size() > 200) throw new IllegalArgumentException("数据集需要 1～200 列");
        return new Data(List.copyOf(columns), List.copyOf(rows));
    }
    private static void validateName(String name) {
        if (!name.matches("[\\p{L}_][\\p{L}\\p{N}_.-]{0,127}")) throw new IllegalArgumentException("数据列名须以字母/中文/下划线开头，后续可用数字、点或短横线");
    }
    private static List<List<String>> csv(String text) {
        var table = new ArrayList<List<String>>();
        var row = new ArrayList<String>(); var field = new StringBuilder();
        boolean quoted = false, closed = false, touched = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') { if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; } else { quoted = false; closed = true; } }
                else field.append(c);
                continue;
            }
            if (c == ',' || c == '\n' || c == '\r') {
                row.add(field.toString()); field.setLength(0); closed = false; touched = true;
                if (c != ',') {
                    table.add(List.copyOf(row)); row.clear(); touched = false;
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                    if (table.size() > 1001) throw new IllegalArgumentException("CSV 最多 1000 行数据");
                }
            } else if (c == '"' && field.isEmpty() && !closed) { quoted = true; touched = true; }
            else {
                if (closed || c == '"') throw new IllegalArgumentException("CSV 引号或引号后的分隔符无效");
                field.append(c); touched = true;
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV 引号未闭合");
        if (touched || !row.isEmpty() || !field.isEmpty()) { row.add(field.toString()); table.add(List.copyOf(row)); }
        return table;
    }
}
