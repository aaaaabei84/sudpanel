package com.sudpanel.web;

import com.sudpanel.perf.*;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/perf")
public class PerfQualityController {
    private final PerfRepository repo;
    public PerfQualityController(PerfRepository repo){this.repo=repo;}
    @GetMapping("/loops/{id}/quality") public Map<String,Object> quality(@PathVariable long id){return repo.loopQuality(id);}
    @GetMapping("/health-policy") public PerfDeviceHealth.Policy policy(){return repo.healthPolicy();}
    @PutMapping("/health-policy") public PerfDeviceHealth.Policy policy(@RequestBody PerfDeviceHealth.Policy policy){repo.saveHealthPolicy(policy);return policy;}
}
