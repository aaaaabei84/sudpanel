package com.sudpanel.backup;
import com.sudpanel.perf.PerfRunService;
import com.sudpanel.runner.RunService;
import com.sudpanel.api.ApiRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
@Component
public class MaintenanceActivity {
    private final ObjectProvider<RunService> function;private final ObjectProvider<PerfRunService> perf;private final ObjectProvider<ApiRunner> api;
    public MaintenanceActivity(ObjectProvider<RunService> function,ObjectProvider<PerfRunService> perf,ObjectProvider<ApiRunner> api){this.function=function;this.perf=perf;this.api=api;}
    public boolean busy(){var f=function.getIfAvailable();var p=perf.getIfAvailable();var a=api.getIfAvailable();return a!=null&&a.hasExecutionOwner()|| f!=null&&f.hasExecutionOwner()||p!=null&&p.hasExecutionOwner();}
}
