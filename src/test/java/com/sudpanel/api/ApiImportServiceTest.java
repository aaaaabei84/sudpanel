package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApiImportServiceTest {
    @TempDir Path dir;
    private JdbcTemplate jdbc;
    private ApiRepository repo;
    private ApiImportService service;
    private String collection = "{\"info\":{\"name\":\"fixture\",\"schema\":\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"},\"variable\":[{\"key\":\"endpoint\",\"value\":\"https://example.test\"}],\"item\":[{\"name\":\"a\",\"request\":\"{{endpoint}}/a\"},{\"name\":\"b\",\"request\":\"{{endpoint}}/b\"}]}";

    @BeforeEach void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("import.db")));
        repo = new ApiRepository(jdbc); service = new ApiImportService(repo, jdbc);
    }

    @Test void commitsAppendAndBindNewEnvironmentWithoutOverwritingExisting() {
        repo.saveVars(1, List.of(new ApiVariables.Var("endpoint", "original")), List.of(), List.of());
        var first = service.commit(new ApiImportService.CommitRequest("POSTMAN", collection, null, null, 1L));
        var second = service.commit(new ApiImportService.CommitRequest("POSTMAN", collection, null, null, 1L));
        assertThat(first.caseIds()).hasSize(2); assertThat(second.caseIds()).hasSize(2);
        assertThat(first.envId()).isNotEqualTo(1).isNotEqualTo(second.envId());
        assertThat(repo.getVars(1).base()).containsExactly(new ApiVariables.Var("endpoint", "original"));
        assertThat(repo.listCases()).hasSize(4); assertThat(repo.listCaseGroups()).hasSize(2);
        assertThat(repo.getCaseGroup(first.groupIds().getFirst()).envId()).isEqualTo(first.envId());
        assertThat(repo.getCase(first.caseIds().getFirst()).options().auth().type()).isEqualTo("NONE");
        assertThat(new ApiRepository(jdbc).getVars(first.envId()).base()).containsExactly(new ApiVariables.Var("endpoint", "https://example.test"));
    }

    @Test void injectedStorageFailureRollsBackEnvironmentGroupAndFirstCaseTogether() {
        var failing = new ApiRepository(jdbc) {
            int calls;
            @Override public ApiSpec createCase(ApiSpec spec) {
                if (++calls == 2) throw new IllegalStateException("fixture write failure");
                return super.createCase(spec);
            }
        };
        var imports = new ApiImportService(failing, jdbc);
        assertThatThrownBy(() -> imports.commit(new ApiImportService.CommitRequest("POSTMAN", collection, null, null, null))).hasMessageContaining("fixture write failure");
        assertThat(repo.listCases()).isEmpty(); assertThat(repo.listCaseGroups()).isEmpty(); assertThat(repo.listEnvs()).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM api_var", Integer.class)).isZero();
    }

    @Test void standaloneEnvironmentImportsWithoutRequestAndNeverMutatesDefault() {
        var result = service.commit(new ApiImportService.CommitRequest("POSTMAN", "{\"name\":\"fixture-env\",\"values\":[{\"key\":\"x\",\"value\":\"y\"}]}", null, null, 1L));
        assertThat(result.caseIds()).isEmpty(); assertThat(result.groupIds()).isEmpty(); assertThat(result.envId()).isNotEqualTo(1);
        assertThat(repo.getVars(result.envId()).body()).containsExactly(new ApiVariables.Var("x", "y"));
        assertThat(repo.getVars(1).body()).isEmpty();
    }

    @Test void curlsAndBadCollectionsCannotCommit() {
        assertThatThrownBy(() -> service.commit(new ApiImportService.CommitRequest("CURL", "curl https://example.test", null, null, null))).hasMessageContaining("草稿");
        assertThatThrownBy(() -> service.commit(new ApiImportService.CommitRequest("POSTMAN", collection.replace("https://schema.getpostman.com/json/collection/v2.1.0/collection.json", "wrong-version"), null, null, null))).hasMessageContaining("未提交");
        assertThat(repo.listCases()).isEmpty(); assertThat(repo.listEnvs()).hasSize(1);
    }

    @Test void longAndCollidingNamesRemainValidAndDistinct() {
        var names = new java.util.HashSet<String>();
        String raw = "x".repeat(100);
        String first = ApiImportService.uniqueName(raw, names), second = ApiImportService.uniqueName(raw, names);
        assertThat(first).hasSize(60); assertThat(second).hasSize(60).endsWith(" 2"); assertThat(first).isNotEqualTo(second);
    }

    @Test void openApiPreviewAndCommitUseSameParserAndAppendToExplicitEnvironment() {
        long env = repo.createEnv("fixture-target").id();
        String content = "{\"openapi\":\"3.0.3\",\"info\":{\"title\":\"fixture-api\",\"version\":\"1\"},\"servers\":[{\"url\":\"https://example.test\"}],\"paths\":{\"/x\":{\"get\":{}}}}";
        var result = service.commit(new ApiImportService.CommitRequest("OPENAPI", content, null, null, env));
        assertThat(result.envId()).isEqualTo(env); assertThat(result.caseIds()).hasSize(1);
        var saved = repo.getCase(result.caseIds().getFirst());
        assertThat(saved.url()).isEqualTo("https://example.test/x");
        assertThat(repo.getCaseGroup(saved.groupId()).envId()).isEqualTo(env);
        assertThat(repo.listEnvs()).hasSize(2);
    }
}
