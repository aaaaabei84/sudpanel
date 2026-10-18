package com.sudpanel.api;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** 每次提交重新解析原文，整批追加在一个事务中，永不覆盖现有配置。 */
@Service
public class ApiImportService {
    private final ApiRepository repo;
    private final TransactionTemplate transaction;

    public record CommitRequest(String format,String content,String environmentContent,String name,Long envId,Map<String,List<String>>variableScopes,Boolean skipInvalidRequests,Boolean enableSignatureScripts) {
        public CommitRequest(String format,String content,String environmentContent,String name,Long envId,Map<String,List<String>>variableScopes,Boolean skipInvalidRequests){this(format,content,environmentContent,name,envId,variableScopes,skipInvalidRequests,null);}
        public CommitRequest(String format,String content,String environmentContent,String name,Long envId,Map<String,List<String>>variableScopes){this(format,content,environmentContent,name,envId,variableScopes,null,null);}
        public CommitRequest(String format,String content,String environmentContent,String name,Long envId){this(format,content,environmentContent,name,envId,null,null,null);}
    }
    public record Committed(long envId, String environmentName, List<Long> groupIds, List<Long> caseIds, List<String> warnings,List<ApiInterchange.SkippedRequest> skippedRequests,int skippedCount) {
        public Committed(long envId,String environmentName,List<Long>groupIds,List<Long>caseIds,List<String>warnings){this(envId,environmentName,groupIds,caseIds,warnings,List.of(),0);}
    }

    public ApiImportService(ApiRepository repo, JdbcTemplate jdbc) {
        this.repo = repo;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
    }

    public Committed commit(CommitRequest request) {
        if (request == null) throw new IllegalArgumentException("导入内容不能为空");
        if ("CURL".equalsIgnoreCase(request.format())) throw new IllegalArgumentException("cURL 只载入快速请求草稿，请核对后手动保存");
        var preview = ApiInterchange.forCommit(new ApiInterchange.Input(request.format(),request.content(),request.environmentContent(),request.variableScopes(),null,request.skipInvalidRequests(),request.enableSignatureScripts()));
        if (!preview.errors().isEmpty()) throw new IllegalArgumentException("导入未提交：" + String.join("；", preview.errors()));
        boolean hasVariables = !preview.variables().base().isEmpty() || !preview.variables().header().isEmpty() || !preview.variables().body().isEmpty();
        if (preview.requests().isEmpty() && preview.folders().isEmpty() && !hasVariables) throw new IllegalArgumentException("没有可导入的请求或变量");
        return transaction.execute(status -> {
            var warnings = new ArrayList<>(preview.warnings());
            long envId;
            if (hasVariables || preview.environmentName() != null) {
                String base = request.name() == null || request.name().isBlank()
                        ? preview.environmentName() == null ? preview.name() + " 环境" : preview.environmentName() : request.name();
                Set<String> names = new HashSet<>(); repo.listEnvs().forEach(e -> names.add(e.name()));
                envId = repo.createEnv(uniqueName(base, names)).id();
                repo.saveVars(envId, preview.variables().base(), preview.variables().header(), preview.variables().body());
                if (request.envId() != null) warnings.add("导入含环境或变量，已新建独立环境；未修改所选现有环境");
            } else {
                envId = request.envId() == null ? repo.defaultEnvId() : request.envId();
                repo.getEnv(envId);
            }
            Set<String> names = new HashSet<>(); repo.listCaseGroups().forEach(g -> names.add(g.name()));
            Map<String, Long> groups = new LinkedHashMap<>();
            var cases = new ArrayList<Long>();
            for(var folder:preview.folders()) {
                String label=request.name()==null||request.name().isBlank()?folder.name():request.name()+" / "+folder.name();
                var group=repo.createCaseGroup(uniqueName(label,names),envId);repo.saveGroupImportInfo(group.id(),folder.path(),folder.note());groups.put(folder.key(),group.id());
            }
            for (var imported : preview.requests()) {
                String folder = imported.folder() == null || imported.folder().isBlank() ? preview.name() : imported.folder();
                if (request.name() != null && !request.name().isBlank()) folder = request.name() + (folder.equals(preview.name()) ? "" : " / " + folder);
                final String groupName = folder;
                String key=imported.folderKey()==null?folder:imported.folderKey();
                long groupId = groups.computeIfAbsent(key, f -> repo.createCaseGroup(uniqueName(groupName, names), envId).id());
                var s = imported.spec();
                var created = repo.createCase(new ApiSpec(null, s.name(), s.method(), s.url(), s.headers(), s.body(), s.note(),
                        s.expectStatus(), s.expectCode(), s.timeoutMs(), s.insecure(), s.enabled(), groupId, s.asserts(), s.options()));
                cases.add(created.id());
            }
            return new Committed(envId, repo.getEnv(envId).name(), List.copyOf(groups.values()), List.copyOf(cases), List.copyOf(warnings),preview.skippedRequests(),preview.skippedCount());
        });
    }

    static String uniqueName(String raw, Set<String> names) {
        String base = raw == null || raw.isBlank() ? "导入" : raw.trim();
        if (base.length() > 60) base = base.substring(0, 60);
        String candidate = base;
        for (int n = 2; names.contains(candidate); n++) {
            String suffix = " " + n;
            candidate = base.substring(0, Math.min(base.length(), 60 - suffix.length())) + suffix;
        }
        names.add(candidate); return candidate;
    }
}
