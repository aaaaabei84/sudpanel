package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;

class ApiEnvironmentPersistenceTest {
    @TempDir Path dir;
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private ApiRepository repo;

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class Transactions {
        @Bean DataSourceTransactionManager transactionManager(DriverManagerDataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }
        @Bean ApiRepository apiRepository(JdbcTemplate jdbc) {
            return new ApiRepository(jdbc);
        }
    }

    @BeforeEach void setUp() {
        openContext();
    }

    private void openContext() {
        var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("env.db"));
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DriverManagerDataSource.class, () -> ds);
        context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(ds));
        context.register(Transactions.class);
        context.refresh();
        jdbc = context.getBean(JdbcTemplate.class);
        repo = context.getBean(ApiRepository.class);
    }

    private void restartRepository() {
        context.close();
        openContext();
        assertThat(AopUtils.isAopProxy(repo)).isTrue();
    }

    private void assertEnvironmentVariableIndex() {
        assertThat(jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='api_var'", String.class))
                .contains("uq_api_var_env")
                .doesNotContain("uq_api_var");
        assertThat(jdbc.queryForList("SELECT name FROM pragma_index_info('uq_api_var_env')", String.class))
                .containsExactly("env_id", "scope", "name");
    }

    @AfterEach void tearDown() {
        context.close();
    }

    @Test void repeatedInitializationDoesNotRestoreGlobalVariableUniqueness() {
        long env = repo.createEnv("restart-check").id();
        var original = repo.saveVars(env, List.of(new ApiVariables.Var("endpoint", "original")),
                List.of(), List.of());
        for (int restart = 0; restart < 3; restart++) {
            restartRepository();
            assertEnvironmentVariableIndex();
            assertThat(repo.getVars(env)).isEqualTo(original);
        }
    }

    @Test void twoEnvironmentsCanSaveSameVariableNamesIndependentlyAfterRestart() {
        long first = repo.createEnv("first-environment").id();
        long second = repo.createEnv("second-environment").id();
        restartRepository();

        var firstSaved = repo.saveEnvironmentSettings(first,
                new ApiSigner.Config(false, "first-app", "", "first-nonce", "first-game"),
                new ApiVariables.VarSet(List.of(new ApiVariables.Var("endpoint", "first-base")),
                        List.of(new ApiVariables.Var("endpoint", "first-header")),
                        List.of(new ApiVariables.Var("endpoint", "first-body"))));
        var secondSaved = repo.saveEnvironmentSettings(second,
                new ApiSigner.Config(false, "second-app", "", "second-nonce", "second-game"),
                new ApiVariables.VarSet(List.of(new ApiVariables.Var("endpoint", "second-base")),
                        List.of(new ApiVariables.Var("endpoint", "second-header")),
                        List.of(new ApiVariables.Var("endpoint", "second-body"))));
        assertThat(repo.getVars(first)).isEqualTo(firstSaved.vars());
        assertThat(repo.getSignConfig(first).appId()).isEqualTo("first-app");
        assertThat(repo.getVars(second)).isEqualTo(secondSaved.vars());
        assertThat(repo.getSignConfig(second).appId()).isEqualTo("second-app");

        var firstUpdated = repo.saveVars(first, List.of(new ApiVariables.Var("endpoint", "updated-base")),
                List.of(), List.of());
        assertThat(repo.getVars(second)).isEqualTo(secondSaved.vars());
        repo.saveVars(second, List.of(new ApiVariables.Var("endpoint", "second-updated")),
                List.of(), List.of());
        assertThat(repo.getVars(first)).isEqualTo(firstUpdated);
    }

    @Test void repairsExistingEnvironmentColumnWithBothIndexesWithoutLosingConfiguration() {
        long first = repo.createEnv("upgrade-first").id();
        long second = repo.createEnv("upgrade-second").id();
        var firstVars = repo.saveVars(first, List.of(new ApiVariables.Var("first-endpoint", "first-value")),
                List.of(new ApiVariables.Var("first-header", "first-header-value")),
                List.of(new ApiVariables.Var("first-body", "first-body-value")));
        var secondVars = repo.saveVars(second,
                List.of(new ApiVariables.Var("second-endpoint", "second-value")), List.of(), List.of());
        repo.saveSignConfig(first, false, "upgrade-app", "fixture-only-signing-value", "nonce", "game");
        repo.saveSignConfig(second, false, "second-app", "", "second-nonce", "second-game");
        jdbc.execute("CREATE UNIQUE INDEX uq_api_var ON api_var(scope, name)");
        assertThat(jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='api_var'", String.class))
                .contains("uq_api_var", "uq_api_var_env");

        restartRepository();
        assertEnvironmentVariableIndex();
        assertThat(repo.getVars(first)).isEqualTo(firstVars);
        assertThat(repo.getVars(second)).isEqualTo(secondVars);
        var firstSign = repo.getSignConfig(first);
        assertThat(firstSign.appId()).isEqualTo("upgrade-app");
        assertThat(firstSign.nonce()).isEqualTo("nonce");
        assertThat(firstSign.mgId()).isEqualTo("game");
        assertThat(firstSign.appSecret().equals("fixture-only-signing-value")).isTrue();
        assertThat(repo.getSignConfig(second).appId()).isEqualTo("second-app");

        repo.saveVars(first, List.of(new ApiVariables.Var("shared-endpoint", "first")), List.of(), List.of());
        repo.saveVars(second, List.of(new ApiVariables.Var("shared-endpoint", "second")), List.of(), List.of());
        restartRepository();
        assertEnvironmentVariableIndex();
        assertThat(repo.getVars(first).base()).containsExactly(new ApiVariables.Var("shared-endpoint", "first"));
        assertThat(repo.getVars(second).base()).containsExactly(new ApiVariables.Var("shared-endpoint", "second"));
        assertThat(repo.getSignConfig(first).appSecret().equals("fixture-only-signing-value")).isTrue();
    }

    @Test void existingMultienvironmentDatabaseCanRestartRepeatedly() {
        long first = repo.createEnv("existing-first").id();
        long second = repo.createEnv("existing-second").id();
        var firstVars = repo.saveVars(first, List.of(new ApiVariables.Var("endpoint", "first")),
                List.of(), List.of());
        var secondVars = repo.saveVars(second, List.of(new ApiVariables.Var("endpoint", "second")),
                List.of(), List.of());
        for (int restart = 0; restart < 3; restart++) {
            restartRepository();
            assertEnvironmentVariableIndex();
            assertThat(repo.getVars(first)).isEqualTo(firstVars);
            assertThat(repo.getVars(second)).isEqualTo(secondVars);
        }
    }

    @Test void environmentVariableIndexStillRejectsDuplicatesWithinSameScope() {
        long env = repo.createEnv("uniqueness-check").id();
        restartRepository();
        var original = repo.saveVars(env, List.of(new ApiVariables.Var("endpoint", "base")),
                List.of(new ApiVariables.Var("endpoint", "header")),
                List.of(new ApiVariables.Var("endpoint", "body")));
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO api_var(env_id,scope,name,value) VALUES(?,?,?,?)", env, "base", "endpoint", "duplicate"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("SQLITE_CONSTRAINT_UNIQUE");
        assertThat(repo.getVars(env)).isEqualTo(original);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM api_var WHERE env_id=? AND scope=? AND name=?", Long.class,
                env, "base", "endpoint")).isEqualTo(1L);
    }

    @Test void failedIndexRepairRollsBackDataBackfillAndIndexRemoval() {
        long env = repo.createEnv("invalid-schema-fixture").id();
        repo.saveVars(env, List.of(new ApiVariables.Var("duplicate", "first")), List.of(), List.of());
        // 人为损坏夹具：旧名称索引刻意建成非唯一，才能放入同环境重复行。
        // 正式问题中的旧索引是 UNIQUE；此夹具仅注入新唯一索引创建失败，验证修复事务回滚。
        jdbc.execute("DROP INDEX uq_api_var_env");
        jdbc.execute("CREATE INDEX uq_api_var ON api_var(scope, name)");
        jdbc.update("INSERT INTO api_var(env_id,scope,name,value) VALUES(?,?,?,?)",
                env, "base", "duplicate", "second");
        jdbc.update("INSERT INTO api_var(env_id,scope,name,value) VALUES(NULL,?,?,?)",
                "body", "unassigned", "must-remain-unassigned-on-failure");
        var originalRows = jdbc.queryForList(
                "SELECT id,env_id,scope,name,value,sort_order,updated_at FROM api_var ORDER BY id");
        var originalIndexes = jdbc.queryForList(
                "SELECT name,sql FROM sqlite_master WHERE type='index' AND tbl_name='api_var' ORDER BY name");

        assertThatThrownBy(this::restartRepository)
                .isInstanceOf(RuntimeException.class)
                .hasStackTraceContaining("SQLITE_CONSTRAINT_UNIQUE");

        assertThat(jdbc.queryForList(
                "SELECT id,env_id,scope,name,value,sort_order,updated_at FROM api_var ORDER BY id"))
                .isEqualTo(originalRows);
        assertThat(jdbc.queryForList(
                "SELECT name,sql FROM sqlite_master WHERE type='index' AND tbl_name='api_var' ORDER BY name"))
                .isEqualTo(originalIndexes);
        assertThat(jdbc.queryForObject("SELECT env_id FROM api_var WHERE name='unassigned'", Long.class))
                .isNull();
    }

    @Test void duplicatesLegacyScopesWithoutDroppingVariables() {
        long env = repo.createEnv("legacy").id();
        for (String scope : List.of("BASE", "Header", "body")) {
            jdbc.update("INSERT INTO api_var(env_id,scope,name,value) VALUES(?,?,?,?)",
                    env, scope, "endpoint", scope + "-value");
        }
        var source = repo.getVars(env);
        var copied = repo.getVars(repo.duplicateEnv(env).id());
        assertThat(copied).isEqualTo(source);
    }

    @Test void failedVariableReplacementKeepsAllOriginalScopes() {
        long env = repo.createEnv("variables").id();
        var original = repo.saveVars(env, List.of(new ApiVariables.Var("url", "old-base")),
                List.of(new ApiVariables.Var("header", "old-header")),
                List.of(new ApiVariables.Var("body", "old-body")));
        jdbc.execute("CREATE TRIGGER reject_var BEFORE INSERT ON api_var "
                + "WHEN NEW.name='reject' BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        assertThatThrownBy(() -> repo.saveVars(env,
                List.of(new ApiVariables.Var("url", "new-base")),
                List.of(new ApiVariables.Var("reject", "new-header")), List.of()))
                .isInstanceOf(RuntimeException.class);
        assertThat(repo.getVars(env)).isEqualTo(original);
    }

    @Test void failedSignatureReplacementKeepsOriginalConfiguration() {
        long env = repo.createEnv("signature").id();
        var original = repo.saveSignConfig(env, false, "old-app", "dummy-test-value", "old-nonce", "old-game");
        jdbc.execute("CREATE TRIGGER reject_sign BEFORE INSERT ON api_sign "
                + "WHEN NEW.app_id='reject' BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        assertThatThrownBy(() -> repo.saveSignConfig(env, false, "reject", "", "new-nonce", "new-game"))
                .isInstanceOf(RuntimeException.class);
        assertThat(repo.getSignConfig(env)).isEqualTo(original);
    }

    @Test void savingSignatureWithoutNewKeyPreservesSavedKey() {
        long env = repo.createEnv("keep-key").id();
        repo.saveSignConfig(env, false, "old", "dummy-test-value", "", "");
        var saved = repo.saveSignConfig(env, false, "new", "", "next", "");
        assertThat(saved.appId()).isEqualTo("new");
        assertThat(saved.appSecret()).isEqualTo("dummy-test-value");
    }

    @Test void failedWholeEnvironmentSaveRollsBackSignatureAndVariables() {
        long env = repo.createEnv("atomic-settings").id();
        var originalSign = repo.saveSignConfig(env, false, "old", "dummy-test-value", "", "");
        var originalVars = repo.saveVars(env, List.of(new ApiVariables.Var("url", "old")), List.of(), List.of());
        jdbc.execute("CREATE TRIGGER reject_whole BEFORE INSERT ON api_var "
                + "WHEN NEW.name='reject' BEGIN SELECT RAISE(ABORT, 'test failure'); END");
        assertThatThrownBy(() -> repo.saveEnvironmentSettings(env,
                new ApiSigner.Config(false, "new", "", "", ""),
                new ApiVariables.VarSet(List.of(new ApiVariables.Var("reject", "new")), List.of(), List.of())))
                .isInstanceOf(RuntimeException.class);
        assertThat(repo.getSignConfig(env)).isEqualTo(originalSign);
        assertThat(repo.getVars(env)).isEqualTo(originalVars);
    }
}
