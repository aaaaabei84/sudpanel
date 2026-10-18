package com.sudpanel.web;
import com.sudpanel.api.configarchive.ApiConfigArchive;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
@RestController @RequestMapping("/api/apitest/config")
public class ApiConfigArchiveController {
    private final ApiConfigArchive archive;
    public ApiConfigArchiveController(ApiConfigArchive archive){this.archive=archive;}
    public record Request(String content){}
    @GetMapping("/export") public ResponseEntity<ApiConfigArchive.Bundle> export(@RequestParam(defaultValue="false") boolean includeSecrets){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"sudpanel-config-v1.json\"").body(archive.export(includeSecrets));}
    @PostMapping("/preview") public ApiConfigArchive.Preview preview(@RequestBody Request r){return archive.preview(r.content());}
    @PostMapping("/commit") public ApiConfigArchive.Imported commit(@RequestBody Request r){return archive.commit(r.content());}
}
