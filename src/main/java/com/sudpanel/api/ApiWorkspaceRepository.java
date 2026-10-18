package com.sudpanel.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 工作台草稿保存在本机加密库；浏览器磁盘只保存不含内容的工作区身份。 */
@Repository
public class ApiWorkspaceRepository {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JdbcTemplate jdbc;
    private final ApiPlanVault vault;
    public record Workspace(String id, long revision, String content, String updatedAt) {}
    public ApiWorkspaceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc; this.vault = new ApiPlanVault(jdbc);
        jdbc.execute("CREATE TABLE IF NOT EXISTS api_workspace(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, payload TEXT NOT NULL, updated_at TEXT NOT NULL)");
    }
    public Workspace read(String id) {
        requireId(id);
        return jdbc.query("SELECT revision,payload,updated_at FROM api_workspace WHERE id=?", (row, n) ->
            new Workspace(id, row.getLong(1), vault.decrypt("api-workspace", id, row.getString(2)), row.getString(3)), id)
            .stream().findFirst().orElse(new Workspace(id, 0, null, null));
    }
    public Workspace save(String id, long expectedRevision, String content) {
        requireId(id);
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) throw new IllegalArgumentException("草稿版本无效");
        validate(content);
        String payload = vault.encrypt("api-workspace", id, content), at = Instant.now().toString();
        int changed = expectedRevision == 0
            ? jdbc.update("INSERT OR IGNORE INTO api_workspace(id,revision,payload,updated_at) VALUES(?,1,?,?)", id, payload, at)
            : jdbc.update("UPDATE api_workspace SET revision=revision+1,payload=?,updated_at=? WHERE id=? AND revision=?", payload, at, id, expectedRevision);
        if (changed != 1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "草稿已在其他窗口更新，请保留本地副本后重新读取");
        return new Workspace(id, expectedRevision + 1, content, at);
    }
    public void delete(String id, long expectedRevision) {
        requireId(id);
        if (jdbc.update("DELETE FROM api_workspace WHERE id=? AND revision=?", id, expectedRevision) != 1)
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT, "草稿版本已变化，未删除");
    }
    private static void requireId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException("工作区编号无效");
    }
    private static void validate(String content) {
        if (content == null || content.length() > 2_000_000) throw new IllegalArgumentException("草稿内容不能超过 200 万字符");
        try {
            JsonNode root = JSON.readTree(content);
            if (!root.isObject() || root.path("version").asInt() != 1 || !root.path("tabs").isArray() || root.path("tabs").isEmpty() || root.path("tabs").size() > 12 || !root.path("closed").isArray() || root.path("closed").size() > 12) throw new IllegalArgumentException("工作区需有 1～12 个标签，最多恢复 12 个关闭标签");
            HashSet<String> ids = new HashSet<>(); boolean selected = false;
            for (String section : new String[]{"tabs", "closed"}) for (JsonNode tab : root.path(section)) {
                String id = tab.path("id").asText(); requireId(id);
                if (!ids.add(id) || !tab.path("draft").isObject() || !tab.path("envId").isTextual()) throw new IllegalArgumentException("草稿标签结构无效或编号重复");
                if (section.equals("tabs") && id.equals(root.path("activeId").asText())) selected = true;
            }
            if (!selected) throw new IllegalArgumentException("当前标签不存在");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalArgumentException("工作区草稿不是合法 JSON"); }
    }
}
