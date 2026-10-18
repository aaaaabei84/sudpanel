package com.sudpanel.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** OpenAPI 3.0/3.1 JSON 常用 HTTP 子集。只解析本地 JSON Pointer，绝不联网取 ref/example。 */
final class ApiOpenApi {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static { JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(128).build()); }
    private static final List<String> METHODS = List.of("get", "post", "put", "patch", "delete", "head", "options");
    private final JsonNode root;
    private final String name;
    private final Set<String> warnings = new LinkedHashSet<>();
    private final List<String> errors = new ArrayList<>();
    private final List<ApiInterchange.ImportedRequest> requests = new ArrayList<>();
    private ApiOpenApi(JsonNode root) { this.root = root; this.name = text(root.path("info"), "title", "OpenAPI 导入"); }

    static ApiInterchange.Preview preview(String content) {
        try {
            if (!content.stripLeading().startsWith("{")) throw bad("OpenAPI 仅支持 3.0/3.1 JSON；YAML 请先转换为 JSON");
            JsonNode root = JSON.readTree(content);
            String version = text(root, "openapi", "");
            if (!version.startsWith("3.0.") && !version.startsWith("3.1.")) throw bad("OpenAPI 版本仅支持 3.0.x / 3.1.x");
            var parser = new ApiOpenApi(root); parser.parse();
            return new ApiInterchange.Preview("OPENAPI", parser.name, List.copyOf(parser.requests), ApiVariables.VarSet.empty(), null,
                    List.copyOf(parser.warnings), List.copyOf(parser.errors));
        } catch (IllegalArgumentException e) { return failed(e.getMessage()); }
        catch (Exception e) { return failed("OpenAPI JSON 无法解析，或包含多份尾随文档"); }
    }

    private static ApiInterchange.Preview failed(String error) { return new ApiInterchange.Preview("OPENAPI", "OpenAPI 导入", List.of(), ApiVariables.VarSet.empty(), null, List.of(), List.of(error)); }

    private void parse() {
        JsonNode paths = root.path("paths");
        if (!paths.isObject()) throw bad("OpenAPI 需要 paths 对象");
        paths.fields().forEachRemaining(path -> {
            if (!path.getKey().startsWith("/")) { errors.add("路径必须以 / 开头"); return; }
            JsonNode pathItem;
            try { pathItem = deref(path.getValue()); if (!pathItem.isObject()) throw bad("Path Item 必须是对象"); }
            catch (IllegalArgumentException e) { errors.add(path.getKey() + "：" + e.getMessage()); return; }
            for (String method : METHODS) if (pathItem.has(method)) {
                if (requests.size() >= 1000) throw bad("最多导入 1000 个请求");
                try { requests.add(operation(path.getKey(), method, pathItem, deref(pathItem.path(method)))); }
                catch (IllegalArgumentException e) { errors.add(method.toUpperCase(Locale.ROOT) + " " + path.getKey() + "：" + e.getMessage()); }
            }
            if (pathItem.has("trace")) warnings.add("TRACE 请求未导入；平台当前只支持七种 HTTP 方法");
        });
        if (requests.isEmpty() && errors.isEmpty()) errors.add("OpenAPI 没有可导入的 HTTP 请求");
        warnings.add("只迁移请求模板；响应 Schema、响应示例、回调和链接未转换为断言/脚本");
    }

    private ApiInterchange.ImportedRequest operation(String path, String method, JsonNode pathItem, JsonNode op) {
        if (!op.isObject()) throw bad("Operation 必须是对象");
        String title = text(op, "summary", text(op, "operationId", method.toUpperCase(Locale.ROOT) + " " + path));
        var headers = new LinkedHashMap<String, String>();
        var params = new ArrayList<ApiRequestOptions.Param>();
        var fields = new ArrayList<ApiRequestOptions.Param>();
        var allParams = new LinkedHashMap<String, JsonNode>();
        parameters(pathItem.path("parameters"), allParams); parameters(op.path("parameters"), allParams);
        String urlPath = path;
        for (JsonNode p : allParams.values()) {
            String key = text(p, "name", ""), in = text(p, "in", "");
            if (key.isBlank()) throw bad("参数名称不能为空");
            if (p.has("content")) throw bad("参数 content 序列化尚未支持：" + key);
            if (p.path("allowReserved").asBoolean(false)) throw bad("allowReserved 参数尚未支持：" + key);
            boolean required = p.path("required").asBoolean(false) || in.equals("path");
            JsonNode schema = deref(p.path("schema"));
            JsonNode example = example(p, schema, false);
            String value = example == null ? "{{openapi.param." + safe(key) + "}}" : example.isValueNode() ? scalar(example, key) : "";
            if (in.equals("path")) {
                if (example != null && !example.isValueNode()) throw bad("path 目前只支持标量参数：" + key);
                String style = text(p, "style", "simple"); if (!style.equals("simple")) throw bad("path style 尚未支持：" + style);
                urlPath = urlPath.replace("{" + key + "}", example == null ? value : pathEncode(value));
                if (example == null) warnings.add("必填路径参数缺少示例，需配置变量：openapi.param." + safe(key));
            } else if (in.equals("query")) {
                String style = text(p, "style", "form"); if (!style.equals("form")) throw bad("query style 尚未支持：" + style);
                boolean enabled = required || example != null;
                if (example != null && example.isArray()) {
                    boolean explode = p.path("explode").asBoolean(true);
                    if (explode) for (JsonNode item : example) params.add(new ApiRequestOptions.Param(key, scalar(item, key), enabled));
                    else { var values = new ArrayList<String>(); example.forEach(i -> values.add(scalar(i, key))); params.add(new ApiRequestOptions.Param(key, String.join(",", values), enabled)); }
                } else params.add(new ApiRequestOptions.Param(key, example == null ? value : scalar(example, key), enabled));
                if (example == null) warnings.add((required ? "必填" : "可选（已禁用）") + " query 参数缺少示例：" + key);
            } else if (in.equals("header")) {
                if (Set.of("accept", "content-type", "authorization").contains(key.toLowerCase(Locale.ROOT))) { warnings.add("按 OpenAPI 规范忽略保留 header 参数：" + key); continue; }
                if (example == null && !required) { warnings.add("可选 header 无示例，未启用：" + key); continue; }
                if (!text(p, "style", "simple").equals("simple") || example != null && !example.isValueNode()) throw bad("header 仅支持 simple 标量参数：" + key);
                header(headers, key, value);
                if (example == null) warnings.add("必填 header 缺少示例，需配置变量：openapi.param." + safe(key));
            } else throw bad("参数位置尚未支持：" + in + "（" + key + "）");
        }
        var unresolved = Pattern.compile("(?<!\\{)\\{([^{}]+)}(?!})").matcher(urlPath);
        var completedPath = new StringBuilder();
        while (unresolved.find()) { String key = unresolved.group(1); warnings.add("路径参数未定义，需配置变量：openapi.param." + safe(key)); unresolved.appendReplacement(completedPath, java.util.regex.Matcher.quoteReplacement("{{openapi.param." + safe(key) + "}}")); }
        unresolved.appendTail(completedPath);
        String server = server(op.has("servers") ? op.path("servers") : pathItem.has("servers") ? pathItem.path("servers") : root.path("servers"));
        String url = server.replaceAll("/+$", "") + completedPath;
        String body = null, bodyType = "NONE";
        if (op.has("requestBody")) {
            JsonNode requestBody = deref(op.path("requestBody")), content = requestBody.path("content");
            if (!content.isObject() || content.isEmpty()) throw bad("requestBody 缺少 content");
            String mediaType = content.has("application/json") ? "application/json" : content.has("application/x-www-form-urlencoded") ? "application/x-www-form-urlencoded" : content.fieldNames().next();
            if (content.size() > 1) warnings.add("多个 requestBody 媒体类型仅导入首选类型：" + mediaType);
            JsonNode media = deref(content.path(mediaType)), schema = media.path("schema");
            JsonNode value = example(media, schema, true);
            if (value == null) { value = sample(schema, new HashSet<>(), 0); warnings.add("请求体由 Schema 生成结构示例，未验证全部约束，发送前请核对：" + title); }
            if (mediaType.equals("application/json") || mediaType.endsWith("+json")) {
                bodyType = "JSON"; body = value.toString(); header(headers, "Content-Type", mediaType);
            } else if (mediaType.equals("application/x-www-form-urlencoded")) {
                if (media.has("encoding") && !media.path("encoding").isEmpty()) throw bad("form encoding 自定义规则尚未支持");
                if (!value.isObject()) throw bad("URLENCODED 示例/Schema 必须为对象");
                bodyType = "URLENCODED";
                value.fields().forEachRemaining(e -> {
                    if (e.getValue().isArray()) e.getValue().forEach(i -> fields.add(new ApiRequestOptions.Param(e.getKey(), scalar(i, e.getKey()), true)));
                    else fields.add(new ApiRequestOptions.Param(e.getKey(), scalar(e.getValue(), e.getKey()), true));
                });
                header(headers, "Content-Type", mediaType);
            } else if (mediaType.startsWith("text/")) { bodyType = "RAW"; body = scalar(value, "正文"); header(headers, "Content-Type", mediaType); }
            else throw bad("正文媒体类型尚未支持：" + mediaType + "；不会转换为空正文");
        }
        String folder = name;
        if (op.path("tags").isArray() && !op.path("tags").isEmpty()) {
            folder += " / " + op.path("tags").get(0).asText();
            if (op.path("tags").size() > 1) warnings.add("多个 tag 仅使用第一个作为分组：" + title);
        }
        var auth = auth(op.has("security") ? op.path("security") : root.path("security"));
        var options = new ApiRequestOptions(bodyType, params, auth, List.of(), fields,
                new ApiRequestOptions.Transport("NORMAL", "INHERIT", null, null, "OFF", null), true);
        var spec = new ApiSpec(null, title, method.toUpperCase(Locale.ROOT), url, ApiRequestEngine.stringify(headers), body,
                text(op, "description", "") + "\n从 OpenAPI 导入；响应规范未转换为断言。", "", "", 10000, false, true, null, null, options);
        return new ApiInterchange.ImportedRequest(folder, spec);
    }

    private void parameters(JsonNode rows, Map<String, JsonNode> target) {
        if (rows.isMissingNode()) return;
        if (!rows.isArray() || rows.size() > 200) throw bad("parameters 需为最多 200 条的数组");
        for (JsonNode row : rows) { JsonNode p = deref(row); if (!p.isObject()) throw bad("Parameter 必须是对象"); target.put(text(p, "in", "") + ":" + text(p, "name", ""), p); }
    }

    private String server(JsonNode servers) {
        if (servers.isMissingNode() || servers.isArray() && servers.isEmpty()) { warnings.add("未提供绝对 server，需配置 openapi.base_url 变量"); return "{{openapi.base_url}}"; }
        if (!servers.isArray()) throw bad("servers 必须是数组");
        if (servers.size() > 1) warnings.add("多个 server 仅导入第一个；可在导入后调整环境基址");
        JsonNode server = servers.get(0); String url = text(server, "url", "");
        if (url.isBlank()) throw bad("server URL 不能为空");
        var matcher = Pattern.compile("\\{([^{}]+)}").matcher(url); var out = new StringBuilder();
        while (matcher.find()) { String key = matcher.group(1); JsonNode variable = server.path("variables").path(key);
            if (!variable.has("default")) throw bad("server 变量缺少 default：" + key);
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(variable.path("default").asText())); }
        matcher.appendTail(out); url = out.toString();
        if (url.startsWith("/")) { warnings.add("相对 server 需配置 openapi.base_url 变量"); return "{{openapi.base_url}}" + url; }
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw bad("只支持绝对 HTTP(S) server，或 / 开头的相对路径");
        return url;
    }

    private ApiRequestOptions.Auth auth(JsonNode security) {
        if (security.isMissingNode() || security.isArray() && security.isEmpty()) return noAuth();
        if (!security.isArray()) throw bad("security 必须是数组");
        JsonNode requirement = security.get(0);
        if (!requirement.isObject()) throw bad("security 条目必须是对象");
        if (security.size() > 1) warnings.add("多个 OR 鉴权方案仅导入第一个；请在发送前确认");
        if (requirement.isEmpty()) return noAuth();
        if (requirement.size() > 1) throw bad("同一请求要求多个 AND 鉴权，当前不能等价导入");
        String key = requirement.fieldNames().next();
        JsonNode scheme = deref(root.path("components").path("securitySchemes").path(key));
        String type = text(scheme, "type", ""), prefix = "{{openapi.auth." + safe(key);
        warnings.add("鉴权凭据需手动配置环境变量：openapi.auth." + safe(key));
        if (type.equals("http")) {
            String http = text(scheme, "scheme", "").toLowerCase(Locale.ROOT);
            if (http.equals("bearer")) return new ApiRequestOptions.Auth("BEARER", prefix + ".token}}", null, null, null, null, null);
            if (http.equals("basic")) return new ApiRequestOptions.Auth("BASIC", null, prefix + ".username}}", prefix + ".password}}", null, null, null);
            throw bad("HTTP 鉴权 scheme 尚未支持：" + http);
        }
        if (type.equals("apiKey")) {
            String in = text(scheme, "in", ""); if (!Set.of("query", "header").contains(in)) throw bad("API Key 只支持 query/header");
            String name = text(scheme, "name", ""); if (name.isBlank()) throw bad("API Key 名称不能为空");
            return new ApiRequestOptions.Auth("API_KEY", null, null, null, name, prefix + ".value}}", in.toUpperCase(Locale.ROOT));
        }
        throw bad("鉴权类型尚未支持：" + type);
    }

    private JsonNode example(JsonNode container, JsonNode schema, boolean allowSchema) {
        if (container.has("example")) return container.get("example");
        JsonNode examples = container.path("examples");
        if (examples.isObject() && !examples.isEmpty()) {
            JsonNode first = deref(examples.elements().next());
            if (first.has("externalValue")) throw bad("externalValue 示例不会联网读取，请改为内联 value");
            if (first.has("value")) return first.get("value");
        }
        JsonNode resolved = deref(schema);
        if (resolved.has("example")) return resolved.get("example");
        if (resolved.path("examples").isArray() && !resolved.path("examples").isEmpty()) return resolved.path("examples").get(0);
        if (resolved.has("default")) return resolved.get("default");
        if (resolved.path("enum").isArray() && !resolved.path("enum").isEmpty()) return resolved.path("enum").get(0);
        return null;
    }

    private JsonNode sample(JsonNode schema, Set<String> seen, int depth) {
        if (depth > 32) throw bad("Schema 示例递归超过 32 层，请提供内联 example");
        if (schema.has("$ref")) {
            refSiblings(schema);
            String ref = text(schema, "$ref", ""); if (!seen.add(ref)) throw bad("循环 Schema 引用，请提供有限的内联 example");
            try { return sample(local(ref), seen, depth + 1); } finally { seen.remove(ref); }
        }
        JsonNode found = example(schema, schema, true); if (found != null) return found;
        if (schema.has("oneOf") || schema.has("anyOf") || schema.has("allOf")) throw bad("组合 Schema 请提供内联 example 后导入");
        String type = text(schema, "type", "");
        if (schema.path("type").isArray()) { type = ""; for (JsonNode t : schema.path("type")) if (!t.asText().equals("null")) { type = t.asText(); break; } }
        if (type.isBlank() && schema.has("properties")) type = "object";
        return switch (type) {
            case "object" -> {
                var object = JSON.createObjectNode();
                JsonNode properties = schema.path("properties"); if (!properties.isMissingNode() && !properties.isObject()) throw bad("Schema properties 必须为对象");
                if (properties.size() > 200) throw bad("Schema 示例对象最多 200 个字段");
                properties.fields().forEachRemaining(e -> { if (!deref(e.getValue()).path("readOnly").asBoolean(false)) object.set(e.getKey(), sample(e.getValue(), seen, depth + 1)); });
                yield object;
            }
            case "array" -> {
                int count = Math.max(1, schema.path("minItems").asInt(1)); if (count > 10) throw bad("Schema minItems 超过示例生成上限10，请提供 example");
                var array = JSON.createArrayNode(); for (int i = 0; i < count; i++) array.add(sample(schema.path("items"), seen, depth + 1)); yield array;
            }
            case "integer" -> JSON.getNodeFactory().numberNode(0);
            case "number" -> JSON.getNodeFactory().numberNode(0);
            case "boolean" -> JSON.getNodeFactory().booleanNode(false);
            case "null" -> JSON.nullNode();
            case "string" -> {
                if (Set.of("binary", "byte").contains(text(schema, "format", ""))) throw bad("二进制 Schema 不能生成文本示例");
                yield JSON.getNodeFactory().textNode("example");
            }
            default -> throw bad("无法从 Schema 生成正文，请提供内联 example");
        };
    }

    private JsonNode deref(JsonNode node) {
        var seen = new HashSet<String>();
        for (int depth = 0; node.has("$ref"); depth++) {
            String ref = text(node, "$ref", ""); if (depth >= 32 || !seen.add(ref)) throw bad("循环或过深的本地 $ref");
            refSiblings(node);
            node = local(ref);
        }
        return node;
    }
    private static void refSiblings(JsonNode node) {
        var names = new ArrayList<String>(); node.fieldNames().forEachRemaining(names::add);
        if (names.stream().anyMatch(k -> !Set.of("$ref", "summary", "description").contains(k))) throw bad("$ref 的结构性兄弟字段尚未支持，请展开引用后导入");
    }
    private JsonNode local(String ref) {
        if (!ref.startsWith("#/")) throw bad("只支持 #/ 开头的本地 $ref；外部引用不会读取");
        JsonNode node; try { node = root.at(java.net.URI.create(ref).getFragment()); } catch (Exception e) { throw bad("本地 $ref JSON Pointer 无效"); }
        if (node.isMissingNode()) throw bad("本地 $ref 目标不存在"); return node;
    }
    private static void header(Map<String, String> headers, String name, String value) {
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw bad("header 名称/值无效");
        if (headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name))) throw bad("重复 header 无法等价导入：" + name);
        headers.put(name, value);
    }
    private static String scalar(JsonNode value, String key) { if (value == null || !value.isValueNode() || value.isNull()) throw bad("参数/表单仅支持非 null 标量：" + key); return value.asText(); }
    private static String text(JsonNode node, String key, String fallback) { JsonNode value = node == null ? null : node.get(key); return value == null || value.isNull() ? fallback : value.asText(); }
    private static String safe(String value) { return value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
    private static String pathEncode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static ApiRequestOptions.Auth noAuth() { return new ApiRequestOptions.Auth("NONE", null, null, null, null, null, null); }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
