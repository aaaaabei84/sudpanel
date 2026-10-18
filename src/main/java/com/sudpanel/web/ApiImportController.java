package com.sudpanel.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sudpanel.api.ApiImportService;
import com.sudpanel.api.ApiInterchange;
import com.sudpanel.api.ApiSpec;

@RestController
@RequestMapping("/api/apitest")
public class ApiImportController {
    private final ApiImportService imports;

    public ApiImportController(ApiImportService imports) { this.imports = imports; }

    /** Skip policy is explicit in Input and is independently reparsed by commit; preview never writes or sends. */
    @PostMapping("/import/preview")
    public ApiInterchange.Preview preview(@RequestBody ApiInterchange.Input input) { return ApiInterchange.preview(input); }

    @PostMapping("/import/commit")
    public ApiImportService.Committed commit(@RequestBody ApiImportService.CommitRequest input) { return imports.commit(input); }

    public record ExportRequest(ApiSpec spec, Boolean includeSecrets) {}

    @PostMapping("/export/curl")
    public ApiInterchange.CurlExport curl(@RequestBody ExportRequest input) {
        return ApiInterchange.exportCurl(input.spec(), Boolean.TRUE.equals(input.includeSecrets()));
    }
}
