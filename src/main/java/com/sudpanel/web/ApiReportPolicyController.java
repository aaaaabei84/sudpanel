package com.sudpanel.web;
import com.sudpanel.api.ApiReportPolicy;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/apitest/report-policy")
public class ApiReportPolicyController {
    private final ApiReportPolicy policy;
    public ApiReportPolicyController(ApiReportPolicy policy){this.policy=policy;}
    @GetMapping public ApiReportPolicy.Policy get(){return policy.get();}
    @PostMapping public ApiReportPolicy.Policy save(@RequestBody ApiReportPolicy.Policy p){return policy.save(p);}
}
