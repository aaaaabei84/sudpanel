package com.sudpanel.web;

import com.sudpanel.perf.PerfPreflightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PerfPreflightController {
    private final PerfPreflightService preflight;
    public PerfPreflightController(PerfPreflightService preflight) { this.preflight = preflight; }
    @PostMapping("/api/perf/preflight")
    public PerfPreflightService.Result check(@RequestBody PerfPreflightService.Request request) { return preflight.check(request); }
    @GetMapping("/api/devices/diagnostics")
    public PerfPreflightService.Result device(@RequestParam(required = false) String serial) { return preflight.device(serial); }
}
