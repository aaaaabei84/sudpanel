package com.sudpanel.api;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class ApiWorkspaceTest {
    @TempDir Path directory;
    JdbcTemplate jdbc() { return new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("drafts.db"))); }
    String content(String body) {
        String tab = "00000000-0000-0000-0000-000000000001";
        return "{\"version\":1,\"activeId\":\"" + tab + "\",\"tabs\":[{\"id\":\"" + tab + "\",\"envId\":\"1\",\"draft\":{\"body\":\"" + body + "\",\"headers\":\"fixture-private-token\"}}],\"closed\":[]}";
    }
    @Test void encryptedRestartPreservesExactLongNumbersAndNeverWritesPlaintextToDatabase() throws Exception {
        JdbcTemplate jdbc = jdbc(); ApiWorkspaceRepository repo = new ApiWorkspaceRepository(jdbc);
        String id = UUID.randomUUID().toString(), body = content("12345678901234567890123456789012345678");
        assertThat(repo.read(id).revision()).isZero(); assertThat(repo.save(id, 0, body).revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT payload FROM api_workspace", String.class)).doesNotContain("fixture-private-token", "12345678901234567890");
        assertThat(new String(Files.readAllBytes(directory.resolve("drafts.db")), java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("fixture-private-token");
        assertThat(new ApiWorkspaceRepository(jdbc()).read(id).content()).isEqualTo(body);
        Files.delete(directory.resolve(ApiPlanVault.KEY_FILE_NAME));
        assertThatThrownBy(() -> new ApiWorkspaceRepository(jdbc()).read(id)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(directory.resolve(ApiPlanVault.KEY_FILE_NAME))).isFalse();
        assertThatThrownBy(() -> new ApiWorkspaceRepository(jdbc()).save(UUID.randomUUID().toString(), 0, content("new"))).isInstanceOf(IllegalStateException.class);
        assertThat(Files.exists(directory.resolve(ApiPlanVault.KEY_FILE_NAME))).isFalse();
    }
    @Test void staleWindowCannotOverwriteOrDeleteNewerDraft() {
        ApiWorkspaceRepository repo = new ApiWorkspaceRepository(jdbc()); String id = UUID.randomUUID().toString();
        repo.save(id, 0, content("first")); repo.save(id, 1, content("second"));
        assertThatThrownBy(() -> repo.save(id, 1, content("lost update"))).isInstanceOf(ResponseStatusException.class).satisfies(e -> assertThat(((ResponseStatusException)e).getStatusCode().value()).isEqualTo(409));
        assertThatThrownBy(() -> repo.delete(id, 1)).isInstanceOf(ResponseStatusException.class);
        assertThat(repo.read(id).content()).isEqualTo(content("second"));
        repo.delete(id, 2); assertThat(repo.read(id).revision()).isZero();
    }
    @Test void malformedWorkspaceLeavesExistingDraftUntouched() {
        ApiWorkspaceRepository repo = new ApiWorkspaceRepository(jdbc()); String id = UUID.randomUUID().toString();
        repo.save(id, 0, content("original"));
        for (String invalid : new String[]{"{}", "{\"version\":2}", content("valid") + "{}", content("valid").replace("\"closed\":[]", "\"closed\":[{\"id\":\"00000000-0000-0000-0000-000000000001\",\"envId\":\"1\",\"draft\":{}}]")})
            assertThatThrownBy(() -> repo.save(id, 1, invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repo.read(id).content()).isEqualTo(content("original"));
    }
    @Test void workspaceIsLocalOnlyAndBusinessHttpStatusSurvivesAdvice() {
        var controller = new com.sudpanel.web.ApiWorkspaceController(new ApiWorkspaceRepository(jdbc()));
        var request = new org.springframework.mock.web.MockHttpServletRequest(); request.setRemoteAddr("198.51.100.1");
        assertThatThrownBy(() -> controller.read(UUID.randomUUID().toString(), request)).isInstanceOf(ResponseStatusException.class);
        var response = new com.sudpanel.web.GlobalExceptionHandler().handleStatus(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "版本冲突"));
        assertThat(response.getStatusCode().value()).isEqualTo(409); assertThat(response.getBody()).containsEntry("message", "版本冲突");
    }
}
