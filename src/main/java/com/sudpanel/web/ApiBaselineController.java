package com.sudpanel.web;
import com.sudpanel.api.*;
import java.util.List;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/apitest/baselines")
public class ApiBaselineController {
    private final ApiBaselineRepository baselines;
    public ApiBaselineController(ApiBaselineRepository baselines){this.baselines=baselines;}
    public record Create(String name,long execId,List<String> ignorePaths){}
    public record Compare(long execId){}
    @GetMapping public List<ApiBaselineRepository.Baseline> list(){return baselines.list();}
    @PostMapping public ApiBaselineRepository.Baseline create(@RequestBody Create r){return baselines.create(r.name(),r.execId(),r.ignorePaths());}
    @DeleteMapping("/{id}") public void delete(@PathVariable long id){baselines.delete(id);}
    @PostMapping("/{id}/compare") public ApiHistoryCompare.Compared compare(@PathVariable long id,@RequestBody Compare r){return baselines.compare(id,r.execId());}
    @GetMapping("/{id}/assertions") public ApiBaselineRepository.Generated assertions(@PathVariable long id){return baselines.assertions(id);}
}
