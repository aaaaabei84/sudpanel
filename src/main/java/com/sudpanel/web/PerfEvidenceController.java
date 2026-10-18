package com.sudpanel.web;

import com.sudpanel.perf.PerfEvidenceRepository;
import com.sudpanel.perf.PerfRepository;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/perf")
public class PerfEvidenceController {
    private final PerfEvidenceRepository evidence;
    private final PerfRepository perf;
    private final com.sudpanel.perf.PerfExportService exports;
    @org.springframework.beans.factory.annotation.Autowired private com.sudpanel.perf.PerfSettlementService settlement;

    public PerfEvidenceController(PerfEvidenceRepository evidence, PerfRepository perf) {
        this(evidence,perf,new com.sudpanel.perf.PerfExportService(perf,evidence));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public PerfEvidenceController(PerfEvidenceRepository evidence,PerfRepository perf,com.sudpanel.perf.PerfExportService exports) {
        this.evidence=evidence;this.perf=perf;this.exports=exports;
    }

    @GetMapping("/loops/{loopId}/evidence")
    public Map<String, Object> loop(@PathVariable long loopId) { return evidence.loopEvidence(loopId); }

    @GetMapping("/runs/{runId}/steps")
    public List<Map<String, Object>> steps(@PathVariable long runId) {
        if (perf.getRun(runId) == null) throw new NoSuchElementException("运行不存在");
        return evidence.runSteps(runId);
    }

    @GetMapping("/steps/{id}")
    public Map<String, Object> detail(@PathVariable long id) { return evidence.detail(id); }
    @GetMapping(value="/runs/{runId}/samples.csv",produces="text/csv;charset=UTF-8")
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> samples(
            @PathVariable long runId,@org.springframework.web.bind.annotation.RequestParam(required=false) Long gameId,
            @org.springframework.web.bind.annotation.RequestParam(required=false) Long loopId) {
        perf.validateExportScope(runId,gameId,loopId);
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=perf-run"+runId+"-samples.csv")
                .body(output -> exports.samples(runId,gameId,loopId,output));
    }
    @GetMapping(value="/runs/{runId}/export.zip",produces="application/zip")
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> zip(
            @PathVariable long runId,@org.springframework.web.bind.annotation.RequestParam(required=false) Long gameId) {
        perf.validateExportScope(runId,gameId,null);
        return org.springframework.http.ResponseEntity.ok().header("Content-Disposition","attachment; filename=perf-run"+runId+".zip")
                .body(output -> exports.zip(runId,gameId,output));
    }

    @org.springframework.web.bind.annotation.PostMapping("/loops/{loopId}/settlement-check")
    public Map<String,Object> checkSettlement(@PathVariable long loopId,@org.springframework.web.bind.annotation.RequestBody com.sudpanel.perf.PerfSettlementService.Request request) {return settlement.check(loopId,request);}
    @GetMapping("/loops/{loopId}/settlement-checks") public List<Map<String,Object>> settlementChecks(@PathVariable long loopId) {return perf.settlementChecks(loopId);}

}
