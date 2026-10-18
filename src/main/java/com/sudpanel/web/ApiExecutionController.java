package com.sudpanel.web;

import java.util.Map;
import org.springframework.web.bind.annotation.*;
import com.sudpanel.api.*;

/** 新执行合同独立入口；读计划不返回内部密钥、数据值或签名配置。 */
@RestController
@RequestMapping("/api/apitest")
public class ApiExecutionController {
    private final ApiRunner runner;private final ApiRepository repo;
    public ApiExecutionController(ApiRunner runner,ApiRepository repo){this.runner=runner;this.repo=repo;}
    public record RequestInput(ApiSpec spec,Long envId,Map<String,Object> sourceContext,String requestId) {}
    public record ReplayInput(String source,String name,Boolean confirmConfigChanges) {}
    public record StrictInput(Boolean strictVariables) {}
    public record ActualCurlInput(ApiSpec spec,Long envId,Boolean includeSecrets) {}
    public record PathInput(String body,Map<String,java.util.List<String>>headers,String path) {}
    @PostMapping("/export/curl/actual") public ApiActualCurl.Export actualCurl(@RequestBody ActualCurlInput input){return runner.actualCurl(input.spec(),input.envId(),Boolean.TRUE.equals(input.includeSecrets()));}
    @GetMapping("/execs/{id}/curl") public ApiActualCurl.Export historyCurl(@PathVariable long id,@RequestParam(defaultValue="false") boolean includeSecrets){return ApiActualCurl.history(repo.getExec(id),includeSecrets);}
    @PostMapping("/inspect-response/path") public ApiResponseTools.PathInspection readPath(@RequestBody PathInput input){return ApiResponseTools.readPath(input.body(),input.headers(),input.path());}
    @PostMapping("/requests") public ApiRunner.RequestView submit(@RequestBody RequestInput input){return runner.submitRequest(input.spec(),input.envId(),input.sourceContext(),input.requestId());}
    @GetMapping("/requests/{id}") public ApiRunner.RequestView request(@PathVariable String id){return runner.request(id);}
    @PostMapping("/requests/{id}/cancel") public ApiRunner.RequestView cancel(@PathVariable String id){return runner.cancelRequest(id);}
    @GetMapping("/batches/{id}/progress") public Map<String,Object> progress(@PathVariable long id){return runner.progress(id);}
    @GetMapping("/batches/{id}/executions") public ApiRepository.ExecutionPage executions(@PathVariable long id,@RequestParam(required=false) Long afterId,@RequestParam(defaultValue="50") int limit){return repo.executionPage(id,afterId,limit);}
    @GetMapping("/batches/{id}/plan") public ApiPlanReview.Summary plan(@PathVariable long id){return runner.plan(id);}
    @GetMapping("/batches/{id}/plan/diff") public ApiPlanReview.Difference difference(@PathVariable long id){return runner.planDifference(id);}
    @PostMapping("/batches/{id}/replay") public ApiBatch replay(@PathVariable long id,@RequestBody ReplayInput input){return runner.replay(id,input.source(),input.name(),Boolean.TRUE.equals(input.confirmConfigChanges()));}
    @GetMapping("/batches/{id}/retry-failed/preview") public ApiPlanReview.RetryPreview retryPreview(@PathVariable long id){return runner.retryPreview(id);}
    @PostMapping("/batches/{id}/retry-failed") public ApiBatch retry(@PathVariable long id,@RequestBody ReplayInput input){return runner.retryFailed(id,input.source(),input.name(),Boolean.TRUE.equals(input.confirmConfigChanges()));}
    @PutMapping("/envs/{id}/strict") public ApiEnv strict(@PathVariable long id,@RequestBody StrictInput input){return repo.setEnvStrict(id,input.strictVariables());}
}
