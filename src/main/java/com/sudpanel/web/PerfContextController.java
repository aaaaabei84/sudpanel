package com.sudpanel.web;

import com.sudpanel.perf.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/perf")
public class PerfContextController {
    private final PerfRepository repo;private final CalibrationService calibration;private final PerfEvidencePackage packages;
    public PerfContextController(PerfRepository repo,CalibrationService calibration,PerfEvidencePackage packages){this.repo=repo;this.calibration=calibration;this.packages=packages;}
    private void exists(long run){if(repo.getRun(run)==null)throw new NoSuchElementException("运行不存在");}
    @GetMapping("/runs/{id}/timeline") public Map<String,Object> timeline(@PathVariable long id){exists(id);return repo.facts().timeline(id);}
    @GetMapping("/runs/{id}/conditions") public Map<String,Object> conditions(@PathVariable long id){exists(id);return repo.facts().summary(id);}
    @GetMapping("/runs/{id}/compare") public Map<String,Object> compare(@PathVariable long id,@RequestParam long other){exists(id);exists(other);return repo.facts().compare(id,other);}
    @GetMapping("/runs/{id}/trend") public Map<String,Object> trend(@PathVariable long id,@RequestParam long gameId,@RequestParam(defaultValue="true") boolean comparableOnly){exists(id);return repo.facts().trend(repo,id,gameId,comparableOnly);}
    @GetMapping("/coordinate-profile") public Map<String,Object> layout(@RequestParam String deviceSerial,@RequestParam(required=false) Long groupId){return calibration.layoutStatus(deviceSerial,groupId);}
    public record Bind(String deviceSerial,boolean confirmed){}
    @PostMapping("/coordinate-profile/fixed") public Map<String,Object> bind(@RequestBody Bind bind){if(!bind.confirmed()||bind.deviceSerial()==null||bind.deviceSerial().isBlank())throw new IllegalArgumentException("请现场核对固定坐标后明确确认");return calibration.bindFixedLayout(bind.deviceSerial());}
    @GetMapping(value="/runs/{id}/evidence.zip",produces="application/zip") public ResponseEntity<byte[]> pack(@PathVariable long id,@RequestParam(defaultValue="false")boolean includePrivateFiles,@RequestParam(required=false)String confirmation,jakarta.servlet.http.HttpServletRequest request){
        exists(id);if(includePrivateFiles)try{if(!java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress())throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"私密证据包只允许本机下载");}catch(java.net.UnknownHostException invalid){throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN,"私密证据包只允许本机下载");}
        if(includePrivateFiles&&!"PRIVATE_LOCAL_ONLY".equals(confirmation))throw new IllegalArgumentException("私密附件仅限本机，须明确确认");
        return ResponseEntity.ok().header("Content-Disposition","attachment; filename=perf-evidence"+(includePrivateFiles?"-PRIVATE":"-redacted")+".zip").body(packages.create(id,includePrivateFiles));
    }
}
