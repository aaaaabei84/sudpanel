package com.sudpanel.web;
import com.sudpanel.backup.MaintenanceService;
import java.nio.file.Path;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/maintenance")
public class MaintenanceController {
    private final MaintenanceService service;
    public MaintenanceController(MaintenanceService service){this.service=service;}
    @GetMapping("/overview") public MaintenanceService.Overview overview(){return service.overview();}
    @GetMapping("/retention") public MaintenanceService.Retention retention(){return service.retention();}
    @PutMapping("/retention") public MaintenanceService.Retention retention(@RequestBody MaintenanceService.Retention r){return service.retention(r);}
    @GetMapping("/backups") public List<MaintenanceService.Backup> backups(){return service.backups();}
    @PostMapping("/backups") public MaintenanceService.Backup backup(){return service.backup();}
    @GetMapping("/backups/{id}/download") public ResponseEntity<FileSystemResource> download(@PathVariable String id){return file(service.backupFile(id),"sudpanel-private-backup-"+id+".zip","application/zip");}
    @PostMapping(value="/restores/preview",consumes=MediaType.APPLICATION_OCTET_STREAM_VALUE) public MaintenanceService.RestorePreview preview(@RequestBody byte[] content){return service.preview(content);}
    public record Stage(String confirmSha256){}
    @PostMapping("/restores/{id}/stage") public Map<String,Object> stage(@PathVariable String id,@RequestBody Stage r){return service.stage(id,r.confirmSha256());}
    @GetMapping("/restores/status") public Map<String,Object> status(){return service.restoreStatus();}
    @DeleteMapping("/restores/stage") public Map<String,Object> unstage(){return service.unstage();}
    @PostMapping("/cleanup/preview") public MaintenanceService.CleanupPreview preview(@RequestBody MaintenanceService.CleanupRequest r){return service.cleanupPreview(r);}
    public record Commit(String token){}
    @PostMapping("/cleanup/commit") public MaintenanceService.Cleaned commit(@RequestBody Commit r){return service.cleanupCommit(r.token());}
    @GetMapping("/archives/{id}/download") public ResponseEntity<FileSystemResource> archive(@PathVariable String id){return file(service.archiveFile(id),"sudpanel-private-history-"+id+".json","application/json");}
    private static ResponseEntity<FileSystemResource> file(Path path,String name,String type){return ResponseEntity.ok().contentType(MediaType.parseMediaType(type)).header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+name+"\"").body(new FileSystemResource(path));}
}
