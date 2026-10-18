package com.sudpanel.api.script;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.core.type.TypeReference;
import java.util.*;
import org.junit.jupiter.api.Test;

class ApiScriptSignatureFixturesTest {
    @Test void independentPythonSuccessVectorsRunInTheActualOsWorker() throws Exception {
        var resource = getClass().getResourceAsStream("signature-fixtures.json"); assertThat(resource).isNotNull();
        Map<String,Object> fixture; try (resource) { fixture = ApiScriptSandbox.JSON.readValue(resource, new TypeReference<>() {}); }
        int verified = 0;
        for (var item : (List<Map<String,Object>>)fixture.get("cases")) if ("success".equals(item.get("expectedOutcome"))) {
            var input = new ApiScriptSandboxTest().input(); input.put("environment", item.getOrDefault("environment", Map.of())); input.put("globals", item.getOrDefault("globals", Map.of()));
            input.put("body", item.getOrDefault("body", "")); input.put("headers", List.of());
            var result = ApiScriptSandbox.execute((String)item.get("script"), input, 2000);
            var expected = (Map<String,Object>)item.get("expectedOutputs");
            for (var entry : expected.entrySet()) {
                if (entry.getKey().equals("resolvedHeaders")) continue; // Core, not worker, expands header templates.
                if (entry.getKey().startsWith("globals.")) assertThat(result.globalsWrites()).as(item.get("name").toString()).containsEntry(entry.getKey().substring(8), entry.getValue().toString());
                else assertThat(result.environmentWrites()).as(item.get("name").toString()).containsEntry(entry.getKey(), entry.getValue().toString());
            }
            verified++;
        }
        assertThat(verified).isEqualTo(17);
    }
    @Test void tenFailureVectorsAreRejectedAndConsoleFloodHasNoOutputAmplification() throws Exception {
        var resource = getClass().getResourceAsStream("signature-fixtures.json"); Map<String,Object> fixture;
        try (resource) { fixture = ApiScriptSandbox.JSON.readValue(resource, new TypeReference<>() {}); }
        int rejected = 0;
        for (var item : (List<Map<String,Object>>)fixture.get("cases")) if (!"success".equals(item.get("expectedOutcome"))) {
            var input = new ApiScriptSandboxTest().input(); input.put("environment", item.getOrDefault("environment", Map.of())); input.put("body", item.getOrDefault("body", ""));
            if (item.get("name").equals("output-flood")) {
                try { var result = ApiScriptSandbox.execute((String)item.get("script"), input, 2000); assertThat(result.consoleCount()).isEqualTo(1); assertThat(result.environmentWrites()).isEmpty(); assertThat(result.globalsWrites()).isEmpty(); }
                catch (ApiScriptSandbox.ScriptException e) { assertThat(e.type()).isIn("TIME_LIMIT", "MEMORY_LIMIT", "WORKER_FAILED"); }
            } else {
                assertThatThrownBy(() -> ApiScriptSandbox.execute((String)item.get("script"), input, 300)).as(item.get("name").toString()).isInstanceOf(ApiScriptSandbox.ScriptException.class);
                rejected++;
            }
        }
        assertThat(rejected).isEqualTo(10);
    }
}
