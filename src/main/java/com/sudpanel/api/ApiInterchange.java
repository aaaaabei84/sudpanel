package com.sudpanel.api;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

/** 本机纯转换：只解析文本，不执行 shell、脚本、不读文件、不访问网络。 */
public final class ApiInterchange {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static {
        JSON.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(128).build());
    }
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");
    private static final Pattern SECRET = Pattern.compile("(?i)(?:authorization|proxy-authorization|cookie|set-cookie|.*(?:token|secret|password|passwd|api[_-]?key|access[_-]?key|sign).*)");
    private static final int MAX_CONTENT = 2_000_000;
    private static final int MAX_REQUESTS = 1000;
    private ApiInterchange() {}

    public record Input(String format,String content,String environmentContent,Map<String,List<String>>variableScopes,Boolean includeSecrets,Boolean skipInvalidRequests,Boolean enableSignatureScripts) {
        public Input(String format,String content,String environmentContent,Map<String,List<String>>variableScopes,Boolean includeSecrets,Boolean skipInvalidRequests){this(format,content,environmentContent,variableScopes,includeSecrets,skipInvalidRequests,null);}
        public Input(String format,String content,String environmentContent,Map<String,List<String>>variableScopes,Boolean includeSecrets){this(format,content,environmentContent,variableScopes,includeSecrets,null,null);}
        public Input(String format,String content,String environmentContent){this(format,content,environmentContent,null,null,null,null);}
    }
    public record VariableMapping(String name,String source,boolean enabled,boolean secret,boolean overridden,List<String>scopes,List<String>references,String valuePreview) {}
    private record VariableSource(String name,String value,String source,boolean enabled,boolean secret) {}
    public record ImportedRequest(String folder,ApiSpec spec,String folderKey) {
        public ImportedRequest(String folder,ApiSpec spec){this(folder,spec,null);}
    }
    public record ImportedFolder(String key,String name,List<String> path,String note,int requestCount) {}
    public record ImportLoss(String location,String code,String message,boolean blocking) {}
    public record SkippedRequest(String path,String name,String reason) {}
    public static final String POSTMAN_NOTE_PREFIX="[POSTMAN_IMPORT_V1]";
    public record Preview(String format, String name, List<ImportedRequest> requests, ApiVariables.VarSet variables,
                          String environmentName,List<String>warnings,List<String>errors,List<ImportedFolder>folders,List<ImportLoss>losses,List<VariableMapping>variableMappings,List<SkippedRequest>skippedRequests,int skippedCount) {
        public Preview(String format,String name,List<ImportedRequest>requests,ApiVariables.VarSet variables,String environmentName,List<String>warnings,List<String>errors,List<ImportedFolder>folders,List<ImportLoss>losses,List<VariableMapping>variableMappings){this(format,name,requests,variables,environmentName,warnings,errors,folders,losses,variableMappings,List.of(),0);}
        public Preview(String format,String name,List<ImportedRequest>requests,ApiVariables.VarSet variables,String environmentName,List<String>warnings,List<String>errors){this(format,name,requests,variables,environmentName,warnings,errors,List.of(),List.of(),List.of(),List.of(),0);}
    }
    public record CurlExport(String curl, List<String> warnings) {}

    public static Preview preview(Input input) {return parse(input,true);}
    static Preview forCommit(Input input){return parse(input,false);}
    private static Preview parse(Input input,boolean publicPreview) {
        var state = new State(input == null || input.format() == null ? "" : input.format().toUpperCase(Locale.ROOT));
        try {
            if (input == null || input.content() == null || input.content().isBlank()) throw bad("请输入待导入文本");
            if (input.content().length() > MAX_CONTENT || input.environmentContent() != null && input.environmentContent().length() > MAX_CONTENT)
                throw bad("单份导入文本最多 200 万字符");
            if (Boolean.TRUE.equals(input.skipInvalidRequests()) && !"POSTMAN".equals(state.format))
                throw bad("跳过错误请求仅支持 Postman 集合导入");
            if (Boolean.TRUE.equals(input.enableSignatureScripts()) && !"POSTMAN".equals(state.format))
                throw bad("启用前置签名脚本仅支持 Postman 集合导入");
            state.skipInvalidRequests=Boolean.TRUE.equals(input.skipInvalidRequests());
            state.enableSignatureScripts=Boolean.TRUE.equals(input.enableSignatureScripts());
            switch (state.format) {
                case "CURL" -> state.requests.add(new ImportedRequest("cURL", curl(input.content(), state)));
                case "POSTMAN" -> postman(input, state);
                case "OPENAPI" -> {
                    if (input.environmentContent() != null && !input.environmentContent().isBlank() || input.variableScopes()!=null&&!input.variableScopes().isEmpty()) throw bad("OpenAPI 不接收 Postman 附加环境/作用域映射，请导入后配置环境变量");
                    return ApiOpenApi.preview(input.content());
                }
                default -> throw bad("导入格式仅支持 CURL / POSTMAN / OPENAPI");
            }
        } catch (IllegalArgumentException e) { state.errors.add(e.getMessage()); }
        catch (Exception e) { state.errors.add("导入内容无法解析，请核对格式"); }
        state.scopeOverrides=input==null||input.variableScopes()==null?Map.of():input.variableScopes();
        var vars=scopes(state);boolean mask=publicPreview&&(input==null||!Boolean.TRUE.equals(input.includeSecrets()));
        if(mask)vars=new ApiVariables.VarSet(maskVars(vars.base()),maskVars(vars.header()),maskVars(vars.body()));
        var mappings=state.variableSources.stream().map(v->{boolean overridden=v.enabled()&&state.activeSources.get(v.name())!=v;var groups=!v.enabled()||overridden?List.<String>of():state.mapping.getOrDefault(v.name(),List.of());String value=mask&&v.secret()?"••••••":v.value().length()>160?v.value().substring(0,160)+"…":v.value();return new VariableMapping(v.name(),v.source(),v.enabled(),v.secret(),overridden,groups,state.references.getOrDefault(v.name(),List.of()),value);}).toList();
        return new Preview(state.format,state.name,List.copyOf(state.requests),vars,state.environmentName,List.copyOf(state.warnings),List.copyOf(state.errors),List.copyOf(state.folders.values()),List.copyOf(state.losses),mappings,List.copyOf(state.skippedRequests),state.skippedRequests.size());
    }

    private static final class State {
        final String format;
        String name = "导入请求";
        String environmentName;
        final List<ImportedRequest> requests = new ArrayList<>();
        final Map<String, String> values = new LinkedHashMap<>();
        final Set<String> warnings = new LinkedHashSet<>();
        final List<String> errors = new ArrayList<>();
        final Map<String,ImportedFolder> folders=new LinkedHashMap<>();
        final List<ImportLoss> losses=new ArrayList<>();
        final List<SkippedRequest> skippedRequests=new ArrayList<>();
        boolean skipInvalidRequests;
        boolean enableSignatureScripts;
        int requestCount;
        long preservedNoteChars;
        final List<VariableSource>variableSources=new ArrayList<>();
        final Map<String,VariableSource>activeSources=new LinkedHashMap<>();
        Map<String,List<String>>scopeOverrides=Map.of();
        final Map<String,List<String>>mapping=new LinkedHashMap<>(),references=new LinkedHashMap<>();
        State(String format) { this.format = format; }
    }

    private static ApiSpec curl(String content, State state) {
        List<String> words = shellWords(content);
        if (words.isEmpty() || !Set.of("curl", "/usr/bin/curl", "curl.exe").contains(words.getFirst()))
            throw bad("文本必须是一条 curl 命令");
        String url = null, method = null, body = null;
        int timeoutMs = 10000;
        boolean head = false, get = false, insecure = false, json = false;
        String redirect = "NEVER"; // curl 默认不跟随；不能继承平台 NORMAL 而改变导入语义。
        var headers = new LinkedHashMap<String, String>();
        ApiRequestOptions.Auth auth = noAuth();
        boolean options = true;
        for (int i = 1; i < words.size(); i++) {
            String word = words.get(i), flag = word, attached = null;
            if (options && word.equals("--")) { options = false; continue; }
            if (options && word.startsWith("--") && word.contains("=")) {
                int eq = word.indexOf('='); flag = word.substring(0, eq); attached = word.substring(eq + 1);
            } else if (options && word.length() > 2 && word.charAt(0) == '-' && "XHdubAem".indexOf(word.charAt(1)) >= 0) {
                flag = word.substring(0, 2); attached = word.substring(2);
            }
            if (!options || !word.startsWith("-")) {
                if (url != null) throw bad("一次只支持一个请求地址；多命令/多 URL 不会执行");
                url = word; continue;
            }
            if (Set.of("-s", "--silent", "-S", "--show-error", "-v", "--verbose", "-i", "--include", "--globoff", "--http1.1").contains(flag)) continue;
            if (Set.of("-I", "--head").contains(flag)) { head = true; continue; }
            if (Set.of("-G", "--get").contains(flag)) { get = true; continue; }
            if (Set.of("-k", "--insecure").contains(flag)) { insecure = true; continue; }
            if (Set.of("-L", "--location").contains(flag)) { redirect = "NORMAL"; state.warnings.add("-L 已转换为 NORMAL 重定向；HTTPS 降级到 HTTP 不跟随"); continue; }
            if (flag.equals("--compressed")) { putHeader(headers, "Accept-Encoding", "gzip, deflate", false); state.warnings.add("--compressed 转换为 gzip/deflate，未启用其他压缩算法"); continue; }
            if (!Set.of("-X", "--request", "-H", "--header", "--url", "-d", "--data", "--data-raw", "--data-binary",
                    "--data-urlencode", "--json", "-u", "--user", "--oauth2-bearer", "-b", "--cookie", "-A", "--user-agent", "-e", "--referer", "-m", "--max-time").contains(flag))
                throw bad("不支持的 cURL 选项：" + flag + "；为避免改变请求，未忽略该选项");
            String value = attached;
            if (value == null) { if (++i >= words.size()) throw bad("cURL 选项缺少值：" + flag); value = words.get(i); }
            switch (flag) {
                case "-X", "--request" -> method = value.toUpperCase(Locale.ROOT);
                case "-H", "--header" -> parseHeader(headers, value);
                case "--url" -> { if (url != null) throw bad("一次只支持一个请求地址"); url = value; }
                case "-d", "--data", "--data-raw", "--data-binary", "--data-urlencode", "--json" -> {
                    if (!flag.equals("--data-raw") && value.startsWith("@")) throw bad("禁止读取 @文件；请直接粘贴请求体");
                    if (flag.equals("--data-urlencode")) {
                        int eq = value.indexOf('=');
                        if (eq < 0 && value.contains("@")) throw bad("--data-urlencode 的 @文件语法不支持");
                        value = eq < 0 ? formEncode(value)
                                : (eq == 0 ? formEncode(value.substring(1)) : value.substring(0, eq) + "=" + formEncode(value.substring(eq + 1)));
                    }
                    if (flag.equals("--json")) {
                        if (body != null) throw bad("--json 不能与其他正文选项混用");
                        json = true;
                    } else if (json) throw bad("--json 不能与其他正文选项混用");
                    body = body == null ? value : body + "&" + value;
                }
                case "-u", "--user" -> {
                    if (!"NONE".equals(auth.type())) throw bad("不能同时转换多个鉴权 helper");
                    int colon = value.indexOf(':');
                    if (colon < 0) throw bad("Basic 凭据需填写 用户名:密码；不支持交互输入密码");
                    auth = new ApiRequestOptions.Auth("BASIC", null, value.substring(0, colon), value.substring(colon + 1), null, null, null);
                }
                case "--oauth2-bearer" -> {
                    if (!"NONE".equals(auth.type())) throw bad("不能同时转换多个鉴权 helper");
                    auth = new ApiRequestOptions.Auth("BEARER", value, null, null, null, null, null);
                }
                case "-b", "--cookie" -> {
                    if (!value.contains("=")) throw bad("不支持 Cookie 文件，只支持直接填写 name=value");
                    putHeader(headers, "Cookie", value, false);
                }
                case "-A", "--user-agent" -> putHeader(headers, "User-Agent", value, false);
                case "-e", "--referer" -> putHeader(headers, "Referer", value, false);
                case "-m", "--max-time" -> {
                    try {
                        timeoutMs = new BigDecimal(value).multiply(BigDecimal.valueOf(1000)).intValueExact();
                        if (timeoutMs < 1 || timeoutMs > 3_600_000) throw new ArithmeticException();
                    } catch (NumberFormatException | ArithmeticException e) { throw bad("--max-time 需为 0.001 至 3600 秒且精确到毫秒"); }
                }
                default -> throw bad("无法转换 cURL 选项");
            }
        }
        if (url == null || url.isBlank()) throw bad("cURL 缺少请求地址");
        if (head && get) throw bad("--head 与 --get 不能同时转换");
        if (get && body != null) { url = appendQuery(url, body); body = null; }
        if (method == null) method = head ? "HEAD" : get ? "GET" : body != null ? "POST" : "GET";
        checkMethod(method);
        if (head && !method.equals("HEAD")) throw bad("--head 与自定义方法冲突，无法保证原请求语义");
        if (body != null) {
            putHeader(headers, "Content-Type", json ? "application/json" : "application/x-www-form-urlencoded", false);
            if (json) putHeader(headers, "Accept", "application/json", false);
        }
        if (!"NONE".equals(auth.type()) && headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("Authorization"))) {
            auth = noAuth(); state.warnings.add("显式 Authorization 请求头优先；未启用 cURL 鉴权 helper，避免覆盖原头");
        }
        state.name = "cURL 导入";
        var transport = new ApiRequestOptions.Transport(redirect, "DIRECT", null, null, "OFF", null);
        var result = spec("cURL 导入", method, url, headers, body, "从 cURL 导入；仅解析，不执行原命令。", insecure,
                new ApiRequestOptions(body == null ? "NONE" : "RAW", List.of(), auth, List.of(), List.of(), transport, true));
        return new ApiSpec(result.id(), result.name(), result.method(), result.url(), result.headers(), result.body(), result.note(),
                result.expectStatus(), result.expectCode(), timeoutMs, result.insecure(), result.enabled(), null, null, result.options());
    }

    /** 只做 POSIX 参数分词；命令操作符和替换语法主动拒绝，原字符串绝不交给 shell。 */
    static List<String> shellWords(String text) {
        if (text.contains("$(") || text.indexOf('`') >= 0) throw bad("不支持命令替换或反引号；不会执行 shell");
        var out = new ArrayList<String>();
        var word = new StringBuilder();
        char quote = 0;
        boolean begun = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == '\'') { if (c == '\'') quote = 0; else word.append(c); begun = true; continue; }
            if (c == '\\') {
                if (i + 1 >= text.length()) throw bad("cURL 末尾有未完成的转义");
                char next = text.charAt(++i);
                if (next == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { i++; continue; }
                if (next == '\n') continue;
                if (quote == '"' && next != '"' && next != '\\' && next != '$' && next != '`') word.append('\\');
                word.append(next); begun = true; continue;
            }
            if (c == '$' && i + 1 < text.length() && (Character.isLetter(text.charAt(i + 1)) || "_{'".indexOf(text.charAt(i + 1)) >= 0))
                throw bad("不支持 shell 环境变量展开或 ANSI-C 引号；请粘贴字面值或平台 {{变量}} 写法");
            if (quote == '"') { if (c == '"') quote = 0; else word.append(c); begun = true; continue; }
            if (c == '\'' || c == '"') { quote = c; begun = true; continue; }
            if ("|;&<>".indexOf(c) >= 0) throw bad("不支持 shell 操作符/重定向/多命令");
            if (Character.isWhitespace(c)) { if (begun) { out.add(word.toString()); word.setLength(0); begun = false; } }
            else { word.append(c); begun = true; }
        }
        if (quote != 0) throw bad("cURL 引号未闭合");
        if (begun) out.add(word.toString());
        return out;
    }

    private static void postman(Input input, State s) throws Exception {
        JsonNode root = document(input.content());
        if (!root.isObject()) throw bad("Postman 内容必须是 JSON 对象");
        if (root.has("values") && !root.has("item")) {
            environment(root, s);
            if (input.environmentContent() != null && !input.environmentContent().isBlank()) throw bad("单独环境文件不需要第二份环境文本");
            s.name = s.environmentName;
            return;
        }
        String schema = text(root.path("info"), "schema", "");
        if (!schema.contains("/v2.1.0/")) throw bad("只支持 Postman Collection v2.1 JSON，请从 Postman 导出该版本");
        if (!root.path("item").isArray()) throw bad("Postman Collection 缺少 item 数组");
        s.name = text(root.path("info"), "name", "Postman 导入");
        readVariables(root.path("variable"), false, s);
        if (input.environmentContent() != null && !input.environmentContent().isBlank()) environment(document(input.environmentContent()), s);
        String rootNote=sourceNote(root,"collection",s.name,s);
        s.folders.put("root",new ImportedFolder("root",s.name,List.of(s.name),rootNote,0));
        if (root.has("protocolProfileBehavior")) s.warnings.add("集合 protocolProfileBehavior 未迁移；传输行为使用各请求显式设置");
        walk(root.path("item"),List.of(s.name),"root",root.get("auth"),rootNote,signatureScripts(root,s.name,s),s,0);
        if(!s.skippedRequests.isEmpty()) {
            s.warnings.add("已显式跳过"+s.skippedRequests.size()+"个错误请求；仅追加预览中的有效请求，未修改原文");
            if(s.requests.isEmpty())s.errors.add("跳过错误请求后没有有效请求可导入；未提交环境或目录");
        }
        if (s.requests.isEmpty() && s.errors.isEmpty()) s.warnings.add("集合只有目录，没有可执行请求");
        s.warnings.add(s.enableSignatureScripts ? "已显式启用前置签名脚本导入；仅保存为请求级 SCRIPT，导入不执行；测试/禁用脚本仅备注，不代表完整 Postman 兼容" : "Postman 脚本/测试脚本不执行；响应示例不转换为断言；导入后请预览并核对实际请求");
    }

    private static void environment(JsonNode root, State s) {
        if (!root.isObject() || !root.path("values").isArray()) throw bad("Postman 环境需要 values 数组");
        s.environmentName = text(root, "name", "Postman 环境");
        readVariables(root.path("values"), true, s);
        s.warnings.add("环境变量优先于集合变量；只追加新环境，不覆盖已有环境；全局/data/local 作用域未导入");
    }

    private static void readVariables(JsonNode rows, boolean environment, State s) {
        if (rows.isMissingNode() || rows.isNull()) return;
        if (!rows.isArray()) throw bad("变量必须是数组");
        if (rows.size() > 1000) throw bad("每份变量最多 1000 条");
        for (JsonNode row : rows) {
            if (!row.isObject()) throw bad("变量行必须是对象");
            String key = text(row, "key", text(row, "id", ""));
            if (key.isBlank()) { s.warnings.add("已跳过空名称变量"); continue; }
            boolean enabled=!(row.path("disabled").asBoolean(false)||environment&&row.has("enabled")&&!row.path("enabled").asBoolean());
            boolean secret=text(row,"type","").equalsIgnoreCase("secret")||SECRET.matcher(key).matches();
            JsonNode raw=row.get("value");String value=raw==null||raw.isNull()?"":raw.isValueNode()?raw.asText():raw.toString();
            var source=new VariableSource(key,value,environment?"ENVIRONMENT":"COLLECTION",enabled,secret);s.variableSources.add(source);
            if(!enabled){s.warnings.add("已跳过未启用变量："+key);continue;}
            if(key.startsWith("$")||key.startsWith("run.")||key.startsWith("data.")){s.errors.add("变量名占用平台保留命名空间："+key);continue;}
            if(s.activeSources.containsKey(key))s.warnings.add("同名变量采用后读取的"+(environment?"环境":"集合")+"值："+key);
            s.activeSources.put(key,source);s.values.put(key,value);
            if(secret)s.warnings.add("敏感变量已保留标记，默认预览隐藏值："+key+"；提交从原文重新解析保存本机");
        }
    }

    private static void walk(JsonNode rows,List<String>path,String folderKey,JsonNode inheritedAuth,String inheritedNote,String inheritedScript,State s,int depth) {
        if(depth>32)throw bad("集合分组嵌套不能超过32层");int index=0;
        for(JsonNode item:rows){String location=folderKey+"/item/"+index++;
            if(!item.isObject()){s.errors.add(location+"：集合item必须是对象");s.losses.add(new ImportLoss(location,"INVALID_ITEM","集合item必须是对象",true));continue;}
            // Count source request entries, including unsupported ones: skipping must not bypass the collection limit.
            if(!item.has("item")&&++s.requestCount>MAX_REQUESTS)throw bad("最多导入1000个请求（含错误请求）");
            String name=text(item,"name","未命名请求");var itemPath=new ArrayList<>(path);itemPath.add(name);
            String own;try{own=sourceNote(item,item.has("item")?"folder":"request",String.join(" / ",itemPath),s);}catch(IllegalArgumentException e){s.errors.add(location+"："+e.getMessage());continue;}
            String note=own.isBlank()?inheritedNote:inheritedNote+"\n\n"+own;
            String mergedScript=mergeSignatureScripts(inheritedScript,signatureScripts(item,String.join(" / ",itemPath),s));
            if(item.has("variable")){s.warnings.add("分组/条目变量未迁移："+location+"；请移动到环境后导入");s.losses.add(new ImportLoss(location,"ITEM_VARIABLES","分组/条目变量仅保存原文，未应用作用域",false));note+="\n[未应用的条目变量原文]\n"+item.get("variable");}
            JsonNode auth=item.get("auth")==null||item.get("auth").isNull()?inheritedAuth:item.get("auth");
            if(item.has("item")){
                if(!item.path("item").isArray()){s.errors.add(location+"：分组item需要数组");s.losses.add(new ImportLoss(location,"INVALID_FOLDER","分组item需要数组",true));continue;}
                if(s.folders.size()>=2000)throw bad("目录最多2000个，请分批导入");reserveNote(s,note);
                s.folders.put(location,new ImportedFolder(location,String.join(" / ",itemPath),List.copyOf(itemPath),note,0));
                walk(item.path("item"),itemPath,location,auth,note,mergedScript,s,depth+1);
            }else {
                ApiSpec spec;
                try{spec=withSignatureScript(postmanRequest(name,item.get("request"),auth,s),mergedScript,s);}
                catch(IllegalArgumentException e){
                    if(e instanceof ImportLimitException)throw e;
                    if(s.skipInvalidRequests){s.skippedRequests.add(new SkippedRequest(location,name,e.getMessage()));s.losses.add(new ImportLoss(location,"REQUEST_SKIPPED",e.getMessage(),false));}
                    else{s.errors.add(location+"（"+name+"）："+e.getMessage());s.losses.add(new ImportLoss(location,"REQUEST_UNSUPPORTED",e.getMessage(),true));}
                    continue;
                }
                String full=POSTMAN_NOTE_PREFIX+"\n来源目录："+ApiRequestEngine.stringify(path)+"\n"+spec.note()+"\n"+note;
                spec=new ApiSpec(spec.id(),spec.name(),spec.method(),spec.url(),spec.headers(),spec.body(),full,spec.expectStatus(),spec.expectCode(),spec.timeoutMs(),spec.insecure(),spec.enabled(),spec.groupId(),spec.asserts(),spec.options());
                // This is a whole-import budget, not a skippable request conversion failure.
                reserveNote(s,full);s.requests.add(new ImportedRequest(String.join(" / ",path),spec,folderKey));var f=s.folders.get(folderKey);s.folders.put(folderKey,new ImportedFolder(f.key(),f.name(),f.path(),f.note(),f.requestCount()+1));
                if(item.path("response").isArray()&&!item.path("response").isEmpty())s.losses.add(new ImportLoss(location,"RESPONSE_EXAMPLES","响应示例未转换为断言",false));
            }
        }
    }
    /** Only explicit opt-in reads executable prerequest source. Import itself never evaluates it. */
    private static String signatureScripts(JsonNode node,String location,State s) {
        if(!s.enableSignatureScripts || !node.has("event"))return "";
        JsonNode events=node.path("event");
        if(!events.isArray())throw bad("事件event需要数组："+location);
        String combined="";
        for(JsonNode event:events) {
            if(!event.isObject())throw bad("事件行必须是对象："+location);
            if(!"prerequest".equals(text(event,"listen","")) || event.path("disabled").asBoolean(false))continue;
            JsonNode script=event.path("script");
            if(!script.isObject())throw bad("前置事件script需要对象："+location);
            if(script.path("disabled").asBoolean(false))continue;
            JsonNode exec=script.get("exec");String source;
            if(exec!=null && exec.isTextual())source=exec.asText();
            else if(exec!=null && exec.isArray()) {
                var lines=new ArrayList<String>();
                for(JsonNode line:exec) {if(!line.isTextual())throw bad("前置脚本exec数组必须全部为字符串："+location);lines.add(line.asText());}
                source=String.join("\n",lines);
            }else throw bad("前置脚本exec需要字符串或字符串数组："+location);
            combined=mergeSignatureScripts(combined,source);
        }
        return combined;
    }
    private static String mergeSignatureScripts(String inherited,String own) {
        String combined=inherited.isEmpty()?own:own.isEmpty()?inherited:inherited+"\n;\n"+own;
        if(combined.getBytes(StandardCharsets.UTF_8).length>65536)
            throw new ImportLimitException("每请求合并前置签名脚本超过64KiB UTF-8；请拆分或缩短脚本，未截断原文");
        return combined;
    }
    private static ApiSpec withSignatureScript(ApiSpec spec,String script,State s) {
        if(!s.enableSignatureScripts || script.isBlank())return spec;
        var options=spec.options();var auth=options==null?null:options.auth();
        if(auth!=null && auth.type()!=null && !Set.of("NONE","INHERIT").contains(auth.type()))
            throw bad("前置签名脚本与已有 "+auth.type()+" 鉴权冲突；请先在源请求明确选择一种鉴权，不会静默覆盖");
        var scripted=new ApiRequestOptions.Auth("SCRIPT",auth==null?null:auth.token(),auth==null?null:auth.username(),auth==null?null:auth.password(),auth==null?null:auth.keyName(),auth==null?null:auth.keyValue(),auth==null?null:auth.in(),auth==null?null:auth.profileId(),auth==null?null:auth.digestAlgorithm(),auth==null?null:auth.digestQop(),script,null);
        var updated=new ApiRequestOptions(options.bodyType(),options.params(),scripted,options.extractors(),options.fields(),options.transport(),options.strictVariables(),options.fileBody(),options.parts(),options.headerRows());
        return new ApiSpec(spec.id(),spec.name(),spec.method(),spec.url(),spec.headers(),spec.body(),spec.note(),spec.expectStatus(),spec.expectCode(),spec.timeoutMs(),spec.insecure(),spec.enabled(),spec.groupId(),spec.asserts(),updated);
    }
    private static void reserveNote(State s,String value){s.preservedNoteChars+=value.length();if(s.preservedNoteChars>8_000_000)throw bad("继承脚本和描述的备注累计超过800万字符，请拆分集合导入；未截断原文");}
    private static String sourceNote(JsonNode node,String scope,String location,State s) {
        var note=new StringBuilder();String description=description("collection".equals(scope)&&node.path("info").has("description")?node.path("info").get("description"):node.get("description"));if(!description.isBlank())note.append("[Postman ").append(scope).append(" 描述]\n").append(description);
        if(node.has("event")) {
            if(!node.path("event").isArray())throw bad("事件event需要数组："+location);
            if(!node.path("event").isEmpty()){
                s.warnings.add((s.enableSignatureScripts ? "前置脚本按显式选择转换为请求级签名；测试/禁用脚本仅备注，导入不执行：" : "脚本未执行/未转换，原文已保存在导入备注：")+location);
                s.losses.add(new ImportLoss(location,s.enableSignatureScripts?"SCRIPT_IMPORT_OPT_IN":"SCRIPT_NOT_EXECUTED",s.enableSignatureScripts?"是否启用签名以请求级 SCRIPT 配置为准；测试/禁用脚本仅保留原文，导入未运行脚本":"脚本原文保存在备注；未执行且未迁移为断言",false));
                note.append("\n[Postman ").append(scope).append(" event原文：仅保存，未执行]\n").append(node.path("event"));
            }
        }
        for(String key:List.of("certificate","proxy","protocolProfileBehavior"))if(node.has(key)){
            boolean blocking=!key.equals("protocolProfileBehavior");s.losses.add(new ImportLoss(location,key.toUpperCase(Locale.ROOT),"此配置未迁移："+key,blocking));
            if(blocking)throw bad("包含"+key+"配置，需手工配置；不会静默忽略："+location);
            s.warnings.add("protocolProfileBehavior未迁移："+location);
            note.append("\n[未迁移配置原文 ").append(key).append("]\n").append(node.get(key));
        }
        return note.toString();
    }

    private static ApiSpec postmanRequest(String name, JsonNode request, JsonNode inheritedAuth, State s) {
        if (request == null) throw bad("缺少 request");
        if (request.isTextual()) {
            if(request.asText().isBlank())throw bad("缺少 URL");
            return spec(name, "GET", dynamic(request.asText(), s), Map.of(), null,
                "从 Postman Collection v2.1 导入。", false, new ApiRequestOptions("NONE", List.of(), postmanAuth(inheritedAuth, s), List.of(), List.of(), transport("NORMAL"), true));
        }
        if (!request.isObject()) throw bad("request 必须是对象或 URL 字符串");
        String method = text(request, "method", "GET").toUpperCase(Locale.ROOT);
        checkMethod(method);
        var headers = new LinkedHashMap<String, String>();
        JsonNode header = request.get("header");
        if (header != null && header.isTextual()) {
            for (String line : header.asText().split("\\r?\\n")) if (!line.isBlank()) parseHeader(headers, dynamic(line, s));
        } else if (header != null && header.isArray()) {
            for (JsonNode row : header) {
                if (!row.isObject()) throw bad("请求头行必须是对象");
                if (row.path("disabled").asBoolean(false)) { s.warnings.add("未启用请求头已跳过：" + text(row, "key", "空名称")); continue; }
                putHeader(headers, dynamic(text(row, "key", ""), s), dynamic(text(row, "value", ""), s), true);
            }
        } else if (header != null && !header.isNull()) throw bad("header 必须是数组或文本");
        var params = new ArrayList<ApiRequestOptions.Param>();
        String url = postmanUrl(request.get("url"), params, s);
        String body = null, bodyType = "NONE";
        var fields = new ArrayList<ApiRequestOptions.Param>();
        JsonNode bodyNode = request.path("body");
        if (!bodyNode.isMissingNode() && !bodyNode.isNull() && !bodyNode.path("disabled").asBoolean(false)) {
            if (!bodyNode.isObject()) throw bad("body 必须是对象");
            String mode = text(bodyNode, "mode", "raw");
            if (mode.equals("raw")) {
                body = dynamic(text(bodyNode, "raw", ""), s); bodyType = "RAW";
                if (text(bodyNode.path("options").path("raw"), "language", "").equals("json")) putHeader(headers, "Content-Type", "application/json", false);
            } else if (mode.equals("urlencoded")) {
                bodyType = "URLENCODED";
                readParams(bodyNode.path("urlencoded"), fields, s);
            } else throw bad("未转换正文模式 " + mode + "（文件/表单上传/GraphQL 不会被当成空正文）");
        }
        JsonNode auth = request.get("auth") == null || request.get("auth").isNull() ? inheritedAuth : request.get("auth");
        if (request.has("certificate") || request.has("proxy")) throw bad("请求包含客户端证书或专用代理，需先手工配置；不会静默忽略");
        String note = description(request.get("description"));
        if (request.has("protocolProfileBehavior")) s.warnings.add("请求 protocolProfileBehavior 未迁移：" + name);
        return spec(name, method, dynamic(url, s), headers, body, note + (note.isBlank() ? "" : "\n") + "从 Postman Collection v2.1 导入；脚本/示例未转换。", false,
                new ApiRequestOptions(bodyType, params, postmanAuth(auth, s), List.of(), fields, transport("NORMAL"), true));
    }

    private static String postmanUrl(JsonNode node, List<ApiRequestOptions.Param> params, State s) {
        if (node == null) throw bad("缺少 URL");
        if (node.isTextual()) {if(node.asText().isBlank())throw bad("缺少 URL");return node.asText();}
        if (!node.isObject()) throw bad("URL 必须是文本或对象");
        String raw = text(node, "raw", "");
        if (raw.isBlank()) {
            String host = joined(node.get("host"), ".");
            if (host.isBlank()) throw bad("URL 缺少 raw/host");
            String protocol = text(node, "protocol", "");
            raw = (protocol.isBlank() ? "" : protocol + "://") + host;
            String port = text(node, "port", "");
            if (!port.isBlank()) raw += ":" + port;
            String path = joined(node.get("path"), "/");
            if (!path.isBlank()) raw += (path.startsWith("/") ? "" : "/") + path;
            if (node.has("hash")) raw += "#" + text(node, "hash", "");
        }
        if (node.has("query")) {
            readParams(node.path("query"), params, s);
            int hash = raw.indexOf('#'), q = raw.indexOf('?');
            if (q >= 0 && (hash < 0 || q < hash)) raw = raw.substring(0, q) + (hash < 0 ? "" : raw.substring(hash));
        }
        if (node.path("variable").isArray()) for (JsonNode variable : node.path("variable")) {
            String key = text(variable, "key", ""), value = dynamic(text(variable, "value", ""), s);
            if (key.isBlank()) continue;
            String replacement = value.isEmpty() ? "{{" + key + "}}" : value;
            if (value.isEmpty()) s.warnings.add("路径参数未填写：" + key);
            raw = raw.replaceAll("(?<=/):" + Pattern.quote(key) + "(?=/|[?#]|$)", java.util.regex.Matcher.quoteReplacement(replacement));
        }
        return raw;
    }

    private static void readParams(JsonNode rows, List<ApiRequestOptions.Param> out, State s) {
        if (!rows.isArray()) throw bad("参数需要数组");
        if (rows.size() > 200) throw new ImportLimitException("参数最多 200 条");
        for (JsonNode row : rows) {
            if (!row.isObject()) throw bad("参数行必须是对象");
            String key = dynamic(text(row, "key", ""), s);
            if (key.isEmpty()) throw bad("不支持空名称参数，未静默丢弃");
            out.add(new ApiRequestOptions.Param(key, dynamic(text(row, "value", ""), s), !row.path("disabled").asBoolean(false)));
        }
    }

    private static ApiRequestOptions.Auth postmanAuth(JsonNode node, State s) {
        if (node == null || node.isNull()) return noAuth();
        if (!node.isObject()) throw bad("auth 必须是对象");
        String type = text(node, "type", "noauth");
        var attrs = new LinkedHashMap<String, String>();
        if (node.path(type).isArray()) for (JsonNode item : node.path(type)) attrs.put(text(item, "key", ""), dynamic(text(item, "value", ""), s));
        return switch (type) {
            case "noauth" -> noAuth();
            case "bearer" -> new ApiRequestOptions.Auth("BEARER", attrs.get("token"), null, null, null, null, null);
            case "basic" -> new ApiRequestOptions.Auth("BASIC", null, attrs.get("username"), attrs.get("password"), null, null, null);
            case "apikey" -> {
                String in = attrs.getOrDefault("in", "header");
                if (!Set.of("header", "query").contains(in)) throw bad("API Key 位置不支持：" + in);
                yield new ApiRequestOptions.Auth("API_KEY", null, null, null, attrs.get("key"), attrs.get("value"), in.toUpperCase(Locale.ROOT));
            }
            default -> throw bad("鉴权方式 " + type + " 尚未转换；请先改为手动请求头或受支持的鉴权");
        };
    }

    private static List<ApiVariables.Var> maskVars(List<ApiVariables.Var>values){return values.stream().map(v->Boolean.TRUE.equals(v.secret())?new ApiVariables.Var(v.name(),"••••••",true):v).toList();}
    private static ApiVariables.VarSet scopes(State s) {
        var groups=new LinkedHashMap<String,List<ApiVariables.Var>>();for(String scope:List.of("BASE","HEADER","BODY"))groups.put(scope,new ArrayList<>());
        if(s.scopeOverrides.size()>1000)s.errors.add("变量映射最多1000项");
        for(String key:s.scopeOverrides.keySet())if(!s.values.containsKey(key))s.errors.add("映射变量未启用或不存在："+key);
        // Scan each request text once. A large collection must not rescan every body for every variable.
        var referencedScopes=new LinkedHashMap<String,Set<String>>();
        var referencedLocations=new LinkedHashMap<String,Set<String>>();
        int index=0;
        for(var item:s.requests){var spec=item.spec();var o=spec.options();String loc="requests["+index+++"]";
            collectReferences(spec.url(),"BASE",loc+".url",referencedScopes,referencedLocations);
            collectReferences(spec.headers(),"HEADER",loc+".headers",referencedScopes,referencedLocations);
            collectReferences(spec.body(),"BODY",loc+".body",referencedScopes,referencedLocations);
            if(o!=null){
                if(o.params()!=null)for(int i=0;i<o.params().size();i++){var p=o.params().get(i);if(!Boolean.FALSE.equals(p.enabled())){collectReferences(p.name(),"BASE",loc+".params["+i+"].name",referencedScopes,referencedLocations);collectReferences(p.value(),"BASE",loc+".params["+i+"].value",referencedScopes,referencedLocations);}}
                if(o.fields()!=null)for(int i=0;i<o.fields().size();i++){var p=o.fields().get(i);if(!Boolean.FALSE.equals(p.enabled())){collectReferences(p.name(),"BODY",loc+".fields["+i+"].name",referencedScopes,referencedLocations);collectReferences(p.value(),"BODY",loc+".fields["+i+"].value",referencedScopes,referencedLocations);}}
                var auth=o.auth();if(auth!=null){String scope="QUERY".equals(auth.in())?"BASE":"HEADER";
                    collectReferences(auth.token(),scope,loc+".auth.token",referencedScopes,referencedLocations);collectReferences(auth.username(),scope,loc+".auth.username",referencedScopes,referencedLocations);collectReferences(auth.password(),scope,loc+".auth.password",referencedScopes,referencedLocations);collectReferences(auth.keyName(),scope,loc+".auth.keyName",referencedScopes,referencedLocations);collectReferences(auth.keyValue(),scope,loc+".auth.keyValue",referencedScopes,referencedLocations);
                }
            }
        }
        referencedScopes.keySet().stream().filter(k->!s.values.containsKey(k)&&!k.startsWith("$")).forEach(k->s.warnings.add("请求引用未提供的变量："+k+"；发送前需在对应组补齐"));
        s.values.forEach((key,value)->{
            var inferred=new LinkedHashSet<>(referencedScopes.getOrDefault(key,Set.of()));
            var locations=new ArrayList<>(referencedLocations.getOrDefault(key,Set.of()));
            if(inferred.isEmpty()){inferred.addAll(groups.keySet());s.warnings.add("未引用变量默认保留在三组，可在映射表明确调整："+key);}
            List<String>selected=List.copyOf(inferred);if(s.scopeOverrides.containsKey(key)){
                var requested=s.scopeOverrides.get(key);
                if(requested==null||requested.isEmpty()||requested.stream().anyMatch(v->v==null||!groups.containsKey(v)))s.errors.add("变量至少映射到BASE/HEADER/BODY之一："+key);
                else {selected=requested.stream().distinct().toList();if(!selected.containsAll(inferred)&&!locations.isEmpty())s.warnings.add("显式映射未覆盖全部引用位置，导入后需核对严格模式："+key);}
            }
            s.mapping.put(key,selected);s.references.put(key,List.copyOf(locations));
            if(TOKEN.matcher(value).find())s.errors.add("变量值包含嵌套占位符，当前不能等价迁移："+key+"；请展开该值再导入");
            var source=s.activeSources.get(key);var variable=new ApiVariables.Var(key,value,source!=null&&source.secret());for(String scope:selected)groups.get(scope).add(variable);
        });
        return new ApiVariables.VarSet(List.copyOf(groups.get("BASE")),List.copyOf(groups.get("HEADER")),List.copyOf(groups.get("BODY")));
    }
    private static void collectReferences(String text,String scope,String location,Map<String,Set<String>>scopes,Map<String,Set<String>>locations){
        if(text==null)return;var matcher=TOKEN.matcher(text);
        while(matcher.find()){String key=matcher.group(1);scopes.computeIfAbsent(key,k->new LinkedHashSet<>()).add(scope);locations.computeIfAbsent(key,k->new LinkedHashSet<>()).add(location);}
    }

    private static String dynamic(String value, State s) {
        var m = TOKEN.matcher(value); var out = new StringBuilder();
        while (m.find()) {
            String key = m.group(1), replacement = m.group();
            if (key.equals("$guid") || key.equals("$randomUUID")) replacement = "{{$uuid}}";
            else if (key.startsWith("$") && !Set.of("$timestamp", "$timestampMs", "$uuid").contains(key)) s.warnings.add("Postman 动态变量未转换：" + key + "；发送前需修正");
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out); return out.toString();
    }

    public static CurlExport exportCurl(ApiSpec spec, boolean includeSecrets) {
        if (spec == null) throw bad("请求不能为空");
        String method = spec.method() == null ? "GET" : spec.method().toUpperCase(Locale.ROOT); checkMethod(method);
        var warnings = new LinkedHashSet<String>();
        var headers = ApiRequestEngine.parseHeaders(spec.headers());
        var options = spec.options();
        if(options!=null&&Set.of("MULTIPART","BINARY").contains(nullToEmpty(options.bodyType())))throw new IllegalArgumentException("文件请求不能导出为完整文本cURL；请下载原文件后自行使用--data-binary或-F构造");
        String url = spec.url();
        if (url == null || url.isBlank()) throw bad("请求地址不能为空");
        if (options != null && options.params() != null) for (var p : options.params()) {
            if (Boolean.FALSE.equals(p.enabled()) || p.name() == null || p.name().isBlank()) continue;
            url = appendQuery(url, encode(p.name()) + "=" + encode(p.value() == null ? "" : p.value()));
        }
        String body = options != null && "NONE".equals(options.bodyType()) ? null : spec.body();
        if (options != null && "URLENCODED".equals(options.bodyType())) {
            var parts = new ArrayList<String>();
            for (var p : options.fields() == null ? List.<ApiRequestOptions.Param>of() : options.fields())
                if (!Boolean.FALSE.equals(p.enabled()) && p.name() != null && !p.name().isBlank()) parts.add(formEncode(p.name()) + "=" + formEncode(!includeSecrets && sensitive(p.name()) ? "***" : p.value() == null ? "" : p.value()));
            body = String.join("&", parts); putHeader(headers, "Content-Type", "application/x-www-form-urlencoded", false);
        } else if (body != null && !body.isEmpty()) putHeader(headers, "Content-Type", options != null && "RAW".equals(options.bodyType()) ? "text/plain" : "application/json", false);
        if (options != null && options.auth() != null) {
            var a = options.auth();
            if ("BEARER".equals(a.type())) putHeader(headers, "Authorization", "Bearer " + (includeSecrets ? nullToEmpty(a.token()) : "***"), true, true);
            if ("BASIC".equals(a.type())) putHeader(headers, "Authorization", includeSecrets ? "Basic " + java.util.Base64.getEncoder().encodeToString((nullToEmpty(a.username()) + ":" + nullToEmpty(a.password())).getBytes(StandardCharsets.UTF_8)) : "Basic ***", true, true);
            if ("API_KEY".equals(a.type())) {
                if (a.keyName() == null || a.keyName().isBlank()) throw bad("API Key 名称不能为空");
                if ("QUERY".equals(a.in())) { url = removeQuery(url, a.keyName()); url = appendQuery(url, encode(a.keyName()) + "=" + encode(includeSecrets ? nullToEmpty(a.keyValue()) : "***")); }
                else putHeader(headers, a.keyName(), includeSecrets ? nullToEmpty(a.keyValue()) : "***", true, true);
            }
        }
        if (options == null || options.auth() == null || options.auth().type() == null || Set.of("INHERIT", "SUD").contains(nullToEmpty(options.auth().type()))) warnings.add("这是请求模板导出，环境变量和业务签名需在平台生成；cURL 不执行平台签名");
        if (!includeSecrets) {
            headers.replaceAll((name, value) -> sensitive(name) ? "***" : value);
            url = redactUrl(url);
            if (body != null && !body.isEmpty()) { body = "<REDACTED_REQUEST_BODY>"; warnings.add("默认省略整个请求体；此导出用于分享模板，不能直接复放。需要全文时请显式包含敏感值"); }
            warnings.add("请求头和 URL 按常见凭据字段脱敏；URL 路径及自定义字段请在分享前检查");
        }
        if (TOKEN.matcher(url + "\n" + headers + "\n" + body).find()) warnings.add("仍含变量占位符；请先替换后在终端运行");
        var command = new StringBuilder("curl --globoff --request ").append(quote(method)).append(" ").append(quote(url));
        if (spec.insecure()) command.append(" \\\n  --insecure");
        if (options != null && options.transport() != null && "NORMAL".equals(options.transport().redirect())) command.append(" \\\n  --location");
        if (options != null && options.transport() != null && "HTTP".equals(options.transport().proxyMode())) warnings.add("请求代理未包含在 cURL；需另行配置代理");
        for (var entry : headers.entrySet()) command.append(" \\\n  --header ").append(quote(entry.getKey() + ": " + entry.getValue()));
        if (body != null && !body.isEmpty()) command.append(" \\\n  --data-raw ").append(quote(body));
        return new CurlExport(command.toString(), List.copyOf(warnings));
    }

    private static String redactUrl(String url) {
        int hash = url.indexOf('#'); String fragment = hash < 0 ? "" : url.substring(hash); String main = hash < 0 ? url : url.substring(0, hash);
        main = main.replaceFirst("(?i)^(https?://)[^/?#@]+@", "$1***@");
        int q = main.indexOf('?'); if (q < 0) return main + fragment;
        String[] parts = main.substring(q + 1).split("&", -1);
        for (int i = 0; i < parts.length; i++) { int eq = parts[i].indexOf('='); String name = eq < 0 ? parts[i] : parts[i].substring(0, eq); if (sensitive(decode(name))) parts[i] = name + "=***"; }
        return main.substring(0, q + 1) + String.join("&", parts) + fragment;
    }
    private static String removeQuery(String url, String name) {
        int hash = url.indexOf('#'), q = url.indexOf('?'); if (q < 0 || hash >= 0 && q > hash) return url;
        String rest = url.substring(q + 1, hash < 0 ? url.length() : hash);
        String filtered = java.util.Arrays.stream(rest.split("&", -1)).filter(p -> !decode(p.split("=", 2)[0]).equals(name)).collect(java.util.stream.Collectors.joining("&"));
        return url.substring(0, q) + (filtered.isEmpty() ? "" : "?" + filtered) + (hash < 0 ? "" : url.substring(hash));
    }
    private static void scripts(JsonNode node, String name, State s) { if (node.path("event").isArray() && !node.path("event").isEmpty()) s.warnings.add("脚本未执行/未转换：" + name); }
    private static String joined(JsonNode node, String delimiter) { if (node == null || node.isNull()) return ""; if (node.isTextual()) return node.asText(); if (!node.isArray()) throw bad("URL 组件类型无效"); var parts = new ArrayList<String>(); for (JsonNode p : node) parts.add(p.isObject() ? text(p, "value", "") : p.asText()); return String.join(delimiter, parts); }
    private static JsonNode document(String text) throws Exception { return JSON.readTree(text); }
    private static String description(JsonNode node) { return node == null || node.isNull() ? "" : node.isTextual() ? node.asText() : text(node, "content", ""); }
    private static String text(JsonNode node, String key, String fallback) { JsonNode value = node == null ? null : node.get(key); return value == null || value.isNull() ? fallback : value.isValueNode() ? value.asText() : value.toString(); }
    private static boolean references(String text, String key) { if (text == null) return false; var m = TOKEN.matcher(text); while (m.find()) if (m.group(1).equals(key)) return true; return false; }
    private static boolean sensitive(String key) { return SECRET.matcher(key == null ? "" : key).matches(); }
    private static String decode(String text) { try { return URLDecoder.decode(text, StandardCharsets.UTF_8); } catch (Exception e) { return text; } }
    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String formEncode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private static String appendQuery(String url, String raw) { int hash = url.indexOf('#'); String f = hash < 0 ? "" : url.substring(hash), b = hash < 0 ? url : url.substring(0, hash); return b + (b.endsWith("?") || b.endsWith("&") ? "" : b.contains("?") ? "&" : "?") + raw + f; }
    private static void checkMethod(String method) { if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method)) throw bad("不支持的 HTTP 方法：" + method); }
    private static void parseHeader(Map<String, String> headers, String value) { int c = value.indexOf(':'); if (c <= 0) throw bad("请求头需为 名称: 值，不支持 @文件/移除默认头语法"); String v = value.substring(c + 1).stripLeading(); if (v.isEmpty()) throw bad("空请求头/移除默认头语法未转换，请在草稿中明确设置"); putHeader(headers, value.substring(0, c).trim(), v, true); }
    private static void putHeader(Map<String, String> headers, String name, String value, boolean rejectDuplicate) { putHeader(headers, name, value, rejectDuplicate, false); }
    private static void putHeader(Map<String, String> headers, String name, String value, boolean rejectDuplicate, boolean replace) {
        if (!name.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+") || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) throw bad("请求头名称或值无效");
        String old = headers.keySet().stream().filter(k -> k.equalsIgnoreCase(name)).findFirst().orElse(null);
        if (old != null) { if (replace) headers.remove(old); else if (rejectDuplicate) throw bad("重复请求头不能等价导入：" + name); else return; }
        headers.put(name, value);
    }
    private static ApiRequestOptions.Auth noAuth() { return new ApiRequestOptions.Auth("NONE", null, null, null, null, null, null); }
    private static ApiRequestOptions.Transport transport(String redirect) { return new ApiRequestOptions.Transport(redirect, "INHERIT", null, null, "OFF", null); }
    private static ApiSpec spec(String name, String method, String url, Map<String, String> headers, String body, String note, boolean insecure, ApiRequestOptions options) {
        return new ApiSpec(null, name, method, url, ApiRequestEngine.stringify(headers), body, note, "", "", 10000, insecure, true, null, null, options);
    }
    private static final class ImportLimitException extends IllegalArgumentException {
        ImportLimitException(String message) { super(message); }
    }
    private static IllegalArgumentException bad(String message) { return new IllegalArgumentException(message); }
}
