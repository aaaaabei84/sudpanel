package com.sudpanel.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 对保存的应用层响应作比较；数字不经double，缺失与JSON null分开。 */
public final class ApiHistoryCompare {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static { JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(128).build()); }
    private ApiHistoryCompare() {}
    public record Difference(String section, String path, String kind, String left, String right) {}
    public record Compared(Long leftId, Long rightId, boolean complete, List<String> warnings, List<Difference> differences) {}
    public static Compared compare(ApiExec left, ApiExec right, List<String> ignorePaths) {
        var ignore = ignorePaths == null ? List.<String>of() : ignorePaths;
        if (ignore.size() > 100) throw new IllegalArgumentException("忽略路径最多100条");
        for (String path : ignore) if (path == null || !path.isEmpty() && !path.startsWith("/") || path != null && path.matches(".*~(?![01]).*")) throw new IllegalArgumentException("忽略路径需为有效 JSON Pointer，根路径为空字符串");
        var differences = new ArrayList<Difference>(); var warnings = new ArrayList<String>();
        boolean complete = complete(left, "左侧", warnings) & complete(right, "右侧", warnings);
        compareValue("STATUS", "/httpStatus", left.httpStatus(), right.httpStatus(), differences);
        compareValue("STATUS", "/businessCode", left.respCode(), right.respCode(), differences);
        compareValue("STATUS", "/verdict", left.verdict(), right.verdict(), differences);
        var leftHeaders = headers(left.respHeaders()); var rightHeaders = headers(right.respHeaders());
        var names = new TreeSet<String>(); names.addAll(leftHeaders.keySet()); names.addAll(rightHeaders.keySet());
        for (String name : names) compareValue("HEADER", "/" + escape(name), leftHeaders.get(name), rightHeaders.get(name), differences);
        JsonNode l = parse(left.respBody()), r = parse(right.respBody());
        if (l != null && r != null) diff(l, r, "", ignore, differences, 0);
        else if (!ignored("", ignore)) {
            if (l != null || r != null) differences.add(new Difference("BODY", "", "TYPE", json(l == null ? left.respBody() : l), json(r == null ? right.respBody() : r)));
            else compareValue("BODY", "", left.respBody(), right.respBody(), differences);
        }
        if (differences.size() > 500) { warnings.add("差异最多显示500条，剩余差异已省略"); complete = false; }
        warnings.add("比较的是已保存的应用层快照，不包含底层自动头及所有跳转报文；数字按精确数值比较，1与1.0视为等价");
        return new Compared(left.id(), right.id(), complete, List.copyOf(warnings), List.copyOf(differences.subList(0, Math.min(500, differences.size()))));
    }

    private static void diff(JsonNode l, JsonNode r, String path, List<String> ignore, List<Difference> out, int depth) {
        if (out.size() >= 501 || ignored(path, ignore)) return;
        if (depth > 128) throw new IllegalArgumentException("响应JSON过深，无法比较");
        if (l == null || r == null) { if (l != r) out.add(new Difference("BODY", path, l == null ? "ADDED" : "REMOVED", l == null ? null : l.toString(), r == null ? null : r.toString())); return; }
        if (l.getNodeType() != r.getNodeType()) { out.add(new Difference("BODY", path, "TYPE", l.toString(), r.toString())); return; }
        if (l.isObject()) {
            var names = new TreeSet<String>(); l.fieldNames().forEachRemaining(names::add); r.fieldNames().forEachRemaining(names::add);
            for (String name : names) diff(l.get(name), r.get(name), path + "/" + escape(name), ignore, out, depth + 1);
        } else if (l.isArray()) {
            for (int i = 0; i < Math.max(l.size(), r.size()); i++) diff(l.get(i), r.get(i), path + "/" + i, ignore, out, depth + 1);
        } else if (l.isNumber() ? l.decimalValue().compareTo(r.decimalValue()) != 0 : !l.equals(r)) out.add(new Difference("BODY", path, "CHANGED", l.toString(), r.toString()));
    }

    private static boolean complete(ApiExec e, String label, List<String> warnings) {
        JsonNode metadata = parse(e.metadata()); boolean complete = true;
        if (e.httpStatus() == null) { warnings.add(label + "没有收到HTTP响应，无法作完整响应比较"); complete = false; }
        if (metadata == null || !metadata.path("responseBodyTruncated").isBoolean()) { warnings.add(label + "旧记录未保存正文完整性标记，完整性未知"); complete = false; }
        else if (metadata.path("responseBodyTruncated").asBoolean(false) || truncated(e.respBody())) { warnings.add(label + "响应正文已截断，不能据此证明完整一致"); complete = false; }
        return complete;
    }
    public static boolean truncated(String body) { return body != null && body.matches("(?s).*\\n…（已截断，原文 \\d+ 字符）$"); }
    static JsonNode parse(String text) { if (text == null || text.isBlank()) return null; try { return JSON.readTree(text); } catch (Exception e) { return null; } }
    static Map<String, String> headers(String text) {
        var headers = new LinkedHashMap<String, String>();
        for (String line : (text == null ? "" : text).split("\\r?\\n")) {
            if (line.isBlank() || line.startsWith("（")) break;
            int colon = line.indexOf(':'); if (colon <= 0) continue;
            String key = line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
            if (!key.matches("[!#$%&'*+.^_`|~0-9a-z-]+")) continue;
            headers.merge(key, line.substring(colon + 1).stripLeading(), (a, b) -> a + "\n" + b);
        }
        return headers;
    }
    private static boolean ignored(String path, List<String> ignored) { return ignored.stream().anyMatch(i -> i.equals(path) || i.isEmpty() || path.startsWith(i + "/")); }
    private static void compareValue(String section, String path, Object l, Object r, List<Difference> out) { if (!java.util.Objects.equals(l, r)) out.add(new Difference(section, path, l == null ? "ADDED" : r == null ? "REMOVED" : "CHANGED", json(l), json(r))); }
    private static String json(Object value) { return value == null ? null : value instanceof JsonNode node ? node.toString() : ApiRequestEngine.stringify(value); }
    private static String escape(String text) { return text.replace("~", "~0").replace("/", "~1"); }
}
