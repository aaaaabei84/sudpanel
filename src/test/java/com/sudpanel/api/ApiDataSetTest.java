package com.sudpanel.api;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiDataSetTest {
    @Test void csvHandlesBomQuotedCommaNewlineEscapesAndEmptyValues() {
        var data = ApiDataSet.parse(new ApiDataSet.Input("CSV", "\uFEFFid,名称,note\r\n001,\"中文,值\",\"a\n\"\"b\"\"\"\r\n002,x,\r\n"));
        assertThat(data.columns()).containsExactly("id", "名称", "note");
        assertThat(data.rows()).hasSize(2);
        assertThat(data.rows().getFirst()).containsEntry("id", "\"001\"").containsEntry("名称", "\"中文,值\"").containsEntry("note", "\"a\\n\\\"b\\\"\"");
        assertThat(data.rows().getLast()).containsEntry("note", "\"\"");
    }
    @Test void jsonPreservesLongIntegersNullAndStructuredTypes() {
        var data = ApiDataSet.parse(new ApiDataSet.Input("JSON", "[{\"id\":12345678901234567890123456789012345678,\"nil\":null,\"value\":{\"ok\":true}},{\"id\":2}]"));
        assertThat(data.rows().getFirst()).containsEntry("id", "12345678901234567890123456789012345678").containsEntry("nil", "null").containsEntry("value", "{\"ok\":true}");
        assertThat(data.rows().getLast()).doesNotContainKey("nil");
    }
    @Test void malformedCsvJsonAndLimitsAreRejected() {
        for (String csv : new String[]{"a,a\n1,2", "a,b\n1", "a\n\"bad", "a\n\"bad\"x", "a\n"})
            assertThatThrownBy(() -> ApiDataSet.parse(new ApiDataSet.Input("CSV", csv))).isInstanceOf(IllegalArgumentException.class);
        for (String json : new String[]{"[]", "{}", "[1]", "[{}] {}"})
            assertThatThrownBy(() -> ApiDataSet.parse(new ApiDataSet.Input("JSON", json))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApiDataSet.parse(new ApiDataSet.Input("CSV", "a\n" + "x\n".repeat(1001)))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void dataNamespaceIsTypedAndCannotBeShadowedByEnvironment() {
        var env = new ApiRequestEngine.Environment(1, "test", "now", new ApiVariables.VarSet(java.util.List.of(), java.util.List.of(),
                java.util.List.of(new ApiVariables.Var("data.id", "wrong"))), new ApiSigner.Config(false, null, null, null, null));
        var spec = new ApiSpec(null, "data", "POST", "http://localhost", "{}", "{\"id\":{{data.id}},\"name\":\"{{data.name}}\"}", null,
                "", "", 1000, false, true, null, null, new ApiRequestOptions("JSON", null, null, null));
        var built = ApiRequestEngine.build(spec, env, null, Map.of(), Map.of("id", "12345678901234567890123456789012345678", "name", "\"quote\\\"{{run.fake}}\""));
        assertThat(built.body()).isEqualTo("{\"id\":12345678901234567890123456789012345678,\"name\":\"quote\\\"{{run.fake}}\"}");
        assertThat(built.unresolved()).isEmpty();
    }
}
