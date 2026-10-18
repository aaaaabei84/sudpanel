package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sudpanel.web.ApiTestController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ApiPostmanMigrationRegressionTest {
    @TempDir Path dir;

    private JdbcTemplate legacyDatabase(DriverManagerDataSource dataSource) {
        var jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE api_var(id INTEGER PRIMARY KEY AUTOINCREMENT, env_id INTEGER, scope TEXT NOT NULL, name TEXT NOT NULL, value TEXT, sort_order INTEGER, updated_at TEXT)");
        jdbc.execute("CREATE TABLE api_case_group(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, sort_order INTEGER, created_at TEXT, updated_at TEXT, env_id INTEGER)");
        jdbc.update("INSERT INTO api_var(id,env_id,scope,name,value,sort_order,updated_at) VALUES(41,1,'BODY','fixture','legacy-value',7,'unchanged')");
        jdbc.update("INSERT INTO api_case_group(id,name,sort_order,env_id) VALUES(17,'legacy-group',3,1)");
        return jdbc;
    }

    @Test void legacyVariableAndGroupMigrationIsRepeatableAndPreservesExistingRows() {
        var jdbc = legacyDatabase(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("legacy.db")));
        for (int restart = 0; restart < 3; restart++) {
            var repo = new ApiRepository(jdbc);
            assertThat(repo.getVars(1).body()).containsExactly(new ApiVariables.Var("fixture", "legacy-value", false));
            assertThat(repo.getCaseGroup(17).importNote()).isNull();
            assertThat(repo.getCaseGroup(17).importPath()).isNull();
            assertThat(jdbc.queryForObject("SELECT id FROM api_var WHERE name='fixture'", Long.class)).isEqualTo(41);
            assertThat(jdbc.queryForObject("SELECT updated_at FROM api_var WHERE id=41", String.class)).isEqualTo("unchanged");
            assertThat(jdbc.queryForList("SELECT name FROM pragma_table_info('api_var')", String.class)).containsOnlyOnce("secret");
        }
    }

    @Test void metadataMigrationFailureRollsBackItsColumnsAndCanBeRetried() {
        var dataSource = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("rollback.db"));
        var jdbc = legacyDatabase(dataSource);
        var failing = new JdbcTemplate(dataSource) {
            @Override public void execute(String sql) {
                if (sql.startsWith("ALTER TABLE api_case_group ADD COLUMN import_path ")) {
                    throw new DataAccessResourceFailureException("fixture late migration failure");
                }
                super.execute(sql);
            }
        };
        assertThatThrownBy(() -> new ApiRepository(failing)).hasMessageContaining("fixture late migration failure");
        assertThat(jdbc.queryForList("SELECT name FROM pragma_table_info('api_var')", String.class)).doesNotContain("secret");
        assertThat(jdbc.queryForList("SELECT name FROM pragma_table_info('api_case_group')", String.class)).doesNotContain("import_note", "import_path");
        assertThat(jdbc.queryForObject("SELECT value FROM api_var WHERE id=41", String.class)).isEqualTo("legacy-value");
        var recovered = new ApiRepository(jdbc);
        assertThat(recovered.getVars(1).body()).containsExactly(new ApiVariables.Var("fixture", "legacy-value", false));
    }

    @Test void legacyJsonOmissionPreservesSecretWhileExplicitFalseClearsIt() throws Exception {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("flags.db")));
        var repo = new ApiRepository(jdbc);
        repo.saveVars(1, List.of(), List.of(new ApiVariables.Var("credential", "original", true)), List.of());
        var row = new ObjectMapper().readValue("{\"name\":\"credential\",\"value\":\"edited\"}", ApiTestController.VarItem.class);
        assertThat(row.secret()).isNull();
        repo.saveVars(1, List.of(), List.of(new ApiVariables.Var(row.name(), row.value(), row.secret())), List.of());
        assertThat(repo.getVars(1).header()).containsExactly(new ApiVariables.Var("credential", "edited", true));
        repo.saveVars(1, List.of(), List.of(new ApiVariables.Var("credential", "edited", false)), List.of());
        assertThat(new ApiRepository(jdbc).getVars(1).header()).containsExactly(new ApiVariables.Var("credential", "edited", false));
    }
}
