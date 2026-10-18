package com.sudpanel.api;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 纯组装流程。预览和实际发送共用；每次调用独立产生动态值。 */
public final class ApiRequestEngine {
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");
    public static final List<String> METHODS = List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    public static final List<String> AUTH_TYPES = List.of("INHERIT", "NONE", "SUD", "BEARER", "BASIC", "API_KEY", "OAUTH2", "DIGEST", "SCRIPT", "TEMPLATE");

    private ApiRequestEngine() {}

    public record Environment(long envId, String envName, String capturedAt,
                              ApiVariables.VarSet vars, ApiSigner.Config sign, Boolean strictVariables, String inheritedProxy, String cookieScope) {
        public Environment(long envId, String envName, String capturedAt, ApiVariables.VarSet vars, ApiSigner.Config sign) {
            this(envId, envName, capturedAt, vars, sign, null, null, null);
        }
        public Environment(long envId, String envName, String capturedAt, ApiVariables.VarSet vars, ApiSigner.Config sign, Boolean strictVariables, String inheritedProxy) {
            this(envId, envName, capturedAt, vars, sign, strictVariables, inheritedProxy, null);
        }
        public Environment withSessionScope(String scope) {
            if (scope != null && !scope.matches("[A-Za-z0-9_.-]{1,80}")) throw new IllegalArgumentException("Cookie会话scope无效");
            return new Environment(envId, envName, capturedAt, vars, sign, strictVariables, inheritedProxy, scope);
        }
        public Environment {
            vars = new ApiVariables.VarSet(List.copyOf(vars.base()), List.copyOf(vars.header()), List.copyOf(vars.body()));
        }
    }
    public record Built(String method, String url, Map<String, String> headers, String body,
                        String authType, String bodyType, List<String> warnings,
                        List<String> unresolved,List<Map<String,String>> variables,
                        @com.fasterxml.jackson.annotation.JsonIgnore byte[] wireBody,Map<String,Object> bodyMetadata,
                        @com.fasterxml.jackson.annotation.JsonIgnore ApiFileBody.Encoded filePayload,List<ApiRequestOptions.HeaderRow> headerRows,
                        @com.fasterxml.jackson.annotation.JsonIgnore ApiSignatureScripts.Context signatureContext) implements AutoCloseable {
        public Built(String method,String url,Map<String,String> headers,String body,String authType,String bodyType,List<String>warnings,List<String>unresolved,List<Map<String,String>>variables){this(method,url,headers,body,authType,bodyType,warnings,unresolved,variables,null,Map.of(),null,ApiHeaders.fromMap(headers));}
        public Built(String method,String url,Map<String,String> headers,String body,String authType,String bodyType,List<String>warnings,List<String>unresolved,List<Map<String,String>>variables,byte[] wireBody,Map<String,Object> bodyMetadata,ApiFileBody.Encoded payload){this(method,url,headers,body,authType,bodyType,warnings,unresolved,variables,wireBody,bodyMetadata,payload,ApiHeaders.fromMap(headers));}
        public Built(String method,String url,Map<String,String> headers,String body,String authType,String bodyType,List<String>warnings,List<String>unresolved,List<Map<String,String>>variables,List<ApiRequestOptions.HeaderRow> rows){this(method,url,headers,body,authType,bodyType,warnings,unresolved,variables,null,Map.of(),null,rows);}
        public Built(String method,String url,Map<String,String> headers,String body,String authType,String bodyType,List<String>warnings,List<String>unresolved,List<Map<String,String>>variables,byte[] wireBody,Map<String,Object> bodyMetadata,ApiFileBody.Encoded payload,List<ApiRequestOptions.HeaderRow> rows){this(method,url,headers,body,authType,bodyType,warnings,unresolved,variables,wireBody,bodyMetadata,payload,rows,null);}
        public void close(){if(filePayload!=null)filePayload.close();}
    }
    public record Prepared(boolean valid,String method,String url,Map<String,String> headers,String body,
                           long envId,String envName,String capturedAt,List<String>warnings,List<String>unresolved,List<Map<String,String>>variables,String error,List<ApiVariableDiagnostics.Issue>diagnostics,Map<String,Object>bodyMetadata,List<ApiRequestOptions.HeaderRow>headerRows) {
        public Prepared(boolean valid,String method,String url,Map<String,String> headers,String body,long envId,String envName,String capturedAt,List<String>warnings,List<String>unresolved,List<Map<String,String>>variables,String error,List<ApiVariableDiagnostics.Issue>diagnostics){this(valid,method,url,headers,body,envId,envName,capturedAt,warnings,unresolved,variables,error,diagnostics,Map.of(),ApiHeaders.fromMap(headers));}
        public Prepared(boolean valid,String method,String url,Map<String,String> headers,String body,long envId,String envName,String capturedAt,List<String>warnings,List<String>unresolved,List<Map<String,String>>variables,String error,List<ApiVariableDiagnostics.Issue>diagnostics,Map<String,Object>bodyMetadata){this(valid,method,url,headers,body,envId,envName,capturedAt,warnings,unresolved,variables,error,diagnostics,bodyMetadata,ApiHeaders.fromMap(headers));}
        public Prepared(boolean valid,String method,String url,Map<String,String> headers,String body,long envId,String envName,String capturedAt,List<String>warnings,List<String>unresolved,List<Map<String,String>>variables,String error,List<ApiVariableDiagnostics.Issue>diagnostics,List<ApiRequestOptions.HeaderRow>headerRows){this(valid,method,url,headers,body,envId,envName,capturedAt,warnings,unresolved,variables,error,diagnostics,Map.of(),headerRows);}
        public Prepared(boolean valid,String method,String url,Map<String,String> headers,String body,long envId,String envName,String capturedAt,List<String>warnings,List<String>unresolved,List<Map<String,String>>variables,String error){this(valid,method,url,headers,body,envId,envName,capturedAt,warnings,unresolved,variables,error,List.of());}
    }

    public static void validateOptions(ApiRequestOptions options) {
        if (options == null) return;
        if (options.bodyType() != null && !Set.of("JSON", "RAW", "NONE", "URLENCODED", "MULTIPART", "BINARY").contains(options.bodyType()))
            throw new IllegalArgumentException("请求体类型仅支持 JSON / RAW / NONE / URLENCODED / MULTIPART / BINARY");
        ApiTransport.validate(options.transport());
        ApiFileBody.validate(options);
        ApiHeaders.validateRows(options.headerRows());
        if (options.fields() != null && (options.fields().size() > 200 || options.fields().stream().anyMatch(p -> p == null)))
            throw new IllegalArgumentException("表单字段最多 200 条，不能为 null");
        if (options.params() != null && options.params().size() > 200)
            throw new IllegalArgumentException("查询参数最多 200 条");
        if (options.params() != null && options.params().stream().anyMatch(p -> p == null))
            throw new IllegalArgumentException("查询参数不能为 null");
        if (options.auth() != null) {
            String type = options.auth().type();
            if (type != null && !AUTH_TYPES.contains(type)) throw new IllegalArgumentException("不支持的鉴权方式：" + type);
            if(Set.of("OAUTH2","DIGEST").contains(type==null?"":type))ApiHttpProfiles.id(options.auth().profileId());
            ApiSignatureScripts.validate(options.auth());
            String in = options.auth().in();
            if (in != null && !Set.of("HEADER", "QUERY").contains(in))
                throw new IllegalArgumentException("API Key 位置仅支持 HEADER / QUERY");
        }
        ApiResponseTools.validateExtractors(options.extractors());
    }

    public static Built build(ApiSpec spec, Environment env, Map<String, String> overrides,
                              Map<String, String> runVariables) {
        return build(spec, env, overrides, runVariables, Map.of());
    }
    public static Built build(ApiSpec spec, Environment env, Map<String, String> overrides,
                              Map<String, String> runVariables, Map<String, String> dataVariables) {
        return build(spec,env,overrides,runVariables,dataVariables,null);
    }
    public static Built build(ApiSpec spec,Environment env,Map<String,String> overrides,Map<String,String> runVariables,Map<String,String> dataVariables,ApiFileStore files) {
        validateOptions(spec.options());
        if (spec.options()!=null && spec.options().auth()!=null && "SCRIPT".equals(spec.options().auth().type()))
            return ApiSignatureScripts.build(spec,env,overrides,runVariables,dataVariables,files);
        if(spec.options()!=null && spec.options().auth()!=null && "TEMPLATE".equals(spec.options().auth().type())) {
            var effective=ApiSignatureScripts.templateEnvironment(env,overrides);
            var result=buildCore(spec,effective,null,runVariables,dataVariables,files,Instant.now(),new LinkedHashMap<>());
            return new Built(result.method(),result.url(),result.headers(),result.body(),result.authType(),result.bodyType(),result.warnings(),result.unresolved(),result.variables(),result.wireBody(),result.bodyMetadata(),result.filePayload(),result.headerRows(),new ApiSignatureScripts.Context(spec,effective,Set.of(),Map.of("mode","TEMPLATE")));
        }
        return buildCore(spec,env,overrides,runVariables,dataVariables,files,Instant.now(),new LinkedHashMap<>());
    }
    static Built buildCore(ApiSpec spec,Environment env,Map<String,String> overrides,Map<String,String> runVariables,Map<String,String> dataVariables,ApiFileStore files,Instant now,Map<String,String> dynamic) {
        ApiRequestOptions options = spec.options();
        String method = spec.method() == null ? "GET" : spec.method().trim().toUpperCase(java.util.Locale.ROOT);
        if (!METHODS.contains(method)) throw new IllegalArgumentException("不支持的 HTTP 方法：" + method);
        var vars = ApiVariables.overrideBody(env.vars(), overrides);
        var warnings = new ArrayList<String>();
        var sources = new ArrayList<Map<String, String>>();
        String signToken = "__sud_auth_sign_" + UUID.randomUUID() + "__";
        dynamic.put("$timestamp", Long.toString(now.getEpochSecond()));
        dynamic.put("$timestampMs", Long.toString(now.toEpochMilli()));
        var auth = options == null ? null : options.auth();
        String requestedAuth = auth == null || auth.type() == null ? "INHERIT" : auth.type();
        String authType = "INHERIT".equals(requestedAuth)
                ? (env.sign().enabled() ? "SUD" : "NONE") : requestedAuth;
        String nonce = env.sign().nonce() == null || env.sign().nonce().isBlank() ? ApiSigner.randomNonce(16) : env.sign().nonce().trim();
        if ("SUD".equals(authType)) {
            if (env.sign().appId() != null) dynamic.put("$auth.app_id", env.sign().appId());
            if (env.sign().mgId() != null) dynamic.put("$auth.mg_id", env.sign().mgId());
            dynamic.put("$auth.nonce", nonce);
            dynamic.put("$auth.sign", signToken);
        }
        String bodyType = options == null ? null : options.bodyType();
        boolean jsonBody = "JSON".equals(bodyType) || (bodyType == null && spec.body() != null
                && (spec.body().stripLeading().startsWith("{") || spec.body().stripLeading().startsWith("[")));
        var resolver = new Resolver(vars, runVariables == null ? Map.of() : runVariables, dataVariables, dynamic, sources, now, jsonBody);
        String url = resolver.resolve(spec.url(), "URL");
        String headersText = options!=null&&options.headerRows()!=null?null:resolver.resolve(spec.headers(), "HEADER_JSON");
        String body = Set.of("NONE","URLENCODED","MULTIPART","BINARY").contains(bodyType==null?"LEGACY":bodyType) ? null : resolver.resolve(spec.body(), "BODY");
        if ("URLENCODED".equals(bodyType)) {
            var encoded = new ArrayList<String>();
            for (var field : options.fields() == null ? List.<ApiRequestOptions.Param>of() : options.fields()) {
                if (Boolean.FALSE.equals(field.enabled()) || field.name() == null || field.name().isBlank()) continue;
                String name = resolver.restore(resolver.resolve(field.name(), "BODY"));
                String value = resolver.restore(resolver.resolve(field.value(), "BODY"));
                encoded.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8));
            }
            body = String.join("&", encoded);
        }
        // 旧模式与 JSON 的空白正文都表示没有请求体，必须在签名前归一化。
        // RAW 的空白是使用者填写的数据，保持原字节。
        if ((bodyType == null || "JSON".equals(bodyType)) && body != null && body.isBlank()) body = null;
        if (options != null && options.params() != null) {
            for (var param : options.params()) {
                if (Boolean.FALSE.equals(param.enabled()) || param.name() == null || param.name().isBlank()) continue;
                url = query(url, resolver.restore(resolver.resolve(param.name(), "URL")),
                        resolver.restore(resolver.resolve(param.value(), "URL")), false);
            }
        }
        var headers = options != null && options.headerRows() != null ? new LinkedHashMap<String,String>() : parseHeaders(headersText);
        var orderedHeaders=new ArrayList<ApiRequestOptions.HeaderRow>();
        if(options!=null&&options.headerRows()!=null){headers.clear();for(var row:options.headerRows())if(!Boolean.FALSE.equals(row.enabled())){
            String name=resolver.restore(resolver.resolve(row.name(),"HEADER")),value=resolver.resolve(row.value(),"HEADER");ApiHeaders.validate(name,value);
            orderedHeaders.add(new ApiRequestOptions.HeaderRow(name,value==null?"":value,true));headers.put(name,value==null?"":value);
        }}
        if (env.sign().enabled() && !Set.of("SCRIPT","TEMPLATE").contains(authType)) {
            for (String name : List.of("times", "nonce", "app_id", "mg_id", "sign")) {
                if (java.util.stream.Stream.of(vars.base(), vars.header(), vars.body())
                        .flatMap(List::stream).anyMatch(v -> name.equals(v.name())))
                    warnings.add("用户变量 " + name + " 与旧签名占位符同名；时间请使用 {{$timestamp}} 等受保护写法");
            }
        }
        ApiFileBody.Encoded payload=null;
        try {
        if ("MULTIPART".equals(bodyType)||"BINARY".equals(bodyType)) {
            payload=ApiFileBody.encode(options,files,text->{
                String resolved=resolver.resolve(text,"BODY");
                if("SUD".equals(authType))resolved=ApiSigner.sub(resolved,env.sign(),now.getEpochSecond(),nonce,null);
                return resolver.restore(resolved);
            });
            if("MULTIPART".equals(bodyType)) {
                if(containsHeader(headers,"Content-Type"))warnings.add("multipart Content-Type与boundary由最终字节自动生成，已替换手填Content-Type");
                headers.keySet().removeIf(name->name.equalsIgnoreCase("Content-Type"));headers.put("Content-Type",payload.contentType());
            } else if(!containsHeader(headers,"Content-Type"))headers.put("Content-Type",payload.contentType());
        }
        if ("SUD".equals(authType)) {
            ApiSigner.Config configured = env.sign();
            var sign = new ApiSigner.Config(true, configured.appId(), configured.appSecret(), configured.nonce(), configured.mgId());
            if (containsHeader(headers, "Authorization")) warnings.add("自家签名将覆盖手填 Authorization");
            var signingRequest=new ApiRequest(url == null ? null : url.replace(signToken,"{{sign}}"),stringify(headers).replace(signToken,"{{sign}}"),body);
            ApiRequest signed=payload==null?ApiSigner.apply(sign,signingRequest,now.getEpochSecond(),nonce,resolver::restore)
                    :ApiSigner.applyBytes(sign,signingRequest,now.getEpochSecond(),nonce,resolver::restore,payload.bytes());
            url = signed.url(); body = signed.body(); headers = parseHeaders(signed.headers());
        } else if ("OAUTH2".equals(authType)||"DIGEST".equals(authType)) {
            if(containsHeader(headers,"Authorization"))warnings.add("受管鉴权将覆盖手填Authorization");
            headers.keySet().removeIf(k->k.equalsIgnoreCase("Authorization"));warnings.add("预览只校验profile引用；发送时才获取令牌或进行一次Digest握手");
        } else if ("TEMPLATE".equals(authType) || "SCRIPT".equals(authType)) {
            String template=auth.authorizationTemplate();
            if(template!=null&&!template.isBlank()) {
                String value=resolver.restore(resolver.resolve(template,"HEADER"));
                if(!resolver.missing.isEmpty()) throw new IllegalArgumentException("Authorization模板有未解析变量："+String.join(", ",resolver.missing));
                replaceHeader(headers,"Authorization",value,warnings);
            }
        } else if ("BEARER".equals(authType)) {
            String token = resolver.resolve(auth.token(), "HEADER");
            if (token == null || token.isBlank()) throw new IllegalArgumentException("Bearer Token 不能为空");
            replaceHeader(headers, "Authorization", "Bearer " + token, warnings);
        } else if ("BASIC".equals(authType)) {
            String username = resolver.restore(resolver.resolve(auth.username(), "HEADER"));
            String password = resolver.restore(resolver.resolve(auth.password(), "HEADER"));
            if (username == null || username.isBlank()) throw new IllegalArgumentException("Basic 用户名不能为空");
            replaceHeader(headers, "Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":" + (password == null ? "" : password)).getBytes(StandardCharsets.UTF_8)), warnings);
        } else if ("API_KEY".equals(authType)) {
            String name = resolver.restore(resolver.resolve(auth.keyName(), "QUERY".equals(auth.in()) ? "URL" : "HEADER"));
            String value = resolver.restore(resolver.resolve(auth.keyValue(), "QUERY".equals(auth.in()) ? "URL" : "HEADER"));
            if (name == null || name.isBlank() || value == null || value.isBlank())
                throw new IllegalArgumentException("API Key 名称和值不能为空");
            if ("QUERY".equals(auth.in())) url = query(url, name, value, true);
            else replaceHeader(headers, name, value, warnings);
        }
        // 运行变量可能是嵌套 JSON；字面占位符保护也会临时遮住连续闭合大括号。
        // 先还原最终正文，再校验与发送同一份字节。
        url = resolver.restore(url); body = resolver.restore(body);
        headers.replaceAll((name, value) -> resolver.restore(value));
        if ("JSON".equals(bodyType) && body != null && !body.isBlank()) {
            try { if (JSON.readTree(body) == null) throw new IllegalArgumentException(); }
            catch (Exception e) {
                if (!resolver.missing.isEmpty()) throw new IllegalArgumentException("未解析变量导致 JSON 请求体无效：" + String.join(", ", resolver.missing));
                throw new IllegalArgumentException("JSON 请求体格式无效");
            }
        }
        if (("URLENCODED".equals(bodyType) || (body != null && !body.isEmpty())) && !containsHeader(headers, "Content-Type"))
            headers.put("Content-Type", "URLENCODED".equals(bodyType) ? "application/x-www-form-urlencoded"
                    : "RAW".equals(bodyType) ? "text/plain" : "application/json");
        if("URLENCODED".equals(bodyType)){String contentType=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("Content-Type")).map(Map.Entry::getValue).findFirst().orElse("");
            if(!contentType.split(";",2)[0].trim().equalsIgnoreCase("application/x-www-form-urlencoded"))warnings.add("URLENCODED正文已按表单编码，但手填Content-Type与表单类型冲突；请核对后发送");}
        var unresolved = new java.util.LinkedHashSet<String>(resolver.missing);
        if ("SUD".equals(authType)) unresolved.removeAll(List.of("times", "nonce", "app_id", "mg_id", "sign"));
        for (String name : unresolved) warnings.add("未解析变量：" + name);
        List<ApiRequestOptions.HeaderRow> finalRows;
        if(options==null||options.headerRows()==null)finalRows=ApiHeaders.fromMap(headers);
        else {
            var rows=new ArrayList<ApiRequestOptions.HeaderRow>();var present=new java.util.HashSet<String>();
            String managed=(Set.of("SUD","BEARER","BASIC","OAUTH2","DIGEST","TEMPLATE").contains(authType)||"SCRIPT".equals(authType)&&auth.authorizationTemplate()!=null&&!auth.authorizationTemplate().isBlank())?"Authorization":"API_KEY".equals(authType)&&!"QUERY".equals(auth.in())?resolver.restore(resolver.resolve(auth.keyName(),"HEADER")):null;
            String authorization=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase("Authorization")).map(Map.Entry::getValue).findFirst().orElse("");
            var signature=Pattern.compile("signature=\"([^\"]+)\"").matcher(authorization);String signedValue=signature.find()?signature.group(1):"";
            for(var row:orderedHeaders){if(managed!=null&&row.name().equalsIgnoreCase(managed)||"MULTIPART".equals(bodyType)&&row.name().equalsIgnoreCase("Content-Type"))continue;String value=resolver.restore(row.value());
                if("SUD".equals(authType))value=value.replace("{{times}}",Long.toString(now.getEpochSecond())).replace("{{nonce}}",nonce).replace("{{app_id}}",env.sign().appId()).replace("{{mg_id}}",env.sign().mgId()==null?"":env.sign().mgId()).replace("{{sign}}",signedValue).replace(signToken,signedValue);
                rows.add(new ApiRequestOptions.HeaderRow(row.name(),value,true));present.add(row.name().toLowerCase(Locale.ROOT));}
            for(var entry:headers.entrySet())if(!present.contains(entry.getKey().toLowerCase(Locale.ROOT)))rows.add(new ApiRequestOptions.HeaderRow(entry.getKey(),entry.getValue(),true));finalRows=List.copyOf(rows);
        }
        for(var row:finalRows)ApiHeaders.validate(row.name(),row.value());
        ApiHeaders.validateRows(finalRows);
        return new Built(method, url, headers, body, authType, bodyType == null ? "LEGACY" : bodyType,
                warnings, List.copyOf(unresolved), sources,payload==null?null:payload.bytes(),payload==null?Map.of():payload.metadata(),payload,finalRows);
        } catch(RuntimeException e){if(payload!=null)payload.close();throw e;}
    }

    private static final class Resolver {
        private final ApiVariables.VarSet vars;
        private final Map<String, String> run;
        private final Map<String, String> data;
        private final Map<String, String> dynamic;
        private final List<Map<String, String>> sources;
        private final Instant now;
        private final boolean jsonBody;
        private final java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        private final String openLiteral = "__sud_literal_open_" + UUID.randomUUID() + "__";
        private final String closeLiteral = "__sud_literal_close_" + UUID.randomUUID() + "__";
        Resolver(ApiVariables.VarSet vars, Map<String, String> run, Map<String, String> data, Map<String, String> dynamic,
                 List<Map<String, String>> sources, Instant now, boolean jsonBody) {
            this.vars = vars; this.run = run; this.dynamic = dynamic; this.sources = sources; this.now = now;
            this.data = data;
            this.jsonBody = jsonBody;
        }
        String resolve(String text, String scope) {
            if (text == null) return null;
            Map<String, String> local = new LinkedHashMap<>(ApiVariables.toMap(
                    ("BODY".equals(scope) || "ASSERT".equals(scope)) ? vars.body() : vars.header()));
            if ("URL".equals(scope)) local.putAll(ApiVariables.toMap(vars.base()));
            Matcher matcher = PLACEHOLDER.matcher(text);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                String name = matcher.group(1);
                String value;
                String source;
                if (name.startsWith("!")) {
                    value=openLiteral+name.substring(1)+closeLiteral;source="LITERAL";
                } else if (name.startsWith("$")) {
                    value = "$auth.sign".equals(name) && "BODY".equals(scope) ? null : system(name);
                    source = "SYSTEM";
                } else if (name.startsWith("run.") || name.startsWith("data.")) {
                    boolean fromData = name.startsWith("data.");
                    value = fromData ? data.get(name.substring(5)) : run.get(name.substring(4)); source = fromData ? "DATA" : "RUN";
                    if (value != null) {
                        try {
                            JsonNode node = JSON.readTree(value);
                            boolean jsonText = "HEADER_JSON".equals(scope) || ("BODY".equals(scope) && jsonBody);
                            boolean inString = jsonText && insideString(text, matcher.start());
                            String plain = node.isTextual() ? node.asText() : node.toString();
                            if (inString) {
                                String quoted = stringify(plain);
                                value = quoted.substring(1, quoted.length() - 1);
                            } else value = jsonText ? node.toString() : plain;
                        } catch (Exception e) { throw new IllegalArgumentException("运行变量 JSON 值损坏：" + name); }
                    }
                } else { value = local.get(name); source = "ENV"; }
                if (value == null) missing.add(name);
                if (value != null && ("RUN".equals(source) || "DATA".equals(source)))
                    value = value.replace("{{", openLiteral).replace("}}", closeLiteral);
                if (value != null) {
                    Map<String, String> trace = Map.of("name", name, "source", source, "scope", "HEADER_JSON".equals(scope) ? "HEADER" : scope);
                    if (!sources.contains(trace)) sources.add(trace);
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
            }
            matcher.appendTail(out);
            return out.toString();
        }
        String restore(String value) {
            return value == null ? null : value.replace(openLiteral, "{{").replace(closeLiteral, "}}");
        }
        private boolean insideString(String text, int end) {
            boolean inside = false, escaped = false;
            for (int i = 0; i < end; i++) {
                char c = text.charAt(i);
                if (escaped) { escaped = false; continue; }
                if (c == '\\') { escaped = true; continue; }
                if (c == '\"') inside = !inside;
            }
            return inside;
        }
        String system(String name) {
            if (dynamic.containsKey(name)) return dynamic.get(name);
            String value;
            if ("$uuid".equals(name)) value = UUID.randomUUID().toString();
            else if (name.startsWith("$randomInt:")) {
                String[] parts = name.split(":", -1);
                try {
                    if (parts.length != 3) throw new IllegalArgumentException();
                    int min = Integer.parseInt(parts[1]), max = Integer.parseInt(parts[2]);
                    if (min > max) throw new IllegalArgumentException();
                    value = Long.toString(ThreadLocalRandom.current().nextLong(min, (long) max + 1));
                } catch (Exception e) { throw new IllegalArgumentException("随机数格式为 {{$randomInt:最小值:最大值}}，范围包含两端"); }
            } else if (name.startsWith("$date:")) {
                try { value = DateTimeFormatter.ofPattern(name.substring(6)).withZone(ZoneId.of("Asia/Shanghai")).format(now); }
                catch (Exception e) { throw new IllegalArgumentException("日期格式无效：" + name.substring(6)); }
            } else return null;
            dynamic.put(name, value);
            return value;
        }
    }

    public static List<ApiBodyAssert.Rule> assertionRules(String text, Environment environment,
            Map<String, String> run, Map<String, String> data) {
        return assertionRules(text, environment, run, data, null);
    }
    public static List<ApiBodyAssert.Rule> assertionRules(String text, Environment environment,
            Map<String, String> run, Map<String, String> data, Map<String, String> overrides) {
        var resolver = new Resolver(ApiVariables.overrideBody(environment.vars(), overrides), run, data, new LinkedHashMap<>(), new ArrayList<>(), Instant.now(), false);
        var rules = ApiBodyAssert.parse(text).stream().map(rule -> new ApiBodyAssert.Rule(rule.path(), rule.op(),
                "schema".equalsIgnoreCase(rule.op()) ? rule.expect() : resolver.restore(resolver.resolve(rule.expect(), "ASSERT")))).toList();
        if (!resolver.missing.isEmpty()) throw new IllegalArgumentException("断言期望值存在未解析变量：" + String.join(", ", resolver.missing));
        ApiBodyAssert.validateRules(stringify(rules));
        return rules;
    }

    static Map<String, String> parseHeaders(String text) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (text == null || text.isBlank()) return headers;
        try {
            JsonNode root = JSON.readTree(text);
            if (root == null || !root.isObject()) throw new IllegalArgumentException();
            root.fields().forEachRemaining(e -> {
                if (!e.getValue().isValueNode() || e.getValue().isNull())
                    throw new IllegalArgumentException("请求头的值必须是文本或标量");
                headers.put(e.getKey(), e.getValue().asText());
            });
            headers.forEach(ApiHeaders::validate);return headers;
        } catch (IllegalArgumentException e) {if(e.getMessage()!=null)throw e;throw new IllegalArgumentException("请求头要写成 JSON 对象，值为文本或标量");}
        catch (Exception e) { throw new IllegalArgumentException("请求头要写成 JSON 对象，值为文本或标量"); }
    }
    private static boolean containsHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
    }
    private static void replaceHeader(Map<String, String> headers, String name, String value, List<String> warnings) {
        if (containsHeader(headers, name)) warnings.add("鉴权设置将覆盖请求头 " + name);
        headers.keySet().removeIf(key -> key.equalsIgnoreCase(name));
        headers.put(name, value);
    }
    static String stringify(Object object) {
        try { return JSON.writeValueAsString(object); }
        catch (Exception e) { throw new IllegalArgumentException("配置不能序列化", e); }
    }
    private static String query(String url, String name, String value, boolean replace) {
        if (url == null) return null;
        int hash = url.indexOf('#');
        String fragment = hash < 0 ? "" : url.substring(hash);
        String base = hash < 0 ? url : url.substring(0, hash);
        if (replace && base.contains("?")) {
            int q = base.indexOf('?');
            String filtered = java.util.Arrays.stream(base.substring(q + 1).split("&"))
                    .filter(p -> !java.net.URLDecoder.decode(p.split("=", 2)[0], StandardCharsets.UTF_8).equals(name))
                    .collect(java.util.stream.Collectors.joining("&"));
            base = base.substring(0, q) + (filtered.isEmpty() ? "" : "?" + filtered);
        }
        String join = base.endsWith("?") || base.endsWith("&") ? "" : base.contains("?") ? "&" : "?";
        return base + join + encode(name) + "=" + encode(value == null ? "" : value) + fragment;
    }
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static Map<String, String> maskedHeaders(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>(headers);
        result.replaceAll((name, value) -> name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Cookie")
                || name.toLowerCase(java.util.Locale.ROOT).contains("key") ? "***" : value);
        return result;
    }
}
