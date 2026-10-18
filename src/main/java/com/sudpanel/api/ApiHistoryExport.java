package com.sudpanel.api;

import java.io.StringWriter;
import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamWriter;

import com.fasterxml.jackson.databind.JsonNode;

/** 报告默认省略正文并遮罩已知凭据；自定义命名仍需人工检查，不声称识别所有秘密。 */
public final class ApiHistoryExport {
    private ApiHistoryExport() {}
    private static final Pattern SECRET = Pattern.compile("(?i)(?:authorization|proxy-authorization|cookie|set-cookie|.*(?:token|secret|password|passwd|api[_-]?key|access[_-]?key|sign).*)");
    private static final Set<String> META = Set.of("schema", "envId", "envName", "capturedAt", "authType", "bodyType", "scenarioId", "scenarioName", "stepKey", "stepName", "phase", "independent", "onFailure", "dataRowIndex", "dataIteration", "requestBodyChars", "requestBodyTruncated", "responseBodyChars", "responseBodyTruncated", "wireBytes", "decodedBytes", "charset", "contentEncoding", "responseLimitBytes");

    public static Map<String, Object> json(ApiBatch batch, ApiHistoryRepository.Stats stats, List<ApiExec> execs, boolean includeSecrets) { return json(batch,stats,execs,includeSecrets,List.of()); }
    public static Map<String, Object> json(ApiBatch batch, ApiHistoryRepository.Stats stats, List<ApiExec> execs, boolean includeSecrets, List<String> sensitivePaths) {
        var report = new LinkedHashMap<String, Object>();
        report.put("schema", 1); report.put("exportedAt", Instant.now().toString()); report.put("includeSecrets", includeSecrets);
        report.put("warnings", warnings(execs, includeSecrets)); report.put("batch", batch);
        Set<String> secrets = secrets(execs,includeSecrets,sensitivePaths);
        if(!includeSecrets) report.put("batch",sanitize(batch,ApiBatch.class,secrets));
        report.put("stats", includeSecrets ? stats : sanitize(stats,ApiHistoryRepository.Stats.class,secrets));
        report.put("execs", includeSecrets ? execs : execs.stream().map(e->sanitize(redact(e,secrets),ApiExec.class,secrets)).toList());
        return report;
    }

    public static String junit(ApiBatch batch, List<ApiExec> execs, boolean includeSecrets) { return junit(batch,execs,includeSecrets,List.of()); }
    public static String junit(ApiBatch batch, List<ApiExec> execs, boolean includeSecrets, List<String> sensitivePaths) {
        Set<String> secrets=secrets(execs,includeSecrets,sensitivePaths);
        try {
            StringWriter output = new StringWriter(); XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            xml.writeStartDocument("UTF-8", "1.0"); xml.writeStartElement("testsuites"); xml.writeStartElement("testsuite");
            attr(xml, "name", includeSecrets?batch.name():replace(batch.name(),secrets)); attr(xml, "tests", execs.size());
            attr(xml, "failures", execs.stream().filter(e -> "FAIL".equals(e.verdict())).count());
            attr(xml, "errors", execs.stream().filter(e -> "ERROR".equals(e.verdict())).count());
            attr(xml, "skipped", execs.stream().filter(e -> "SKIP".equals(e.verdict())).count());
            long millis = execs.stream().filter(e -> e.elapsedMs() != null && e.elapsedMs() > 0).mapToLong(ApiExec::elapsedMs).sum(); attr(xml, "time", seconds(millis));
            xml.writeStartElement("properties"); xml.writeEmptyElement("property"); attr(xml, "name", "includeSecrets"); attr(xml, "value", includeSecrets); xml.writeEndElement();
            for (ApiExec original : execs) {
                ApiExec e = includeSecrets ? original : sanitize(redact(original,secrets),ApiExec.class,secrets);
                xml.writeStartElement("testcase"); attr(xml, "classname", "api.case." + (e.caseId() == null ? "adhoc" : e.caseId()));
                attr(xml, "name", "轮次" + e.roundNo() + " 序号" + e.seq() + " " + e.caseName()); attr(xml, "time", seconds(e.elapsedMs() == null || e.elapsedMs() < 0 ? 0 : e.elapsedMs()));
                String result = "FAIL".equals(e.verdict()) ? "failure" : "ERROR".equals(e.verdict()) ? "error" : "SKIP".equals(e.verdict()) ? "skipped" : null;
                if (result != null) {
                    xml.writeStartElement(result); attr(xml, "message", e.reason() == null ? e.verdict() : e.reason());
                    xml.writeCharacters(xmlSafe("判定=" + e.verdict() + "\n" + nullText(e.error()) + "\n断言=" + nullText(e.assertsResult())
                            + (includeSecrets ? "\n请求=" + nullText(e.reqBody()) + "\n响应=" + nullText(e.respBody()) : "\n报文正文已省略")));
                    xml.writeEndElement();
                }
                xml.writeEndElement();
            }
            xml.writeStartElement("system-out"); xml.writeCharacters(xmlSafe(String.join("\n", warnings(execs, includeSecrets)))); xml.writeEndElement();
            xml.writeEndElement(); xml.writeEndElement(); xml.writeEndDocument(); xml.close(); return output.toString();
        } catch (Exception e) { throw new IllegalStateException("JUnit 报告生成失败", e); }
    }

    private static List<String> warnings(List<ApiExec> execs, boolean includeSecrets) {
        var warnings = new ArrayList<String>();
        warnings.add(includeSecrets ? "本机全文导出包含已保存的请求/响应及可能的凭据，请勿直接外发" : "默认省略请求/响应正文及断言实际/期望详情；已知敏感头和query已遮罩，自定义字段、自由文本及业务ID仍需分享前检查");
        if (execs.stream().anyMatch(e -> { JsonNode m = ApiHistoryCompare.parse(e.metadata()); return m == null || !m.path("requestBodyTruncated").isBoolean() || !m.path("responseBodyTruncated").isBoolean(); })) warnings.add("部分旧记录未保存正文完整性标记；不能把报告视为完整原始报文");
        if (execs.stream().anyMatch(e -> { JsonNode m = ApiHistoryCompare.parse(e.metadata()); return ApiHistoryCompare.truncated(e.reqBody()) || ApiHistoryCompare.truncated(e.respBody()) || m != null && (m.path("requestBodyTruncated").asBoolean(false) || m.path("responseBodyTruncated").asBoolean(false)); })) warnings.add("部分正文已在执行时截断；即使包含敏感值也无法恢复未保存全文");
        return warnings;
    }

    static ApiExec redact(ApiExec e) {
        Set<String> secrets = new LinkedHashSet<>();
        known(e, secrets);
        return redact(e,secrets);
    }
    private static ApiExec redact(ApiExec e, Set<String> secrets) {
        String url = redactUrl(e.url(), secrets);
        var metadata = new LinkedHashMap<String, Object>(); JsonNode node = ApiHistoryCompare.parse(e.metadata());
        if (node != null && node.isObject()) node.fields().forEachRemaining(f -> { if (META.contains(f.getKey()) && f.getValue().isValueNode()) metadata.put(f.getKey(), f.getValue()); });
        var assertions = new ArrayList<Map<String, Object>>(); JsonNode rules = ApiHistoryCompare.parse(e.assertsResult());
        if (rules != null && rules.isArray()) for (JsonNode rule : rules) {
            var safe = new LinkedHashMap<String, Object>();
            for (String key : List.of("path", "op", "ok", "source", "actualTruncated")) if (rule.has(key)) safe.put(key, rule.get(key));
            safe.put("why", "详情已省略"); assertions.add(safe);
        }
        return new ApiExec(e.id(), e.batchId(), e.caseId(), e.caseName(), e.roundNo(), e.seq(), e.method(), url, e.httpStatus(), e.respCode(), e.codeMeaning(), e.verdict(),
                replace(e.reason(), secrets), e.elapsedMs(), e.respBody() == null ? null : "<REDACTED_RESPONSE_BODY>",
                redactHeaders(e.respHeaders(), secrets, null), redactHeaders(e.reqHeaders(), secrets, nullText(e.method()) + " " + nullText(url)),
                e.reqBody() == null ? null : "<REDACTED_REQUEST_BODY>", replace(e.error(), secrets), e.ts(), rules == null ? null : ApiRequestEngine.stringify(assertions), ApiRequestEngine.stringify(metadata));
    }

    private static Set<String> secrets(List<ApiExec> execs,boolean includeSecrets,List<String> paths) {
        Set<String> secrets=new LinkedHashSet<>();if(includeSecrets)return secrets;
        for(ApiExec e:execs){known(e,secrets);for(String text:List.of(nullText(e.reqBody()),nullText(e.respBody()))){JsonNode n=ApiHistoryCompare.parse(text);if(n!=null)for(String path:paths){JsonNode v=path.isEmpty()?n:n.at(path);if(!v.isMissingNode())collectJson(v,secrets,true);}}}
        return secrets;
    }
    private static <T>T sanitize(T value,Class<T> type,Set<String> secrets){try{var json=new com.fasterxml.jackson.databind.ObjectMapper();JsonNode n=json.valueToTree(value);replaceTree(n,secrets);return json.treeToValue(n,type);}catch(Exception e){throw new IllegalStateException("报告遮罩失败",e);}}
    private static void replaceTree(JsonNode n,Set<String> secrets){
        if(n.isObject()){var o=(com.fasterxml.jackson.databind.node.ObjectNode)n;var keys=new ArrayList<String>();o.fieldNames().forEachRemaining(keys::add);for(String k:keys){JsonNode v=o.get(k);if(v.isTextual())o.put(k,replace(v.asText(),secrets));else replaceTree(v,secrets);}}
        else if(n.isArray()){var a=(com.fasterxml.jackson.databind.node.ArrayNode)n;for(int i=0;i<a.size();i++){JsonNode v=a.get(i);if(v.isTextual())a.set(i,com.fasterxml.jackson.databind.node.TextNode.valueOf(replace(v.asText(),secrets)));else replaceTree(v,secrets);}}
    }
    private static void known(ApiExec e, Set<String> secrets) {
        JsonNode inventory=ApiHistoryCompare.parse(e.metadata());
        if(inventory!=null && inventory.path("privateKnownSecrets").isArray())for(var value:inventory.path("privateKnownSecrets"))secrets.add(value.asText());
        collectHeaders(e.reqHeaders(), secrets); collectHeaders(e.respHeaders(), secrets);
        collectJson(ApiHistoryCompare.parse(e.reqBody()), secrets, false); collectJson(ApiHistoryCompare.parse(e.respBody()), secrets, false);
        redactUrl(e.url(), secrets);
        JsonNode rules = ApiHistoryCompare.parse(e.assertsResult());
        if (rules != null && rules.isArray()) for (JsonNode rule : rules) if (sensitive(rule.path("path").asText(""))) {
            for (String key : List.of("actual", "expect")) if (rule.has(key)) {
                String value = rule.path(key).asText(); JsonNode parsed = ApiHistoryCompare.parse(value);
                if (parsed != null) collectJson(parsed, secrets, true); else if (!value.isBlank()) secrets.add(value);
            }
        }
    }
    private static void collectHeaders(String text, Set<String> secrets) {
        ApiHistoryCompare.headers(text).forEach((key, value) -> { if (sensitive(key) && !value.isEmpty()) { secrets.add(value); int space = value.indexOf(' '); if (space > 0 && space + 1 < value.length()) secrets.add(value.substring(space + 1)); } });
    }
    private static void collectJson(JsonNode node, Set<String> secrets, boolean secret) {
        if (node == null) return;
        if (node.isValueNode()) { if (secret && !node.isNull() && !node.asText().isEmpty()) secrets.add(node.asText()); }
        else if (node.isArray()) node.forEach(i -> collectJson(i, secrets, secret));
        else if (node.isObject()) node.fields().forEachRemaining(f -> collectJson(f.getValue(), secrets, secret || sensitive(f.getKey())));
    }
    private static String redactUrl(String url, Set<String> secrets) {
        if (url == null) return null;
        url = url.replaceFirst("(?i)^(https?://)[^/?#@]+@", "$1***@");
        int q = url.indexOf('?'), hash = url.indexOf('#'); if (q < 0 || hash >= 0 && q > hash) return url;
        String[] fields = url.substring(q + 1, hash < 0 ? url.length() : hash).split("&", -1);
        for (int i = 0; i < fields.length; i++) {
            int eq = fields[i].indexOf('='); String name = eq < 0 ? fields[i] : fields[i].substring(0, eq);
            if (sensitive(decode(name))) { if (eq >= 0 && eq + 1 < fields[i].length()) { secrets.add(fields[i].substring(eq + 1)); secrets.add(decode(fields[i].substring(eq + 1))); } fields[i] = name + "=***"; }
        }
        return url.substring(0, q + 1) + String.join("&", fields) + (hash < 0 ? "" : url.substring(hash));
    }
    private static String redactHeaders(String text, Set<String> secrets, String requestLine) {
        if (text == null) return null;
        StringBuilder safe = new StringBuilder(); if (requestLine != null) safe.append(requestLine).append('\n');
        ApiHistoryCompare.headers(text).forEach((key, value) -> safe.append(key).append(": ").append(sensitive(key) ? "***" : replace(value, secrets)).append('\n'));
        return safe.toString(); // 原reqHeaders还拼有正文，绝不能直接正则替换后继续返回整串。
    }
    private static String replace(String text, Set<String> secrets) { return ApiPrivacy.replace(text,secrets); }
    private static boolean sensitive(String name) { return SECRET.matcher(name).matches(); }
    private static String decode(String text) { try { return URLDecoder.decode(text, StandardCharsets.UTF_8); } catch (Exception e) { return text; } }
    private static String seconds(long millis) { return BigDecimal.valueOf(millis, 3).toPlainString(); }
    private static String nullText(String text) { return text == null ? "" : text; }
    private static void attr(XMLStreamWriter xml, String name, Object value) throws Exception { xml.writeAttribute(name, xmlSafe(String.valueOf(value))); }
    private static String xmlSafe(String text) { var out = new StringBuilder(); text.codePoints().filter(c -> c == 9 || c == 10 || c == 13 || c >= 0x20 && c <= 0xD7FF || c >= 0xE000 && c <= 0xFFFD || c >= 0x10000 && c <= 0x10FFFF).forEach(out::appendCodePoint); return out.toString(); }
}
