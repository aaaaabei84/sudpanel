package com.sudpanel.web;

import com.sudpanel.backup.TransferService;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 本机完整数据两步迁移；导入一次受理后由平台自动重启。 */
@RestController @RequestMapping("/api/maintenance/transfers")
public class TransferController {
    private final TransferService service;
    public TransferController(TransferService service){this.service=service;}
    @PostMapping("/export") public TransferService.Archive export(){return service.exportArchive();}
    @GetMapping("/{id}/download") public ResponseEntity<FileSystemResource> download(@PathVariable String id){
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"SudPanel-complete-data-"+id+".zip\"")
            .body(new FileSystemResource(service.archiveFile(id)));
    }
    @PostMapping(value="/import",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public TransferService.Imported upload(@RequestBody byte[] content,@RequestHeader("X-Transfer-Id") String operationId){return service.importArchive(content,operationId);}
    @GetMapping("/status") public Map<String,Object> status(@RequestParam(required=false) String operationId){return service.status(operationId);}
}
