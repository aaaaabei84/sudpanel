package com.sudpanel.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ApiOpenApiTest {
    private ApiInterchange.Preview preview(String value) { return ApiInterchange.preview(new ApiInterchange.Input("OPENAPI", value, null)); }
    private String spec(String paths, String extra) { return "{\"openapi\":\"3.1.1\",\"info\":{\"title\":\"fixture-api\",\"version\":\"1\"},\"servers\":[{\"url\":\"https://example.test/v1\"}],\"paths\":" + paths + extra + "}"; }
    private ApiSpec only(ApiInterchange.Preview value) { assertThat(value.errors()).isEmpty(); assertThat(value.requests()).hasSize(1); return value.requests().getFirst().spec(); }

    @Test void scalarPathQueryHeaderExamplesAndOptionalDisabledAreConverted() {
        var s = only(preview(spec("{\"/users/{id}\":{\"get\":{\"summary\":\"Read user\",\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"required\":true,\"example\":\"中 /+\",\"schema\":{\"type\":\"string\"}},{\"name\":\"q\",\"in\":\"query\",\"example\":\"hello & world\",\"schema\":{\"type\":\"string\"}},{\"name\":\"optional\",\"in\":\"query\",\"schema\":{\"type\":\"string\"}},{\"name\":\"X-Client\",\"in\":\"header\",\"example\":\"fixture\",\"schema\":{\"type\":\"string\"}}]}}}", "")));
        assertThat(s.url()).isEqualTo("https://example.test/v1/users/%E4%B8%AD%20%2F%2B"); assertThat(s.method()).isEqualTo("GET"); assertThat(s.name()).isEqualTo("Read user");
        assertThat(s.headers()).contains("X-Client", "fixture");
        assertThat(s.options().params().getFirst()).isEqualTo(new ApiRequestOptions.Param("q", "hello & world", true));
        assertThat(s.options().params().get(1).enabled()).isFalse();
    }

    @Test void operationParametersOverridePathParametersAndLocalRefResolves() {
        var s = only(preview(spec("{\"/users/{id}\":{\"parameters\":[{\"$ref\":\"#/components/parameters/Id\"}],\"get\":{\"parameters\":[{\"name\":\"id\",\"in\":\"path\",\"example\":12345678901234567890123456789012345678,\"schema\":{\"type\":\"integer\"}}]}}}", ",\"components\":{\"parameters\":{\"Id\":{\"name\":\"id\",\"in\":\"path\",\"example\":1,\"schema\":{\"type\":\"integer\"}}}}")));
        assertThat(s.url()).endsWith("12345678901234567890123456789012345678");
    }

    @Test void localBodyRefGeneratesFiniteJsonExampleAndKeepsHugeNumericExample() {
        var s = only(preview(spec("{\"/users\":{\"post\":{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/User\"}}}}}}}", ",\"components\":{\"schemas\":{\"User\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\",\"example\":12345678901234567890123456789012345678},\"name\":{\"type\":\"string\"},\"serverOnly\":{\"type\":\"string\",\"readOnly\":true}}}}}")));
        assertThat(s.options().bodyType()).isEqualTo("JSON"); assertThat(s.body()).contains("12345678901234567890123456789012345678", "\"name\":\"example\"").doesNotContain("serverOnly");
    }

    @Test void mediaExamplePrecedesSchemaAndNamedLocalExampleWorks() {
        var body = Map.of("content", Map.of("application/json", Map.of("schema", Map.of("type", "object"), "examples", Map.of("fixture", Map.of("$ref", "#/components/examples/Body")))));
        var paths = Map.of("/x", Map.of("post", Map.of("requestBody", body)));
        var s = only(preview(spec(ApiRequestEngine.stringify(paths), ",\"components\":{\"examples\":{\"Body\":{\"value\":{\"id\":38}}}}")));
        assertThat(s.body()).isEqualTo("{\"id\":38}");
    }

    @Test void formExamplesAndExplodedArrayQueryBecomeOrderedFields() {
        String paths = "{\"/login\":{\"post\":{\"parameters\":[{\"name\":\"q\",\"in\":\"query\",\"example\":[\"a\",\"b\"],\"schema\":{\"type\":\"array\"}}],\"requestBody\":{\"content\":{\"application/x-www-form-urlencoded\":{\"example\":{\"username\":\"fixture\",\"tag\":[\"a\",\"b\"]},\"schema\":{\"type\":\"object\"}}}}}}}";
        var s = only(preview(spec(paths, "")));
        assertThat(s.options().bodyType()).isEqualTo("URLENCODED");
        assertThat(s.options().params()).containsExactly(new ApiRequestOptions.Param("q", "a", true), new ApiRequestOptions.Param("q", "b", true));
        assertThat(s.options().fields()).containsExactly(new ApiRequestOptions.Param("username", "fixture", true), new ApiRequestOptions.Param("tag", "a", true), new ApiRequestOptions.Param("tag", "b", true));
    }

    @Test void bearerAndApiKeySecurityProduceExplicitUnresolvedCredentialVariables() {
        String security = ",\"security\":[{\"Bearer\":[]}],\"components\":{\"securitySchemes\":{\"Bearer\":{\"type\":\"http\",\"scheme\":\"bearer\"},\"Key\":{\"type\":\"apiKey\",\"name\":\"X-Custom\",\"in\":\"header\"}}}";
        var p = preview(spec("{\"/a\":{\"get\":{}},\"/b\":{\"post\":{\"security\":[{\"Key\":[]}]}},\"/public\":{\"get\":{\"security\":[]}}}", security));
        assertThat(p.errors()).isEmpty(); assertThat(p.requests()).hasSize(3);
        assertThat(p.requests().getFirst().spec().options().auth().token()).isEqualTo("{{openapi.auth.Bearer.token}}");
        assertThat(p.requests().get(1).spec().options().auth().type()).isEqualTo("API_KEY");
        assertThat(p.requests().get(1).spec().options().auth().keyName()).isEqualTo("X-Custom");
        assertThat(p.requests().get(2).spec().options().auth().type()).isEqualTo("NONE");
    }

    @Test void serverVariableAndOperationServerPrecedenceAndTagsAreRespected() {
        var p = preview(spec("{\"/x\":{\"servers\":[{\"url\":\"https://path.test\"}],\"get\":{\"tags\":[\"users\",\"other\"],\"servers\":[{\"url\":\"https://{stage}.example.test/{version}\",\"variables\":{\"stage\":{\"default\":\"fixture\"},\"version\":{\"default\":\"v2\"}}}]}}}", ""));
        var s = only(p); assertThat(s.url()).isEqualTo("https://fixture.example.test/v2/x"); assertThat(p.requests().getFirst().folder()).isEqualTo("fixture-api / users");
    }

    @Test void relativeServerAndMissingPathExamplesRemainProtectedByStrictVariables() {
        var p = preview(spec("{\"/users/{id}\":{\"get\":{}}}", "").replace("https://example.test/v1", "/v1"));
        var s = only(p); assertThat(s.url()).isEqualTo("{{openapi.base_url}}/v1/users/{{openapi.param.id}}"); assertThat(s.options().strictVariables()).isTrue();
        assertThat(p.warnings()).anyMatch(w -> w.contains("openapi.base_url"));
    }

    @Test void externalRefsAndExternalExamplesAreRejectedWithoutNetworkAccess() {
        for (String body : List.of("{\"$ref\":\"https://example.test/secret.json\"}", "{\"content\":{\"application/json\":{\"examples\":{\"a\":{\"externalValue\":\"https://example.test/file\"}}}}}"))
            assertThat(preview(spec("{\"/x\":{\"post\":{\"requestBody\":" + body + "}}}", "")).errors()).isNotEmpty();
    }

    @Test void recursiveSchemaAndMissingOrCyclicLocalRefsAreRejected() {
        String paths = "{\"/x\":{\"post\":{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/A\"}}}}}}}";
        for (String schema : List.of("{\"type\":\"object\",\"properties\":{\"self\":{\"$ref\":\"#/components/schemas/A\"}}}", "{\"$ref\":\"#/components/schemas/Missing\"}", "{\"$ref\":\"#/components/schemas/A\"}"))
            assertThat(preview(spec(paths, ",\"components\":{\"schemas\":{\"A\":" + schema + "}}")).errors()).isNotEmpty();
    }

    @Test void unsupportedStylesObjectsCookieAndCombinedAuthDoNotSilentlyChangeRequests() {
        for (String param : List.of("{\"name\":\"q\",\"in\":\"query\",\"style\":\"deepObject\",\"example\":{\"x\":1}}", "{\"name\":\"q\",\"in\":\"query\",\"example\":{\"x\":1}}", "{\"name\":\"cookie\",\"in\":\"cookie\",\"example\":\"x\"}"))
            assertThat(preview(spec("{\"/x\":{\"get\":{\"parameters\":[" + param + "]}}}", "")).errors()).isNotEmpty();
        assertThat(preview(spec("{\"/x\":{\"get\":{\"security\":[{\"A\":[],\"B\":[]}]}}}", "")).errors()).isNotEmpty();
    }

    @Test void yamlOldVersionsAndTrailingJsonAreExplicitErrors() {
        assertThat(preview("openapi: 3.1.1\npaths: {}").errors()).anyMatch(e -> e.contains("YAML"));
        assertThat(preview(spec("{}", "").replace("3.1.1", "2.0")).errors()).isNotEmpty();
        assertThat(preview(spec("{}", "") + " {}").errors()).isNotEmpty();
    }

    @Test void localRefUriFragmentPercentEscapesAreDecodedWithoutTreatingPlusAsSpace() {
        var s = only(preview(spec("{\"/x\":{\"post\":{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/Body%20%2B\"}}}}}}}", ",\"components\":{\"schemas\":{\"Body +\":{\"type\":\"object\",\"example\":{\"x\":1}}}}")));
        assertThat(s.body()).isEqualTo("{\"x\":1}");
    }

    @Test void structuralRefSiblingsAreNeverSilentlyIgnored() {
        var p = preview(spec("{\"/x\":{\"post\":{\"requestBody\":{\"content\":{\"application/json\":{\"schema\":{\"$ref\":\"#/components/schemas/A\",\"type\":\"integer\"}}}}}}}", ",\"components\":{\"schemas\":{\"A\":{\"type\":\"string\"}}}"));
        assertThat(p.errors()).anyMatch(e -> e.contains("兄弟字段"));
    }
}
