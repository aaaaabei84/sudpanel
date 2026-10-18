package com.sudpanel.api;

import java.io.IOException;
import java.net.URI;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 接口测试执行器。
 *
 * <p>设计取舍（与功能测试的 apptomg 执行引擎保持一致的地方，以及刻意不一致的地方）：
 * <ol>
 *   <li><b>直连，不走代理。</b>请求由后端进程发出，并且**显式禁用代理**。
 *       原因是这类工具打的是内网测试环境，若继承了系统的 HTTP_PROXY（本机就设着），
 *       请求会被莫名其妙转发出去，表现成「连接被拒」——排查起来很费时间。
 *       确有需要走代理时，把 {@code sudpanel.api.proxy=host:port} 写进配置即可。</li>
 *   <li><b>定时与批量在服务端跑，不在浏览器里。</b>浏览器受同源/CORS 限制，
 *       且关掉页面就断；放服务端才能「设好间隔，让它自己跑」，也才能中途停。</li>
 *   <li><b>停止是协作式的。</b>每个批次一个开关，循环每步检查一次；
 *       点了停止不会硬杀线程，当前这一条请求会跑完再退，避免留下半截记录。</li>
 *   <li><b>失败与报错分开。</b>断言没过是 FAIL（接口通了但结果不对），
 *       请求本身发不出去/超时是 ERROR（环境问题）。混在一起会看不出该找谁。</li>
 * </ol>
 */
@Service
public class ApiRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiRunner.class);

    /** 显式直连：不读系统代理设置。 */
    private static final ProxySelector DIRECT = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // 直连没有代理可连，不需要处理
        }
    };

    private final ApiRepository repo;
    private final CodeDictionary dict;
    private ApiFileStore fileStore;
    @org.springframework.beans.factory.annotation.Autowired
    public void fileStore(ApiFileStore files){this.fileStore=files;}
    private final ObjectMapper json = new ObjectMapper();
    private ApiHttpProfiles advancedProfiles;
    @org.springframework.beans.factory.annotation.Autowired
    public void setAdvancedProfiles(ApiHttpProfiles profiles){this.advancedProfiles=profiles;}
    private ApiHttpProfiles profiles(){if(advancedProfiles==null)throw new IllegalStateException("受管HTTP profile服务不可用；未使用其他凭据");return advancedProfiles;}
    private void validateAdvanced(ApiSpec spec,ApiRequestEngine.Environment env,ApiRequestEngine.Built built){
        var t=spec.options()==null?null:spec.options().transport();URI target=parseUri(built.url());
        if(t!=null&&"CHAIN".equals(t.cookieMode())&&(env.cookieScope()==null&&(t.cookieSession()==null||t.cookieSession().isBlank())))throw new IllegalArgumentException("本次请求链须填写会话UUID，或在批次/场景内执行");
        if(t!=null&&t.certificateProfileId()!=null&&!t.certificateProfileId().isBlank()){
            if(spec.insecure())throw new IllegalArgumentException("证书profile始终严格校验，不能同时选择跳过TLS校验");profiles().validateTarget(env.envId(),t.certificateProfileId(),"TLS",target);profiles().tls(env.envId(),t.certificateProfileId(),target);}
        if(java.util.Set.of("OAUTH2","DIGEST").contains(built.authType())){
            var auth=spec.options().auth();var config=profiles().auth(env.envId(),auth.profileId(),built.authType(),target);
            if("DIGEST".equals(built.authType())&&(auth.digestAlgorithm()!=null&&!auth.digestAlgorithm().equals(config.algorithm())||auth.digestQop()!=null&&!auth.digestQop().equals(config.qop())))throw new IllegalArgumentException("Digest算法/qop须与所选profile一致");
        }
    }

    /** 走代理时才用得上；留空即直连。 */
    private final String proxy;

    /** 批次 id → 停止开关。只有正在跑的批次才有条目。 */
    private final Map<Long, AtomicBoolean> stopFlags = new ConcurrentHashMap<>();

    private final java.util.concurrent.atomic.AtomicInteger executionOwners = new java.util.concurrent.atomic.AtomicInteger();
    /** 所有同步执行、异步调试及批次finally完成前都视为有写入所有者。 */
    public boolean hasExecutionOwner() { return executionOwners.get() > 0 || !stopFlags.isEmpty() || requestTasks.values().stream().anyMatch(t -> t.finishedAt == null); }
    private <T> T owned(java.util.function.Supplier<T> work) { executionOwners.incrementAndGet();try {return work.get();} finally {executionOwners.decrementAndGet();} }

    private final ThreadLocal<RequestControl> requestControl = new ThreadLocal<>();
    private final Map<String, RequestControl> activeRequests = new ConcurrentHashMap<>();
    private final Map<String, RequestTask> requestTasks = new ConcurrentHashMap<>();
    private final Map<Long, CurrentStep> currentSteps = new ConcurrentHashMap<>();
    public record CurrentStep(String requestId, Long caseId, String stepKey, String phase, int attempt) {}
    public record RequestView(String requestId, String state, ApiExec exec, boolean cancelRequested,
                              boolean serverOutcomeUnknown, String createdAt, String finishedAt, String error) {}
    private static final class RequestControl {
        final String id; final Long batchId; final boolean cleanup;
        final AtomicBoolean cancelled = new AtomicBoolean();
        volatile java.util.concurrent.CompletableFuture<?> future;
        volatile boolean sent, finished;
        RequestControl(String id, Long batchId, boolean cleanup) { this.id=id;this.batchId=batchId;this.cleanup=cleanup; }
        synchronized boolean cancel() {
            if (finished) return false;
            cancelled.set(true); if (future != null) future.cancel(true); return true;
        }
    }
    private static final class RequestTask {
        final RequestControl control; final String fingerprint; final String createdAt=Instant.now().toString();
        volatile String finishedAt,error; volatile ApiExec result; volatile boolean running;
        RequestTask(RequestControl control,String fingerprint) {this.control=control;this.fingerprint=fingerprint;}
    }
    public RequestView submitRequest(ApiSpec spec, Long envId, Map<String,Object> source, String requestedId) {
        if (spec == null) throw new IllegalArgumentException("请求不能为空");
        String id = requestedId == null ? java.util.UUID.randomUUID().toString() : requestedId;
        try { if (!java.util.UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (Exception e) { throw new IllegalArgumentException("requestId须为小写标准UUID"); }
        String fingerprint = ApiRepository.sha256(ApiRequestEngine.stringify(spec)+":"+envId+":"+ApiRequestEngine.stringify(source));
        RequestTask task;
        ApiRequestEngine.Environment environment;
        synchronized (requestTasks) {
            var previous = requestTasks.get(id);
            if (previous != null) {
                if (!previous.fingerprint.equals(fingerprint)) throw new IllegalArgumentException("requestId已被不同请求使用");
                return requestView(previous);
            }
            if (requestTasks.values().stream().filter(t -> t.finishedAt == null).count() >= 16) throw new IllegalStateException("同时调试请求最多16条，请先完成或取消已有请求");
            if (requestTasks.size() >= 500) requestTasks.values().stream().filter(t -> t.finishedAt != null).sorted(java.util.Comparator.comparing(t -> t.createdAt)).limit(100).forEach(t -> requestTasks.remove(t.control.id));
            environment = snapshotEnvironment(spec,envId);
            task = new RequestTask(new RequestControl(id,null,false),fingerprint); requestTasks.put(id,task);
        }
        Thread.ofPlatform().daemon(true).name("api-request-"+id).start(() -> {
            task.running=true; activeRequests.put(id,task.control);
            try {
                var metadata = new LinkedHashMap<String,Object>();
                if(source!=null)metadata.put("sourceContext",safeSource(source));
                task.result = executeControlled(spec,environment,null,new ApiRunVariables(),extractors(spec),metadata,null,1,1,Map.of(),task.control);
                task.result = repo.insertExec(task.result);
            } catch (Exception e) { task.error="请求结果保存失败，请先核对服务器实际状态后再决定是否重试"; }
            finally { synchronized(task.control) {task.control.finished=true;} task.finishedAt=Instant.now().toString();activeRequests.remove(id); }
        });
        return requestView(task);
    }
    public RequestView request(String id) {
        var task=requestTasks.get(id);if(task==null)throw new java.util.NoSuchElementException("请求任务不存在或已过保留上限");return requestView(task);
    }
    public RequestView cancelRequest(String id) {
        var task=requestTasks.get(id);if(task==null)throw new java.util.NoSuchElementException("请求任务不存在或已过保留上限");task.control.cancel();return requestView(task);
    }
    private static RequestView requestView(RequestTask task) {
        boolean cancelled=task.control.cancelled.get();
        String state=task.finishedAt==null ? cancelled?"CANCEL_REQUESTED":task.running?"RUNNING":"QUEUED" : task.error!=null?"FAILED":cancelled?"CANCELLED":"DONE";
        return new RequestView(task.control.id,state,task.result,cancelled,cancelled&&task.control.sent,task.createdAt,task.finishedAt,task.error);
    }
    private ApiExec executeControlled(ApiSpec spec,ApiRequestEngine.Environment environment,Map<String,String> overrides,
            Map<String,String> variables,List<ApiExtractor> extractors,Map<String,Object> metadata,Long batchId,int round,int seq,
            Map<String,String> data,RequestControl control) {
        RequestControl previous=requestControl.get();requestControl.set(control);
        try { return executeIn(spec,environment,overrides,variables,extractors,metadata,batchId,round,seq,data); }
        finally { synchronized(control) {control.finished=true;control.future=null;} if(previous==null)requestControl.remove();else requestControl.set(previous); }
    }

    public ApiRunner(ApiRepository repo, CodeDictionary dict,
                     @Value("${sudpanel.api.proxy:}") String proxy) {
        this.repo = repo;
        this.dict = dict;
        this.proxy = proxy;
        if (proxy != null && !proxy.isBlank()) {
            log.info("接口测试：已配置代理 {}", proxy);
        }
    }

    // ==================== 对外入口 ====================

    /**
     * 跑一组用例，跑完才返回（同步）。用于「执行选中」且不需要重复的场景。
     *
     * @param caseIds 用例 id，顺序即执行顺序
     * @return 每条用例的执行结果
     */
    public synchronized List<ApiExec> runOnce(List<Long> caseIds) { return owned(() -> runOnceOwned(caseIds)); }
    private List<ApiExec> runOnceOwned(List<Long> caseIds) {
        if (caseIds == null || caseIds.isEmpty()) throw new IllegalStateException("请先勾选要执行的用例");
        List<Frozen> plan = repo.readSnapshot(() -> {
            String capturedAt = Instant.now().toString();
            return caseIds.stream().map(id -> {
                ApiSpec spec = repo.getCase(id);
                return new Frozen(spec, capture(spec, null, capturedAt), null);
            }).toList();
        });
        List<ApiExec> results = new ArrayList<>();
        Map<Long, Map<String, String>> run = new LinkedHashMap<>();
        int seq = 1;
        for (Frozen item : plan) results.add(repo.insertExec(executeFrozen(item, run, null, 1, seq++, Map.of())));
        return results;
    }

    private record Frozen(ApiSpec spec, ApiRequestEngine.Environment environment, ApiScenario.Step step) {}

    private ApiRequestEngine.Environment capture(ApiSpec spec, Long explicitEnv) {
        return capture(spec, explicitEnv, Instant.now().toString());
    }

    private ApiRequestEngine.Environment capture(ApiSpec spec, Long explicitEnv, String capturedAt) {
        long id = repo.resolveEnvForSpec(spec, explicitEnv);
        return new ApiRequestEngine.Environment(id, repo.getEnv(id).name(), capturedAt,
                repo.secrets().resolveForRequest(id,repo.getVars(id),spec), resolvedSign(id,spec), repo.getEnv(id).strictVariables(), proxy);
    }

    private ApiSigner.Config resolvedSign(long id,ApiSpec spec) {
        var c=repo.getSignConfig(id);String type=spec.options()==null||spec.options().auth()==null?"INHERIT":spec.options().auth().type();
        boolean used="SUD".equals(type)||(type==null||"INHERIT".equals(type))&&c.enabled();
        return new ApiSigner.Config(c.enabled(),c.appId(),used?repo.secrets().resolve(id,c.appSecret()):c.appSecret(),c.nonce(),c.mgId());
    }

    /** 冻结一次完整环境读取；不发送、不落执行历史。 */
    public ApiRequestEngine.Environment snapshotEnvironment(ApiSpec spec, Long envId) {
        if (spec == null) throw new IllegalArgumentException("请求不能为空");
        return repo.readSnapshot(() -> capture(spec, envId));
    }

    /** 性能等调用方传入自己的冻结环境；本方法与旧execute一样不保存手工历史。 */
    public ApiExec executeWithEnvironment(ApiSpec spec, ApiRequestEngine.Environment environment,
            Map<String, String> bodyOverrides, Long batchId, int roundNo, int seq) {
        if (spec == null || environment == null) throw new IllegalArgumentException("请求和冻结环境不能为空");
        return owned(() -> executeIn(spec, environment, bodyOverrides, new ApiRunVariables(), extractors(spec), Map.of(), batchId, roundNo, seq));
    }

    public ApiRequestEngine.Prepared prepare(ApiSpec spec, Long envId) {
        if (spec == null) throw new IllegalArgumentException("请求不能为空");
        var environment = repo.readSnapshot(() -> capture(spec, envId));
        return prepareCaptured(spec,environment);
    }
    public ApiRequestEngine.Prepared prepareForUi(ApiSpec spec,Long envId,boolean includeSecrets){
        var environment=snapshotEnvironment(spec,envId);var secrets=ApiPrivacy.known(environment,spec,Map.of());
        var prepared=prepareCaptured(spec,environment,secrets);
        return includeSecrets?prepared:ApiPrivacy.prepared(prepared,secrets);
    }
    private ApiRequestEngine.Prepared prepareCaptured(ApiSpec spec,ApiRequestEngine.Environment environment) {return prepareCaptured(spec,environment,new java.util.LinkedHashSet<>());}
    private ApiRequestEngine.Prepared prepareCaptured(ApiSpec spec,ApiRequestEngine.Environment environment,java.util.Set<String> secrets) {
        ApiRequestEngine.Built built = null;
        try {
            ApiBodyAssert.validateRules(spec.asserts());
            built = ApiRequestEngine.build(spec, environment, null, Map.of(), Map.of(),fileStore);
            if(built.signatureContext()!=null)secrets.addAll(built.signatureContext().secretOutputs());
            ApiRequestEngine.assertionRules(spec.asserts(), ApiSignatureScripts.effectiveEnvironment(built,environment), Map.of(), Map.of());
            validateAdvanced(spec,environment,built);
            buildHttpRequest(spec, built, environment); // 与 send 相同校验，刻意不发送。
            ApiTransport.key(spec, environment.envId(), environment.inheritedProxy() == null ? proxy : environment.inheritedProxy(), false);
            return new ApiRequestEngine.Prepared(true, built.method(), built.url(),
                    ApiRequestEngine.maskedHeaders(built.headers()), built.body(), environment.envId(), environment.envName(),
                    environment.capturedAt(), built.warnings(), built.unresolved(), built.variables(), null, ApiVariableDiagnostics.inspect(ApiSignatureScripts.effectiveSpec(built,spec),ApiSignatureScripts.effectiveEnvironment(built,environment),Map.of(),Map.of(),null),built.bodyMetadata(),built.headerRows().stream().map(h->new ApiRequestOptions.HeaderRow(h.name(),ApiRequestEngine.maskedHeaders(Map.of(h.name(),h.value())).get(h.name()),true)).toList());
        } catch (Exception e) {
            return new ApiRequestEngine.Prepared(false, built == null ? spec.method() : built.method(),
                    built == null ? spec.url() : built.url(), built == null ? Map.of() : ApiRequestEngine.maskedHeaders(built.headers()),
                    built == null ? spec.body() : built.body(), environment.envId(), environment.envName(), environment.capturedAt(),
                    built == null ? List.of() : built.warnings(), built == null ? List.of() : built.unresolved(),
                    built == null ? List.of() : built.variables(), e.getMessage(), ApiVariableDiagnostics.inspect(spec,environment,Map.of(),Map.of(),null));
        } finally {if(built!=null)built.close();}
    }

    public ApiActualCurl.Export actualCurl(ApiSpec spec,Long envId,boolean includeSecrets) {
        var environment=snapshotEnvironment(spec,envId);
        ApiBodyAssert.validateRules(spec.asserts());
        try(var built=ApiRequestEngine.build(spec,environment,null,Map.of(),Map.of(),fileStore)){
            validateAdvanced(spec,environment,built);buildHttpRequest(spec,built,environment);ApiTransport.key(spec,environment.envId(),environment.inheritedProxy()==null?proxy:environment.inheritedProxy(),false);
            ApiRequestEngine.assertionRules(spec.asserts(),ApiSignatureScripts.effectiveEnvironment(built,environment),Map.of(),Map.of());
            return ApiActualCurl.prepared(built,spec.insecure(),includeSecrets);
        }
    }

    private static HttpRequest buildHttpRequest(ApiSpec spec, ApiRequestEngine.Built built, ApiRequestEngine.Environment environment) {
        if (built.unresolved().stream().anyMatch(name -> name.startsWith("$") || name.startsWith("run.") || name.startsWith("data.")))
            throw new IllegalArgumentException("系统或运行变量未解析：" + String.join(", ", built.unresolved()));
        if (Boolean.TRUE.equals(spec.options() != null && spec.options().strictVariables() != null ? spec.options().strictVariables() : environment.strictVariables()) && !built.unresolved().isEmpty())
            throw new IllegalArgumentException("严格模式：仍有未解析变量：" + String.join(", ", built.unresolved()));
        URI uri = parseUri(built.url());
        String body = built.body();
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(spec.timeoutOrDefault()));
        request.method(built.method(), built.wireBody()!=null?HttpRequest.BodyPublishers.ofByteArray(built.wireBody())
                :body == null || body.isEmpty() ? HttpRequest.BodyPublishers.noBody()
                :HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        built.headerRows().forEach(row->request.header(row.name(),row.value()));
        return request.build();
    }

    private static boolean sameOrigin(URI left, URI right) {
        if (left.getHost() == null || right.getHost() == null) return false;
        int lp = left.getPort() >= 0 ? left.getPort() : "https".equalsIgnoreCase(left.getScheme()) ? 443 : 80;
        int rp = right.getPort() >= 0 ? right.getPort() : "https".equalsIgnoreCase(right.getScheme()) ? 443 : 80;
        return left.getScheme().equalsIgnoreCase(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost()) && lp == rp;
    }
    private static ApiRequestEngine.Built withAuthorization(ApiRequestEngine.Built built,String authorization){
        var headers=new LinkedHashMap<>(built.headers());headers.keySet().removeIf(k->k.equalsIgnoreCase("Authorization"));headers.put("Authorization",authorization);
        var rows=new ArrayList<>(built.headerRows().stream().filter(h->!h.name().equalsIgnoreCase("Authorization")).toList());rows.add(new ApiRequestOptions.HeaderRow("Authorization",authorization,true));
        return new ApiRequestEngine.Built(built.method(),built.url(),headers,built.body(),built.authType(),built.bodyType(),built.warnings(),built.unresolved(),built.variables(),built.wireBody(),built.bodyMetadata(),built.filePayload(),List.copyOf(rows),built.signatureContext());
    }
    private static HttpRequest copyHttpRequest(HttpRequest old,URI target,String method,boolean dropBody,boolean crossOrigin,String authorization,long deadline){
        var next=HttpRequest.newBuilder(target).timeout(Duration.ofNanos(Math.max(1,deadline-System.nanoTime())));
        old.headers().map().forEach((name,values)->{
            if(crossOrigin&&java.util.Set.of("authorization","proxy-authorization","cookie").contains(name.toLowerCase(java.util.Locale.ROOT)))return;
            if(authorization!=null&&name.equalsIgnoreCase("Authorization"))return;
            if(dropBody&&name.equalsIgnoreCase("Content-Type"))return;values.forEach(value->next.header(name,value));});
        if(authorization!=null)next.header("Authorization",authorization);
        next.method(method,dropBody?HttpRequest.BodyPublishers.noBody():old.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));return next.build();
    }

    private HttpResponse<byte[]> awaitResponse(HttpClient client, HttpRequest request, int limit, long deadline) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new java.net.http.HttpTimeoutException("请求总时限已到");
        RequestControl control=requestControl.get();
        if(control!=null&&control.cancelled.get())throw new java.util.concurrent.CancellationException("用户取消请求");
        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> future;
        if(control!=null){synchronized(control){if(control.cancelled.get())throw new java.util.concurrent.CancellationException("用户取消请求");control.sent=true;future=client.sendAsync(request,ApiTransport.bounded(limit,"HEAD".equals(request.method())));control.future=future;}}
        else future=client.sendAsync(request,ApiTransport.bounded(limit,"HEAD".equals(request.method())));
        try { return future.get(remaining, java.util.concurrent.TimeUnit.NANOSECONDS); }
        catch (java.util.concurrent.TimeoutException timeout) {
            future.cancel(true); throw new java.net.http.HttpTimeoutException("响应正文读取超时");
        } catch (InterruptedException interrupted) {
            future.cancel(true); Thread.currentThread().interrupt(); throw interrupted;
        } catch (java.util.concurrent.ExecutionException failed) {
            if (failed.getCause() instanceof Exception cause) throw cause;
            throw new IOException("读取响应失败", failed.getCause());
        }
    }

    public ApiExec send(ApiSpec spec, Long envId, Map<String, Object> sourceContext) { return owned(() -> sendOwned(spec,envId,sourceContext)); }
    private ApiExec sendOwned(ApiSpec spec, Long envId, Map<String, Object> sourceContext) {
        if (spec == null) throw new IllegalArgumentException("请求不能为空");
        var environment = repo.readSnapshot(() -> capture(spec, envId));
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (sourceContext != null) {
            var safe = new LinkedHashMap<String, Object>();
            for (String key : List.of("runId", "loopId", "loopNo", "gameId", "gameName", "roundId", "envId", "envSource")) {
                Object value = sourceContext.get(key);
                if (value instanceof String || value instanceof Number || value instanceof Boolean) safe.put(key, value);
            }
            metadata.put("sourceContext", safe);
        }
        return repo.insertExec(executeIn(spec, environment, null, new ApiRunVariables(), extractors(spec), metadata, null, 1, 1));
    }

    private static Map<String,Object> safeSource(Map<String,Object> source) {
        var safe=new LinkedHashMap<String,Object>();
        for(String key:List.of("runId","loopId","loopNo","gameId","gameName","roundId","envId","envSource")){Object value=source.get(key);if(value instanceof String||value instanceof Number||value instanceof Boolean)safe.put(key,value);}
        return safe;
    }

    private static List<ApiExtractor> extractors(ApiSpec spec) {
        return spec.options() == null || spec.options().extractors() == null ? List.of() : spec.options().extractors();
    }

    private ApiExec executeFrozen(Frozen item, Map<Long, Map<String, String>> run, Long batchId,
            int round, int seq, Map<String, Object> metadata) {
        return executeFrozen(item, run, batchId, round, seq, metadata, Map.of());
    }
    private ApiExec executeFrozen(Frozen item, Map<Long, Map<String, String>> run, Long batchId,
            int round, int seq, Map<String, Object> metadata, Map<String, String> data) {
        List<ApiExtractor> extractors = item.step() == null || item.step().extractors() == null
                ? extractors(item.spec()) : item.step().extractors();
        return executeIn(item.spec(), item.environment(), null,
                run.computeIfAbsent(item.environment().envId(), id -> new ApiRunVariables()),
                extractors, metadata, batchId, round, seq, data);
    }

    /** 数据行与重复轮次各自拥有新的运行变量；场景清理逐行执行。 */
    public ApiBatch startDataBatch(String name, List<Long> ids, Long scenarioId, ApiDataSet.Input input, int interval, int repeats) {
        if(repeats<1||repeats>1000||interval<0||interval>3600)throw new IllegalArgumentException("数据批次重复次数须为1～1000，行间隔为0～3600秒");
        var data=ApiDataSet.parse(input);var rows=new ArrayList<ApiExecutionPlan.Row>();
        for(int i=0;i<data.rows().size();i++)rows.add(new ApiExecutionPlan.Row(i+1,0,data.rows().get(i),List.of()));
        var plan=repo.readSnapshot(() -> {
            if(scenarioId!=null)return scenarioPlan(repo.getScenario(scenarioId),name,interval,repeats,rows,"DATA");
            var base=casePlan(name,ids,interval,repeats);
            return new ApiExecutionPlan(1,"DATA",base.name(),base.capturedAt(),base.steps(),rows,repeats,interval,null,null,null);
        });
        return startFrozenPlan(plan);
    }



    /**
     * 启动一个异步批次：按间隔重复跑，跑完写库。
     *
     * @param name            批次名
     * @param caseIds         用例 id 列表
     * @param intervalSeconds 每轮之间的间隔秒数（0 表示不等待）
     * @param repeatCount     重复轮数；{@link ApiBatch#REPEAT_FOREVER} 表示一直跑到手动停止
     * @return 已创建的批次（status=RUNNING）
     */
    public ApiBatch startBatch(String name, List<Long> caseIds, int intervalSeconds, int repeatCount) {
        if (caseIds == null || caseIds.isEmpty()) throw new IllegalArgumentException("请先勾选要执行的用例");
        requireRepeats(repeatCount);
        var plan=repo.readSnapshot(() -> casePlan(name,caseIds,Math.max(0,intervalSeconds),repeatCount));
        return startFrozenPlan(plan);
    }

    /** 请求停止某个批次。当前正在跑的那一条会跑完。 */
    public void stop(long batchId) {
        AtomicBoolean flag = stopFlags.get(batchId);
        if (flag == null) {
            throw new IllegalStateException("该批次当前没有在执行（可能已结束）");
        }
        flag.set(true);
        log.info("接口测试：批次 #{} 收到停止请求，当前这条跑完就退", batchId);
    }

    /** 是否有批次正在跑（用于界面上限制重复启动）。 */
    public boolean isRunning(long batchId) {
        return stopFlags.containsKey(batchId);
    }

    /** 正在跑的所有批次 id。 */
    public List<Long> runningBatches() {
        return new ArrayList<>(stopFlags.keySet());
    }

    // ==================== 批次循环 ====================



    /**
     * 批次里执行一条并落库：**全流程只有这一处 insert**。
     *
     * <p>这里踩过一个坑：早先 {@link #execute} 内部自己落库，循环里又落了一次，
     * 于是每条结果在明细里出现两次 —— 3 轮 1 条用例会写出 6 行，而实际只发了 3 次请求
     * （通过数 bump 只做了一次，所以计数是对的、明细是双的，看上去像「记录莫名翻倍」）。
     * 现在把「执行」和「落库」拆开：{@code execute} 只负责发请求与判定，
     * 落库统一在本方法里做，insert 与计数各一次，一一对应。
     *
     * <p>异常也不能让整个批次挂掉：单条出错记成一条 ERROR 记录，循环继续下一条。
     */
    private void storeResult(long batchId, Frozen item, Map<Long, Map<String, String>> variables, int round, int seq) {
        ApiSpec spec = item.spec();
        ApiExec result;
        try {
            result = executeFrozen(item, variables, batchId, round, seq, Map.of());
        } catch (Throwable t) {
            // execute 内部已兜住 Exception，这里只兜「兜不住的那些」，最后一道防线
            result = new ApiExec(null, batchId, spec.id(), spec.displayName(), round, seq,
                    spec.method(), spec.url(), null, null, null, ApiExec.ERROR,
                    "该条执行时抛出未预期的异常：" + t.getMessage(), 0L, null, null, null, null,
                    String.valueOf(t), Instant.now().toString(), null);
        }
        try {
            repo.storeBatchExec(result);
        } catch (Exception e) {
            // 无法保存证据时必须终止并标 FAILED，不能把零记录批次标成跑完。
            log.warn("接口测试：批次 #{} 第 {} 轮第 {} 条落库失败：{}", batchId, round, seq, e.getMessage());
            throw new IllegalStateException("请求已执行，但结果保存失败，批次已中止", e);
        }
    }

    /** 睡 interval 秒，被要求停止时提前返回 false。分段睡是为了停止能及时生效。 */
    private boolean sleepInterruptibly(int intervalSeconds, AtomicBoolean stop) {
        long remainMs = intervalSeconds * 1000L;
        while (remainMs > 0) {
            if (stop.get()) {
                return false;
            }
            long step = Math.min(200, remainMs);
            try {
                Thread.sleep(step);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            remainMs -= step;
        }
        return !stop.get();
    }

    // ==================== 单条执行 ====================

    /**
     * 执行一条用例：发请求 → 取状态码与返回码 → 判定。**不落库**。
     *
     * <p>落库由调用方负责（{@link #runOnce} 与 {@link #storeResult}），
     * 这样「发一次请求」与「写一条记录」严格一一对应，不会再出现一条结果写两遍。
     *
     * @param spec    用例
     * @param batchId 批次 id；单条试跑传 null
     * @param roundNo 第几轮
     * @param seq     本轮第几条
     * @return 执行结果（尚未入库，id 为 null）
     */
    public ApiExec execute(ApiSpec spec, Long batchId, int roundNo, int seq) {
        return execute(spec, null, batchId, roundNo, seq);
    }

    /**
     * 执行一次请求，并允许覆盖**请求体那一组**变量（2026-10-06，需求 #68）。
     *
     * <p>只为性能测试的「人机对局」模式而加：那一模式要按**当前游戏**的 mg_id / app_id 去推送，
     * 而变量表里只有一份全局值。覆盖走 {@link ApiVariables#overrideBody}，不改全局变量本身 ——
     * 见那里的注释（被压测顺手改掉全局值，下一次手工调接口就会用错身份）。
     *
     * <p><b>本方法不落库。</b>落库在 {@code storeResult} / {@code runOnce} 里，这里只构造并返回结果，
     * 所以人机对局每圈推的四次指令不会进「接口测试的执行记录」—— 那些记录是给人看手工调试的，
     * 被压测每圈塞四条进去，真正想找的那一条就翻不到了。
     *
     * @param varOverride 要覆盖的「变量名 → 值」；null 或空表示用全局值（与不传一样）
     */
    public ApiExec execute(ApiSpec spec, Map<String, String> varOverride, Long batchId, int roundNo, int seq) {
        var environment = repo.readSnapshot(() -> capture(spec, null));
        return executeIn(spec, environment, varOverride, new ApiRunVariables(), extractors(spec),
                Map.of(), batchId, roundNo, seq);
    }

    private ApiExec executeIn(ApiSpec spec, ApiRequestEngine.Environment environment,
            Map<String, String> overrides, Map<String, String> runVariables, List<ApiExtractor> extractors,
            Map<String, Object> extraMetadata, Long batchId, int roundNo, int seq) {
        return executeIn(spec, environment, overrides, runVariables, extractors, extraMetadata, batchId, roundNo, seq, Map.of());
    }

    private ApiExec executeIn(ApiSpec spec, ApiRequestEngine.Environment environment,
            Map<String, String> overrides, Map<String, String> runVariables, List<ApiExtractor> extractors,
            Map<String, Object> extraMetadata, Long batchId, int roundNo, int seq, Map<String, String> data) {
        var secrets=ApiPrivacy.known(environment,spec,runVariables);
        if(overrides!=null)for(var v:ApiVariables.overrideBody(environment.vars(),overrides).body())if(Boolean.TRUE.equals(v.secret())&&v.value()!=null&&!v.value().isEmpty())secrets.add(v.value());
        var execution=executeInRaw(spec,environment,overrides,runVariables,extractors,extraMetadata,batchId,roundNo,seq,data,secrets);
        secrets.addAll(ApiPrivacy.known(environment,spec,runVariables));
        return ApiPrivacy.attach(execution,secrets,repo.jdbc());
    }

    private ApiExec executeInRaw(ApiSpec spec, ApiRequestEngine.Environment environment,
            Map<String,String> overrides,Map<String,String> runVariables,List<ApiExtractor> extractors,
            Map<String,Object> extraMetadata,Long batchId,int roundNo,int seq,Map<String,String> data,java.util.Set<String> secrets) {
        long t0 = System.nanoTime();
        String ts = Instant.now().toString();
        var metadata = new LinkedHashMap<String, Object>(extraMetadata);
        RequestControl control=requestControl.get();
        if(control!=null)metadata.put("requestId",control.id);
        metadata.put("schema", 1);
        metadata.put("envId", environment.envId()); metadata.put("envName", environment.envName());
        metadata.put("capturedAt", environment.capturedAt());
        metadata.put("assertsTemplate", spec.asserts());
        ApiRequestEngine.Built built = null;
        String reqHeaders = null;
        Integer receivedStatus=null;Map<String,List<String>> receivedHeaders=Map.of();
        try {
            if(control!=null&&control.cancelled.get())throw new java.util.concurrent.CancellationException("用户取消请求");
            // 本步可以先消费旧值（例如刷新 token），组装完成后立即清除待提取目标。
            built = ApiRequestEngine.build(spec, environment, overrides, runVariables, data,fileStore);
            if(built.signatureContext()!=null){secrets.addAll(built.signatureContext().secretOutputs());metadata.put("signatureScript",built.signatureContext().summary());}
            var assertionRules = ApiRequestEngine.assertionRules(spec.asserts(), ApiSignatureScripts.effectiveEnvironment(built,environment), runVariables, data, built.signatureContext()==null?overrides:null);
            if (extractors != null) extractors.forEach(rule -> runVariables.remove(rule.name()));
            metadata.put("authType", built.authType()); metadata.put("bodyType", built.bodyType());
            var executionWarnings = new ArrayList<>(built.warnings());
            metadata.put("warnings", executionWarnings); metadata.put("unresolved", built.unresolved());
            metadata.put("variables", built.variables());
            validateAdvanced(spec,environment,built);
            long deadline = t0 + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(spec.timeoutOrDefault());
            if("OAUTH2".equals(built.authType()))built=withAuthorization(built,"Bearer "+profiles().oauth(environment.envId(),spec.options().auth().profileId(),parseUri(built.url()),deadline,future->{if(control!=null){synchronized(control){if(control.cancelled.get())throw new java.util.concurrent.CancellationException("用户取消请求");control.future=future;}}},()->control!=null&&control.cancelled.get()));
            HttpRequest request = buildHttpRequest(spec, built, environment);
            String body = built.body();
            reqHeaders = renderRequestHeaders(built.method(), built.url(), built.headerRows(), body);
            if(built.wireBody()!=null){metadata.put("requestBody",built.bodyMetadata());metadata.put("requestBodyBytes",built.wireBody().length);metadata.put("requestBodyMode",built.bodyType());metadata.put("requestBodyTruncated",false);reqHeaders=reqHeaders.replace("（无请求体）","（文件正文，"+built.wireBody().length+" 字节；仅保存元数据，原字节未转为文本，见文件SHA256）");}
            metadata.put("requestBodyChars", body == null ? 0 : body.length());
            metadata.put("requestBodyTruncated", body != null && body.length() > ApiExec.BODY_LIMIT);
            var transportKey = ApiTransport.key(spec, environment.envId(), environment.inheritedProxy() == null ? proxy : environment.inheritedProxy(),
                    built.headers().keySet().stream().anyMatch(h -> h.equalsIgnoreCase("Cookie"))).withCookieScope(environment.cookieScope());
            int responseLimit = ApiTransport.limit(spec);
            ApiTransport.Key effectiveKey = transportKey.neverRedirect();
            var manualRedirects = new ArrayList<Map<String, Object>>();
            metadata.put("redirects",manualRedirects);
            metadata.put("requestedHttpVersion",transportKey.httpVersion());
            metadata.put("cookieMode",spec.options()!=null&&spec.options().transport()!=null?String.valueOf(spec.options().transport().cookieMode()):"OFF");
            int maxRedirects=spec.options()!=null&&spec.options().transport()!=null&&spec.options().transport().maxRedirects()!=null?spec.options().transport().maxRedirects():5;
            boolean digestAttempted=false;String digestChallenge=null;ApiHttpProfiles.Input digestCredentials=null;
            HttpResponse<byte[]> response;
            while (true) {
                response = awaitResponse(client(effectiveKey,request.uri()), request, responseLimit, deadline);
                metadata.put("actualHttpVersion",response.version().name());
                if(response.statusCode()==401&&"DIGEST".equals(built.authType())&&!digestAttempted){
                    digestAttempted=true;var credential=profiles().auth(environment.envId(),spec.options().auth().profileId(),"DIGEST",request.uri());
                    String challenge=response.headers().allValues("WWW-Authenticate").stream().filter(v->v.regionMatches(true,0,"Digest ",0,7)).findFirst().orElse(null);
                    digestChallenge=challenge;digestCredentials=credential;
                    String authorization=ApiDigestAuth.authorization(challenge,request.method(),request.uri(),credential.username(),credential.password(),credential.algorithm(),credential.qop());
                    request=copyHttpRequest(request,request.uri(),request.method(),false,false,authorization,deadline);metadata.put("digestAttempts",1);
                    built=withAuthorization(built,authorization);reqHeaders=renderRequestHeaders(built.method(),built.url(),built.headerRows(),body);if(built.wireBody()!=null)reqHeaders=reqHeaders.replace("（无请求体）","（文件正文，"+built.wireBody().length+" 字节；见文件SHA256）");continue;
                }
                if(response.statusCode()==401&&"OAUTH2".equals(built.authType())){profiles().invalidateToken(environment.envId(),spec.options().auth().profileId());executionWarnings.add("OAuth授权失败，已清除本机令牌缓存；未自动重发业务请求");}
                if(response.statusCode()==401&&java.util.Set.of("OAUTH2","DIGEST").contains(built.authType()))metadata.put("authFailure",true);
                String location = response.headers().firstValue("Location").orElse(null);
                int status = response.statusCode();
                if("NEVER".equals(transportKey.redirect())||location==null||!java.util.Set.of(301,302,303,307,308).contains(status))break;
                URI target=request.uri().resolve(location);
                if(target.getHost()==null||!java.util.Set.of("http","https").contains(target.getScheme())||target.getUserInfo()!=null)throw new IOException("重定向目标须有效HTTP(S)，不能含凭据");
                String nextMethod=status==303&&!"HEAD".equals(request.method())||(status==301||status==302)&&"POST".equals(request.method())?"GET":request.method();
                boolean changed=!nextMethod.equals(request.method()),cross=!sameOrigin(request.uri(),target);
                if("NORMAL".equals(transportKey.redirect())&&"https".equalsIgnoreCase(request.uri().getScheme())&&"http".equalsIgnoreCase(target.getScheme())){executionWarnings.add("NORMAL不跟随HTTPS降级HTTP；可显式选ALWAYS");metadata.put("blockedRedirect",true);break;}
                if(java.util.Set.of("SCRIPT","TEMPLATE").contains(built.authType())&&(changed||!request.uri().equals(target))){executionWarnings.add("跳转改变地址或请求方法，已停止，避免复用不匹配的脚本签名或Authorization模板；请创建目标请求核对");metadata.put("blockedRedirect",true);break;}
                if(cross&&(java.util.Set.of("API_KEY","SUD","OAUTH2","DIGEST","SCRIPT","TEMPLATE").contains(built.authType())||transportKey.certificateProfileId()!=null||(!changed&&!java.util.Set.of("GET","HEAD").contains(request.method())))){
                    executionWarnings.add("跨来源跳转已阻断，避免受管鉴权、客户端证书或写请求正文泄漏；请创建目标请求核对");metadata.put("blockedRedirect",true);break;}
                if(manualRedirects.size()>=maxRedirects)throw new IOException("重定向超过配置的最大跳数 "+maxRedirects+"，已停止；不会重新发送初始请求");
                var hop=new LinkedHashMap<String,Object>();hop.put("url",request.uri().toString());hop.put("status",status);hop.put("location",location);hop.put("method",request.method());hop.put("nextMethod",nextMethod);hop.put("crossOrigin",cross);manualRedirects.add(hop);
                String signAuthorization=null;
                if(changed&&"SUD".equals(built.authType())){String old=request.headers().firstValue("Authorization").orElse("");var t=java.util.regex.Pattern.compile("timestamp=\"([0-9]+)\"").matcher(old);var n=java.util.regex.Pattern.compile("nonce=\"([^\"]+)\"").matcher(old);if(t.find()&&n.find())signAuthorization=ApiSigner.authorizationValue(environment.sign().appId(),Long.parseLong(t.group(1)),n.group(1),ApiSigner.sign(environment.sign().appSecret(),environment.sign().appId(),Long.parseLong(t.group(1)),n.group(1),null));}
                if(!cross&&"DIGEST".equals(built.authType())&&digestChallenge!=null)signAuthorization=ApiDigestAuth.authorization(digestChallenge,nextMethod,target,digestCredentials.username(),digestCredentials.password(),digestCredentials.algorithm(),digestCredentials.qop());
                request=copyHttpRequest(request,target,nextMethod,changed,cross,signAuthorization,deadline);
                if(cross)effectiveKey=effectiveKey.withoutCookies();
            }
            if(control!=null&&control.cancelled.get())throw new java.util.concurrent.CancellationException("用户取消请求");
            receivedStatus=response.statusCode();receivedHeaders=response.headers().map();
            var decoded = ApiResponseBody.decode(response.body(), response.headers().map(), responseLimit,response.statusCode());
            long elapsed = (System.nanoTime() - t0) / 1_000_000;
            String raw = decoded.text();
            metadata.put("wireBytes", decoded.wireBytes()); metadata.put("decodedBytes", decoded.bytes().length);
            metadata.put("charset", decoded.charset()); metadata.put("contentEncoding", decoded.encoding());
            metadata.put("responseLimitBytes", responseLimit); metadata.put("finalUrl", response.uri().toString());
            var redirects = new ArrayList<Map<String, Object>>(manualRedirects);
            HttpResponse<?> previous = response;
            while (previous.previousResponse().isPresent()) {
                previous = previous.previousResponse().get();
                redirects.addFirst(Map.of("url", previous.uri().toString(), "status", previous.statusCode(),
                        "location", previous.headers().firstValue("Location").orElse("")));
            }
            metadata.put("redirects", redirects);
            metadata.put("responseBodyChars", raw == null ? 0 : raw.length());
            metadata.put("responseBodyTruncated", raw != null && raw.length() > ApiExec.BODY_LIMIT);
            int status = response.statusCode();
                var responseMeta=new LinkedHashMap<String,Object>();responseMeta.put("classification",decoded.classification());responseMeta.put("bytes",decoded.bytes().length);responseMeta.put("sha256",decoded.sha256());responseMeta.put("contentType",decoded.contentType());responseMeta.put("complete",true);
            if(decoded.text()==null||decoded.text().length()>ApiExec.BODY_LIMIT){
                if(fileStore==null&&decoded.text()==null)throw new IllegalStateException("文件存储未初始化，不能保留二进制响应；请重启平台后重试");
                if(fileStore==null){responseMeta.put("storageUnavailable",true);executionWarnings.add("此调用方未接入文件存储；沿用旧文本断言行为，长响应全文未归档");}
                String type=decoded.contentType().isBlank()?"application/octet-stream":decoded.contentType();
                // 非法/非标准服务器MIME只保留原始元数据，文件下载使用安全默认类型。
                try{type=ApiFileStore.mime(type);}catch(IllegalArgumentException badMime){type="application/octet-stream";}
                if(fileStore!=null){var f=fileStore.save("RESPONSE",decoded.text()==null?"response.bin":"response.txt",type,decoded.bytes());responseMeta.put("file",f);}
            }
            metadata.put("responseBody",responseMeta);
            boolean structuredLimit=ApiStructuredBody.overLimit(raw);
            if(raw!=null)metadata.put("structuredBody",Map.of("maxChars",ApiStructuredBody.MAX_CHARS,"bodyChars",raw.length(),"unit","UTF16","overLimit",structuredLimit));
            if(structuredLimit)executionWarnings.add(ApiStructuredBody.MESSAGE);

            CodeDictionary.Found found = CodeDictionary.extract(raw);
            String code = found == null ? null : found.raw();
            String meaning = dict.describe(code);
            List<ApiBodyAssert.Judged> judged = ApiBodyAssert.judgeAll(raw, assertionRules,
                    response.headers().map(), elapsed,responseMeta,decoded.text()==null);
            List<ApiBodyAssert.Failure> failures = judged.stream().filter(j -> !j.ok()).map(ApiBodyAssert.Judged::failure).toList();
            List<ApiResponseTools.Extraction> extracted = ApiResponseTools.extract(raw, response.headers().map(), extractors, runVariables);
            metadata.put("extractions", extracted);
            String reason = null;
            if(Boolean.TRUE.equals(metadata.get("authFailure"))) reason = "受管鉴权被服务器拒绝（HTTP 401），已停止；未无限重试";
            else if (!CodeDictionary.matches(spec.expectStatus(), String.valueOf(status)))
                reason = "HTTP 状态码不符：期望 " + spec.expectStatus() + "，实际 " + status;
            else if (!spec.expectCodeIsBlank() && structuredLimit) reason = ApiStructuredBody.MESSAGE+"；未核验期望业务返回码";
            else if (!spec.expectCodeIsBlank() && code == null) reason = "期望业务返回码 " + spec.expectCode() + "，但响应体里没识别到返回码字段";
            else if (!CodeDictionary.matches(spec.expectCode(), code))
                reason = "业务返回码不符：期望 " + spec.expectCode() + "，实际 " + code + (meaning == null ? "" : "（" + meaning + "）");
            else if (!failures.isEmpty()) reason = renderBodyFails(failures);
            else if (structuredLimit && extracted.stream().anyMatch(e -> "JSON".equals(e.source()))) reason = ApiStructuredBody.MESSAGE+"；JSON提取未执行";
            else if (extracted.stream().anyMatch(e -> e.required() && !e.ok()))
                reason = "必填响应提取失败：" + extracted.stream().filter(e -> e.required() && !e.ok())
                        .map(e -> e.name() + "（" + e.error() + "）").collect(java.util.stream.Collectors.joining("；"));
            if(control!=null){synchronized(control){if(control.cancelled.get())throw new java.util.concurrent.CancellationException("用户取消请求");control.finished=true;}}
            return new ApiExec(null, batchId, spec.id(), spec.displayName(), roundNo, seq, built.method(), built.url(),
                    status, code, meaning, reason == null ? ApiExec.PASS : ApiExec.FAIL, reason, elapsed,
                    ApiExec.truncate(raw), renderHeaders(response), reqHeaders, ApiExec.truncate(body), null, ts,
                    ApiBodyAssert.toJson(judged), ApiRequestEngine.stringify(metadata));
        } catch (Exception e) {
            if(control!=null&&control.cancelled.get()){
                metadata.put("cancelled",true);metadata.put("outcome","CANCELLED");metadata.put("serverOutcomeUnknown",control.sent);
                e=new java.util.concurrent.CancellationException(control.sent?"已取消等待；请求可能已到达服务器，取消不能证明业务未执行":"请求已取消，尚未开始网络发送");
            }
            if (extractors != null) extractors.forEach(rule -> runVariables.remove(rule.name()));
            metadata.putIfAbsent("extractions", List.of());
            var tooLarge=ApiResponseBody.TooLarge.find(e);
            if(tooLarge!=null){receivedStatus=tooLarge.status;receivedHeaders=tooLarge.headers;var limited=new LinkedHashMap<String,Object>(tooLarge.metadata());metadata.put("responseLimit",limited);metadata.put("responseBodyTruncated",true);
                if(fileStore!=null&&tooLarge.retained.length>0)try{limited.put("file",fileStore.save("PARTIAL","incomplete-response.bin","application/octet-stream",tooLarge.retained));}catch(Exception storage){limited.put("storageError",storage.getMessage());}
            }
            return new ApiExec(null, batchId, spec.id(), spec.displayName(), roundNo, seq,
                    built == null ? spec.method() : built.method(), built == null ? spec.url() : built.url(),
                    receivedStatus, null, null, ApiExec.ERROR, explain(e), (System.nanoTime() - t0) / 1_000_000,
                    null, receivedHeaders.isEmpty()?null:ApiRequestEngine.stringify(receivedHeaders), reqHeaders, built == null ? null : ApiExec.truncate(built.body()),
                    e.getClass().getSimpleName() + ": " + e.getMessage(), ts, null, ApiRequestEngine.stringify(metadata));
        } finally {if(built!=null)built.close();}
    }

    public ApiBatch startScenario(long scenarioId, String name, int interval, int repeats) {
        requireRepeats(repeats);
        var plan=repo.readSnapshot(() -> scenarioPlan(repo.getScenario(scenarioId),name,Math.max(0,interval),repeats,List.of(ApiExecutionPlan.emptyRow()),"SCENARIO"));
        return startFrozenPlan(plan);
    }





    /**
     * 把响应体断言的失败原因拼成一条可读的说明。
     *
     * <p>单条时写成一整句；多条时带条数并逐行列出 —— 界面上那一块是等宽换行显示的，
     * 逐行排开比挤成一段更容易一眼扫出是哪几个字段不对。
     */
    private static String renderBodyFails(List<ApiBodyAssert.Failure> fails) {
        if (fails.size() == 1) {
            return "响应体断言不通过：" + fails.get(0).render();
        }
        StringBuilder sb = new StringBuilder("响应体断言不通过（")
                .append(fails.size()).append(" 条）：");
        for (ApiBodyAssert.Failure f : fails) {
            sb.append("\n· ").append(f.render());
        }
        return sb.toString();
    }

    /** 把异常转成人能看懂的一句话 —— 「Connection refused」这种原文对排查帮助不大。 */
    private static String explain(Exception e) {
        if (e instanceof java.net.ConnectException) {
            return "连不上目标地址（对端没在监听，或地址/端口写错，或需要连内网）";
        }
        if (e instanceof java.net.http.HttpTimeoutException
                || e instanceof java.net.SocketTimeoutException) {
            return "请求超时（可以调大该用例的超时时间，或确认服务是否卡住）";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "域名解析不了（检查地址拼写，或当前网络是否需要内网/VPN）";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "HTTPS 证书校验失败（核对信任CA、域名、有效期与客户端证书；受管profile不会降级）";
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    /**
     * 环境探活（需求 #74）：把用例的最终地址解出来，对 host:port 做一次 **TCP 连接**。
     *
     * <p>刻意**不发业务请求、不签名、不落库**：探活的唯一用途是判断「环境通没通」，
     * 每 5 秒往被压测环境打一条业务指令是副作用，不是探测。
     *
     * <p>变量替换照做（地址通常是 {@code {{url_xxx}}} 形式）；签名不做 ——
     * 签名只影响请求体与请求头，与「端口连不连得上」无关。
     *
     * @param spec      取地址用的用例（压测里用「推人机」那条）
     * @param timeoutMs 单次连接超时毫秒
     * @return true = 目标 host:port 能建立连接
     */
    public boolean probeReachability(ApiSpec spec, int timeoutMs) {
        if (spec == null) {
            return false;
        }
        try {
            ApiRequest sent = ApiVariables.apply(
                    repo.getVars(repo.resolveEnvForCase(spec.id())), spec.url(), spec.headers(), spec.body());
            URI uri = parseUri(sent.url());
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            int port = uri.getPort();
            if (port <= 0) {
                port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            }
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
                return true;
            }
        } catch (Exception e) {
            // 探活失败是预期结果之一（环境确实不通），只留调试日志，不刷屏
            log.debug("环境探活失败: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 解析与构造 ====================

    /**
     * 解析请求头。接受 JSON 对象文本，例如 {"Content-Type":"application/json"}。
     * 写成「一行一个 Key: Value」会导致解析失败 —— 那种格式歧义太多（值里带冒号就崩），
     * 所以明确只认 JSON，并在报错里给出正确示例。
     */
    private Map<String, String> parseHeaders(String text) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        String t = text.trim();
        if (!t.startsWith("{")) {
            throw new IllegalArgumentException(
                    "请求头要写成 JSON 对象，例如 {\"Content-Type\":\"application/json\"}");
        }
        try {
            JsonNode node = json.readTree(t);
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                out.put(e.getKey(), e.getValue().asText());
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("请求头 JSON 格式不对：" + e.getMessage());
        }
        return out;
    }

    private static URI parseUri(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("地址为空");
        }
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            throw new IllegalArgumentException("地址要以 http:// 或 https:// 开头，当前是：" + u);
        }
        try {
            return URI.create(u);
        } catch (Exception e) {
            throw new IllegalArgumentException("地址格式不对：" + e.getMessage());
        }
    }

    private static String renderHeaders(HttpResponse<?> resp) {
        StringBuilder sb = new StringBuilder("HTTP ").append(resp.statusCode()).append('\n');
        resp.headers().map().forEach((k, vs) -> {
            if (!k.startsWith(":")) {
                sb.append(k).append(": ").append(String.join(", ", vs)).append('\n');
            }
        });
        return sb.toString();
    }

    /**
     * 渲染「本次发出的请求」快照。
     * <p>注意这里只列**我们自己设的头**，不含 Host / Content-Length / User-Agent 这些
     * 由底层 HTTP 客户端自动加的头 —— 那些无法预知，写进来反而不实。
     * 排查「明明填对了却不通」时，这张快照要能直接和方法名、地址、正文拼成一次完整请求。
     */
    private static String renderRequestHeaders(String method, String url,
                                              Map<String, String> headers, String body) {
        return renderRequestHeaders(method,url,ApiHeaders.fromMap(headers),body);
    }
    private static String renderRequestHeaders(String method,String url,List<ApiRequestOptions.HeaderRow> headers,String body){
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url).append('\n');
        headers.forEach(row -> sb.append(row.name()).append(": ").append(row.value()).append('\n'));
        sb.append("（未列出 Host / Content-Length / User-Agent / Connection：由底层客户端自动生成）\n");
        if (body == null || body.isBlank()) {
            sb.append("（无请求体）");
        } else {
            sb.append('\n').append("请求体（原样，").append(body.getBytes(StandardCharsets.UTF_8).length)
                    .append(" 字节）：\n").append(body);
        }
        return sb.toString();
    }

    /**
     * 按「是否跳过证书校验」缓存 HttpClient。HttpClient 是有连接池的重对象，
     * 每条请求都新建会白白付出握手与线程开销。
     */
    private HttpClient client(ApiTransport.Key key,URI target) throws Exception {
        HttpClient cached = clients.get(key);
        if (cached != null) {
            return cached;
        }
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10));
        ApiTransport.configure(b, key, DIRECT, key.cookieEnv() == null ? null
                : cookieManagers.computeIfAbsent(cookieKey(key.cookieEnv(), key.cookieScope()), id -> ApiTransport.newCookieManager()));
        if(key.certificateProfileId()!=null&&!key.certificateProfileId().isBlank()){
            SSLContext profileContext=profiles().tls(key.envId(),key.certificateProfileId(),target);b.sslContext(profileContext);SSLParameters parameters=profileContext.getDefaultSSLParameters();parameters.setEndpointIdentificationAlgorithm("HTTPS");b.sslParameters(parameters);
        } else if (key.insecure()) {
            SSLContext insecureContext=trustAllContext();b.sslContext(insecureContext);
            SSLParameters sp = insecureContext.getDefaultSSLParameters();
            // 空串表示不做主机名校验；内部环境常用自签证书 + IP 直连，两者都要放过
            sp.setEndpointIdentificationAlgorithm("");
            b.sslParameters(sp);
        }
        HttpClient c = b.build();
        if (clients.size() > 256) clients.clear();
        clients.put(key, c);
        return c;
    }

    private final Map<ApiTransport.Key, HttpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, java.net.CookieManager> cookieManagers = new ConcurrentHashMap<>();

    private static String cookieKey(Long envId, String scope) { return scope == null ? "env:" + envId : "scope:" + scope + ":env:" + envId; }
    /** 新运行/显式续跑创建空独立会话；不影响环境默认jar或另一运行。 */
    public void resetCookieScope(String scope) {
        if (scope == null || !scope.matches("[A-Za-z0-9_.-]{1,80}")) throw new IllegalArgumentException("Cookie会话scope无效");
        String prefix = "scope:" + scope + ":env:";
        cookieManagers.entrySet().removeIf(entry -> { if (!entry.getKey().startsWith(prefix)&&!entry.getKey().startsWith("scope:"+scope+"-row-")) return false; entry.getValue().getCookieStore().removeAll(); return true; });
        clients.keySet().removeIf(key -> scope.equals(key.cookieScope())||key.cookieScope()!=null&&key.cookieScope().startsWith(scope+"-row-"));
    }

    public List<Map<String, Object>> cookies(long envId) {
        return cookies(envId,null);
    }
    public List<Map<String,Object>> cookies(long envId,String scope){
        repo.getEnv(envId);
        if(scope!=null&&!scope.matches("[A-Za-z0-9_.-]{1,80}"))throw new IllegalArgumentException("Cookie scope无效");
        var manager = cookieManagers.get(cookieKey(envId, scope));
        if (manager == null) return List.of();
        return manager.getCookieStore().getCookies().stream().map(cookie -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", cookie.getName()); row.put("value", "••••••");
            row.put("id",cookieId(cookie));
            row.put("domain", cookie.getDomain()); row.put("path", cookie.getPath());
            row.put("secure", cookie.getSecure()); row.put("maxAge", cookie.getMaxAge());
            return row;
        }).toList();
    }
    private static String cookieId(java.net.HttpCookie cookie){return ApiRepository.sha256(cookie.getName()+"\n"+cookie.getDomain()+"\n"+cookie.getPath());}
    public void deleteCookie(long envId,String scope,String id){
        repo.getEnv(envId);if(scope!=null&&!scope.matches("[A-Za-z0-9_.-]{1,80}"))throw new IllegalArgumentException("Cookie scope无效");
        var manager=cookieManagers.get(cookieKey(envId,scope));if(manager==null)return;
        if(id==null){manager.getCookieStore().removeAll();return;}if(!id.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Cookie条目身份无效");
        for(var cookie:List.copyOf(manager.getCookieStore().getCookies()))if(cookieId(cookie).equals(id))manager.getCookieStore().remove(null,cookie);
    }

    public void clearCookies(long envId) {
        repo.getEnv(envId);
        var manager = cookieManagers.get(cookieKey(envId, null));
        if (manager != null) manager.getCookieStore().removeAll();
    }

    private static SSLContext trustAllContext() throws Exception {
        TrustManager[] tm = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // 该客户端只用于连测试环境，刻意不校验
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
                // 同上
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tm, new SecureRandom());
        return ctx;
    }
    private static void requireRepeats(int repeats) { if(repeats==0||repeats< -1)throw new IllegalArgumentException("重复次数须为正数或-1"); }
    private ApiExecutionPlan casePlan(String name,List<Long> ids,int interval,int repeats) {
        if(ids==null||ids.isEmpty())throw new IllegalArgumentException("请先选择用例");
        var steps=new ArrayList<ApiExecutionPlan.Step>();String at=Instant.now().toString();
        for(long id:ids){var spec=repo.getCase(id);if(spec.enabled())steps.add(new ApiExecutionPlan.Step(steps.size()+1,spec,capture(spec,null,at),null));}
        if(steps.isEmpty())throw new IllegalArgumentException("没有启用的执行项");
        return new ApiExecutionPlan(1,"CASE",name==null||name.isBlank()?"接口批次":name,at,steps,List.of(ApiExecutionPlan.emptyRow()),repeats,interval,null,null,null);
    }
    private ApiExecutionPlan scenarioPlan(ApiScenario scenario,String name,int interval,int repeats,List<ApiExecutionPlan.Row> rows,String kind) {
        String at=Instant.now().toString();var steps=new ArrayList<ApiExecutionPlan.Step>();
        for(var step:scenario.steps()){
            ApiStepPolicy.validate(step);var spec=repo.getCase(step.caseId());
            if(step.poll()!=null && ApiBodyAssert.parse(spec.asserts()).size()+ApiBodyAssert.parse(step.poll().untilAsserts()).size()>ApiBodyAssert.MAX_RULES)
                throw new IllegalArgumentException("用例断言与轮询条件合计最多50条");
            steps.add(new ApiExecutionPlan.Step(steps.size()+1,spec,capture(spec,step.envId(),at),step));
        }
        return new ApiExecutionPlan(1,kind,name==null||name.isBlank()?scenario.name():name,at,steps,rows,repeats,interval,scenario.id(),scenario.name(),null);
    }
    private ApiBatch startFrozenPlan(ApiExecutionPlan plan) {
        var batch=repo.writeSnapshot(() -> {
            int rounds=plan.repeatCount()==-1?-1:Math.multiplyExact(plan.repeatCount(),plan.rows().size());
            String mode=plan.dataDriven()?"DATA":rounds==1&&plan.intervalSeconds()==0?"ONCE":"REPEAT";
            var created=repo.createBatch(plan.name(),mode,plan.steps().stream().map(step->step.spec().id()).toList(),plan.intervalSeconds(),rounds);
            repo.saveExecutionPlan(created.id(),plan);return created;
        });
        var stop=new AtomicBoolean();stopFlags.put(batch.id(),stop);
        String scope="api-batch-"+batch.id();resetCookieScope(scope);
        var scopedSteps=plan.steps().stream().map(s->new ApiExecutionPlan.Step(s.seq(),s.spec(),s.environment().withSessionScope(scope),s.scenarioStep())).toList();
        var scoped=new ApiExecutionPlan(plan.schema(),plan.kind(),plan.name(),plan.capturedAt(),scopedSteps,plan.rows(),plan.repeatCount(),plan.intervalSeconds(),plan.scenarioId(),plan.scenarioName(),plan.sourceBatchId());
        Thread.ofPlatform().daemon(true).name("api-plan-"+batch.id()).start(()->runFrozenPlan(batch.id(),scoped,stop));
        return batch;
    }
    public void stop(long batchId,boolean immediate) {
        var stop=stopFlags.get(batchId);
        if(stop==null){repo.getBatch(batchId);return;}
        stop.set(true);
        if(immediate)activeRequests.values().stream().filter(c->java.util.Objects.equals(c.batchId,batchId)&&!c.cleanup).forEach(RequestControl::cancel);
    }
    public Map<String,Object> progress(long batchId) {
        var result=new LinkedHashMap<String,Object>();result.put("batch",repo.getBatch(batchId));result.put("running",isRunning(batchId));
        result.put("current",currentSteps.get(batchId));result.put("lastExecId",repo.lastExecutionId(batchId));return result;
    }
    public ApiPlanReview.Summary plan(long batchId) { return ApiPlanReview.summary(batchId,repo.getExecutionPlan(batchId)); }
    public ApiPlanReview.Difference planDifference(long batchId) { return repo.readSnapshot(() -> ApiPlanReview.difference(batchId,repo.getExecutionPlan(batchId),repo,proxy)); }
    private static void requireSource(String source) { if(!List.of("ORIGINAL","CURRENT").contains(source))throw new IllegalArgumentException("source须为ORIGINAL或CURRENT"); }
    private ApiExecutionPlan currentPlan(ApiExecutionPlan original) {
        if(original.scenarioId()!=null) {
            var p=scenarioPlan(repo.getScenario(original.scenarioId()),original.name(),original.intervalSeconds(),original.repeatCount(),original.rows(),original.kind());
            return new ApiExecutionPlan(1,p.kind(),p.name(),p.capturedAt(),p.steps(),p.rows(),p.repeatCount(),p.intervalSeconds(),p.scenarioId(),p.scenarioName(),original.sourceBatchId());
        }
        var steps=new ArrayList<ApiExecutionPlan.Step>();String at=Instant.now().toString();
        for(var step:original.steps()){var spec=repo.getCase(step.spec().id());steps.add(new ApiExecutionPlan.Step(step.seq(),spec,capture(spec,null,at),step.scenarioStep()));}
        return new ApiExecutionPlan(1,original.kind(),original.name(),at,steps,original.rows(),original.repeatCount(),original.intervalSeconds(),null,null,original.sourceBatchId());
    }
    public ApiBatch replay(long batchId,String source,String name,boolean confirmChanges) {
        requireSource(source);if(isRunning(batchId))throw new IllegalStateException("原批次仍在执行，结束后再重放");
        var plan=repo.readSnapshot(() -> {
            var original=repo.getExecutionPlan(batchId);var diff=ApiPlanReview.difference(batchId,original,repo,proxy);
            if("CURRENT".equals(source)&&diff.changed()&&!confirmChanges)throw new IllegalArgumentException("当前配置与原计划不同，请先审阅差异并确认");
            var chosen="CURRENT".equals(source)?currentPlan(original):original;
            return new ApiExecutionPlan(1,chosen.kind(),name==null||name.isBlank()?chosen.name()+" · 重放":name,chosen.capturedAt(),chosen.steps(),chosen.rows(),chosen.repeatCount(),chosen.intervalSeconds(),chosen.scenarioId(),chosen.scenarioName(),batchId);
        });return startFrozenPlan(plan);
    }
    public ApiPlanReview.RetryPreview retryPreview(long batchId) {
        return repo.readSnapshot(() -> {
            var original=repo.getExecutionPlan(batchId);var failed=repo.finalFailedExecutions(batchId);
            var rounds=failed.stream().map(ApiExec::roundNo).distinct().toList();
            var warnings=new ArrayList<String>();if(isRunning(batchId))warnings.add("原批次仍在执行，结束后才允许复跑");
            if(original.scenarioId()!=null)warnings.add("依赖场景将按原失败数据行重跑完整请求链及清理，避免只重跑中间步骤");
            warnings.add("轮询仅按最后结果选失败项，过程中失败后最终通过的步骤不会复跑");
            return new ApiPlanReview.RetryPreview(batchId,!isRunning(batchId)&&!failed.isEmpty(),failed.size(),rounds.size(),original.scenarioId()!=null,rounds,ApiPlanReview.difference(batchId,original,repo,proxy),List.copyOf(warnings));
        });
    }
    public ApiBatch retryFailed(long batchId,String source,String name,boolean confirmChanges) {
        requireSource(source);if(isRunning(batchId))throw new IllegalStateException("原批次仍在执行，不能选择未稳定失败项");
        var plan=repo.readSnapshot(() -> {
            var original=repo.getExecutionPlan(batchId);var failed=repo.finalFailedExecutions(batchId);
            if(failed.isEmpty())throw new IllegalArgumentException("没有最终FAIL/ERROR步骤");
            var diff=ApiPlanReview.difference(batchId,original,repo,proxy);
            if("CURRENT".equals(source)&&diff.changed()&&!confirmChanges)throw new IllegalArgumentException("当前配置与原计划不同，请先审阅差异并确认");
            var selected=new LinkedHashMap<Integer,List<Integer>>();failed.forEach(e->selected.computeIfAbsent(e.roundNo(),ignored->new ArrayList<>()).add(e.seq()));
            var rows=new ArrayList<ApiExecutionPlan.Row>();
            selected.forEach((round,seqs)->{int index=(round-1)%original.rows().size();var row=original.rows().get(index);int iteration=row.dataIteration()>0?row.dataIteration():(round-1)/original.rows().size()+1;
                rows.add(new ApiExecutionPlan.Row(row.dataRowIndex(),iteration,row.data(),original.scenarioId()==null?List.copyOf(seqs):List.of()));});
            var chosen="CURRENT".equals(source)?currentPlan(original):original;
            return new ApiExecutionPlan(1,chosen.kind(),name==null||name.isBlank()?chosen.name()+" · 失败复跑":name,chosen.capturedAt(),chosen.steps(),rows,1,0,chosen.scenarioId(),chosen.scenarioName(),batchId);
        });return startFrozenPlan(plan);
    }
    private void runFrozenPlan(long batchId,ApiExecutionPlan plan,AtomicBoolean stop) {
        boolean infrastructureFailure=false;int round=0;
        try {
            outer:for(int iteration=1;plan.repeatCount()==-1||iteration<=plan.repeatCount();iteration++)for(var row:plan.rows()){
                if(stop.get())break outer;round++;
                Map<Long,Map<String,String>> variables=new LinkedHashMap<>();boolean halted=false;
                try {
                    for(var item:plan.steps()){
                        var step=item.scenarioStep();if(step!=null&&step.cleanup()||!row.includes(item.seq()))continue;
                        String skip=!item.spec().enabled()?"用例未启用":stop.get()?"用户停止，跳过普通步骤":halted&&step!=null&&!step.independent()?"前置失败，跳过下游步骤":null;
                        var result=executePlanStep(batchId,plan,item,row,iteration,round,variables,stop,skip,false);
                        if(step!=null&&((!item.spec().enabled()&&!step.independent())||(skip==null&&!ApiExec.PASS.equals(result.verdict())&&!"CONTINUE".equals(step.onFailure()))))halted=true;
                    }
                } catch(Exception e){infrastructureFailure=true;}
                finally {
                    for(var item:plan.steps()){
                        var step=item.scenarioStep();if(step==null||!step.cleanup())continue;
                        try {executePlanStep(batchId,plan,item,row,iteration,round,variables,stop,item.spec().enabled()?null:"用例未启用",true);}
                        catch(Exception e){infrastructureFailure=true;}
                    }
                }
                if(infrastructureFailure||stop.get())break outer;
                if(plan.intervalSeconds()>0&&(plan.repeatCount()==-1||iteration<plan.repeatCount()||row!=plan.rows().getLast()))
                    if(!waitStep(plan.intervalSeconds()*1000L,Long.MAX_VALUE,stop,false))break outer;
            }
            repo.finishBatch(batchId,infrastructureFailure?ApiBatch.FAILED:stop.get()?ApiBatch.STOPPED:ApiBatch.DONE);
        }catch(Exception e){repo.finishBatch(batchId,ApiBatch.FAILED);}
        finally {resetCookieScope("api-batch-"+batchId);stopFlags.remove(batchId);currentSteps.remove(batchId);}
    }
    private ApiExec executePlanStep(long batchId,ApiExecutionPlan plan,ApiExecutionPlan.Step item,ApiExecutionPlan.Row row,int iteration,int round,
            Map<Long,Map<String,String>> variables,AtomicBoolean stop,String skip,boolean cleanup) {
        var step=item.scenarioStep();var envVars=variables.computeIfAbsent(item.environment().envId(),ignored->new ApiRunVariables());
        var extraction=step==null||step.extractors()==null?extractors(item.spec()):step.extractors();
        var metadata=new LinkedHashMap<String,Object>();metadata.put("planId",batchId);
        if(plan.sourceBatchId()!=null)metadata.put("sourceBatchId",plan.sourceBatchId());
        if(plan.scenarioId()!=null){metadata.put("scenarioId",plan.scenarioId());metadata.put("scenarioName",plan.scenarioName());}
        if(step!=null){metadata.put("stepKey",step.key());metadata.put("stepName",step.name());metadata.put("independent",step.independent());metadata.put("onFailure",step.onFailure());}
        metadata.put("phase",cleanup?"CLEANUP":"NORMAL");
        if(plan.dataDriven()){metadata.put("dataRowIndex",row.dataRowIndex());metadata.put("dataIteration",row.dataIteration()>0?row.dataIteration():iteration);metadata.put("dataRow",row.data());}
        if(skip!=null){extraction.forEach(rule->envVars.remove(rule.name()));var result=skipResult(item,batchId,round,metadata,skip);repo.storeBatchExec(result);return result;}
        long delay=step==null||step.delayMs()==null?0:step.delayMs();
        if(delay>0){currentSteps.put(batchId,new CurrentStep(null,item.spec().id(),step.key(),cleanup?"CLEANUP_WAIT":"WAIT",0));
            if(!waitStep(delay,Long.MAX_VALUE,stop,cleanup)){extraction.forEach(rule->envVars.remove(rule.name()));var result=skipResult(item,batchId,round,metadata,"用户停止，步骤等待已取消");repo.storeBatchExec(result);return result;}}
        var poll=step==null?null:step.poll();int max=poll==null?1:poll.maxAttempts();
        long deadline=poll==null?Long.MAX_VALUE:System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(poll.timeoutMs());
        ApiExec last=null;
        for(int attempt=1;attempt<=max;attempt++){
            if(!cleanup&&stop.get()){
                extraction.forEach(rule->envVars.remove(rule.name()));last=skipResult(item,batchId,round,metadata,"用户停止，未发送下次轮询");repo.storeBatchExec(last);return last;
            }
            var attemptMetadata=new LinkedHashMap<String,Object>(metadata);attemptMetadata.put("attempt",attempt);attemptMetadata.put("maxAttempts",max);
            ApiSpec spec=item.spec();int normalRuleCount=ApiBodyAssert.parse(spec.asserts()).size();
            if(poll!=null){var combined=new ArrayList<>(ApiBodyAssert.parse(spec.asserts()));combined.addAll(ApiBodyAssert.parse(poll.untilAsserts()));spec=copySpec(spec,ApiRequestEngine.stringify(combined),(int)Math.max(1,Math.min(spec.timeoutOrDefault(),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))));}
            var control=new RequestControl(java.util.UUID.randomUUID().toString(),batchId,cleanup);
            currentSteps.put(batchId,new CurrentStep(control.id,spec.id(),step==null?null:step.key(),cleanup?"CLEANUP":"NORMAL",attempt));activeRequests.put(control.id,control);
            if(!cleanup&&stop.get())control.cancel();
            try {var executionEnvironment=item.environment();var transport=spec.options()==null?null:spec.options().transport();
                if(plan.dataDriven()&&(transport==null||!"SHARED".equals(transport.cookieDataRows())))executionEnvironment=executionEnvironment.withSessionScope("api-batch-"+batchId+"-row-"+iteration+"-"+row.dataRowIndex());
                last=executeControlled(spec,executionEnvironment,null,envVars,extraction,attemptMetadata,batchId,round,item.seq(),row.data(),control);}
            finally {activeRequests.remove(control.id);currentSteps.remove(batchId);}
            boolean matched=poll==null||pollMatched(last,normalRuleCount);
            boolean done=poll==null||ApiExec.PASS.equals(last.verdict())||attempt==max||System.nanoTime()>=deadline||(!cleanup&&stop.get());
            var resultMetadata=parseMetadata(last.metadata());resultMetadata.put("pollFinal",done);resultMetadata.put("pollConditionMatched",matched);
            last=withResult(last,last.verdict(),last.reason(),resultMetadata);repo.storeBatchExec(last);
            if(done)return last;
            currentSteps.put(batchId,new CurrentStep(null,item.spec().id(),step.key(),cleanup?"CLEANUP_WAIT":"WAIT",attempt));
            if(!waitStep(poll.intervalMs(),deadline,stop,cleanup)){
                var terminal=new LinkedHashMap<>(metadata);terminal.put("attempt",attempt);terminal.put("attemptRecorded",false);terminal.put("pollFinal",true);
                terminal.put("event",!cleanup&&stop.get()?"WAIT_CANCELLED":"POLL_TIMEOUT");
                last=skipResult(item,batchId,round,terminal,!cleanup&&stop.get()?"用户停止，轮询等待已取消":"轮询总时限已到，未发送额外请求");
                if(cleanup||!stop.get())last=withResult(last,ApiExec.ERROR,last.reason(),terminal);
                extraction.forEach(rule->envVars.remove(rule.name()));repo.storeBatchExec(last);currentSteps.remove(batchId);return last;
            }
        }
        return last;
    }
    private boolean pollMatched(ApiExec result,int offset) {
        try {var values=json.readTree(result.assertsResult());if(values==null||!values.isArray()||values.size()<=offset)return false;for(int i=offset;i<values.size();i++)if(!values.get(i).path("ok").asBoolean())return false;return true;}
        catch(Exception e){return false;}
    }
    private static boolean waitStep(long millis,long deadline,AtomicBoolean stop,boolean cleanup) {
        long until=Math.min(deadline,System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis));
        while(System.nanoTime()<until){if(!cleanup&&stop.get())return false;try{Thread.sleep(Math.max(1,Math.min(25,java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(until-System.nanoTime()))));}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}
        return (cleanup||!stop.get())&&System.nanoTime()<deadline;
    }
    private static ApiSpec copySpec(ApiSpec s,String asserts,int timeout) {return new ApiSpec(s.id(),s.name(),s.method(),s.url(),s.headers(),s.body(),s.note(),s.expectStatus(),s.expectCode(),timeout,s.insecure(),s.enabled(),s.groupId(),asserts,s.options());}
    private Map<String,Object> parseMetadata(String text){try{return json.readValue(text,new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String,Object>>(){});}catch(Exception e){return new LinkedHashMap<>();}}
    private static ApiExec withResult(ApiExec e,String verdict,String reason,Map<String,Object> metadata){return new ApiExec(e.id(),e.batchId(),e.caseId(),e.caseName(),e.roundNo(),e.seq(),e.method(),e.url(),e.httpStatus(),e.respCode(),e.codeMeaning(),verdict,reason,e.elapsedMs(),e.respBody(),e.respHeaders(),e.reqHeaders(),e.reqBody(),e.error(),e.ts(),e.assertsResult(),ApiRequestEngine.stringify(metadata));}
    private static ApiExec skipResult(ApiExecutionPlan.Step item,long batchId,int round,Map<String,Object> metadata,String reason){
        metadata.put("schema",1);metadata.put("envId",item.environment().envId());metadata.put("envName",item.environment().envName());metadata.put("capturedAt",item.environment().capturedAt());
        return new ApiExec(null,batchId,item.spec().id(),item.spec().displayName(),round,item.seq(),item.spec().method(),item.spec().url(),null,null,null,ApiExec.SKIP,reason,0L,null,null,null,null,null,Instant.now().toString(),null,ApiRequestEngine.stringify(metadata));
    }

}
