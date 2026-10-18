package com.sudpanel.web;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.sudpanel.api.ApiExec;
import com.sudpanel.api.ApiHistoryCompare;
import com.sudpanel.api.ApiHistoryDraft;
import com.sudpanel.api.ApiHistoryRepository;

@RestController
@RequestMapping("/api/apitest")
public class ApiHistoryController {
    private final ApiHistoryRepository history;
    public ApiHistoryController(ApiHistoryRepository history) { this.history = history; }

    @GetMapping("/history")
    public ApiHistoryRepository.Page history(@RequestParam(required = false) Long beforeId, @RequestParam(defaultValue = "30") Integer limit,
            @RequestParam(required = false) String q, @RequestParam(required = false) String verdict, @RequestParam(required = false) String method,
            @RequestParam(required = false) Long caseId, @RequestParam(required = false) Long envId, @RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) Boolean adhoc,
            @RequestParam(required = false) String statsDimension, @RequestParam(required = false) String statsKey) {
        return history.history(new ApiHistoryRepository.Query(beforeId, limit, q, verdict, method, caseId, envId, batchId, from, to, adhoc,statsDimension,statsKey));
    }

    @GetMapping("/history/{id}")
    public ApiExec detail(@PathVariable long id) { return history.detail(id); }

    @GetMapping("/history/{id}/draft")
    public ApiHistoryDraft.Draft draft(@PathVariable long id) { return history.draft(id); }

    public record CompareRequest(long leftId,long rightId,List<String> ignorePaths,Boolean includeSecrets) { public CompareRequest(long leftId,long rightId,List<String> ignorePaths){this(leftId,rightId,ignorePaths,false);} }

    @PostMapping("/history/compare")
    public ApiHistoryCompare.Compared compare(@RequestBody CompareRequest request) { return history.compare(request.leftId(),request.rightId(),request.ignorePaths(),Boolean.TRUE.equals(request.includeSecrets())); }

    @GetMapping(value="/batches/{id}/export.json", produces="application/json")
    public ResponseEntity<Map<String, Object>> exportJson(@PathVariable long id, @RequestParam(defaultValue="false") boolean includeSecrets) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"api-batch-" + id + ".json\"")
                .contentType(MediaType.APPLICATION_JSON).body(history.exportJson(id, includeSecrets));
    }
    @GetMapping(value="/batches/{id}/export.junit", produces="application/xml;charset=UTF-8")
    public ResponseEntity<byte[]> exportJunit(@PathVariable long id, @RequestParam(defaultValue="false") boolean includeSecrets) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"api-batch-" + id + ".xml\"")
                .contentType(MediaType.parseMediaType("application/xml;charset=UTF-8")).body(history.exportJunit(id, includeSecrets).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/batches/{id}/stats")
    public ApiHistoryRepository.Stats stats(@PathVariable long id) { return history.stats(id); }
}
