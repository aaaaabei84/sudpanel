package com.sudpanel.api;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class ApiOpenApiSchemaTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void selectsSpecificResponseAndResolvesLocalReferencesWithoutRounding() throws Exception {
        String document="""
          {"openapi":"3.1.0","paths":{"/round":{"get":{"responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Round"}}}},"404":{"content":{"application/json":{"schema":{"type":"null"}}}}}}}},"components":{"schemas":{"Round":{"type":"object","required":["id"],"properties":{"id":{"const":12345678901234567890123456789012345678}}}}}}
          """;
        var extracted=ApiOpenApiSchema.extract(document,"/round","GET","200");
        assertThat(extracted.schema()).contains("12345678901234567890123456789012345678");
        assertThat(ApiJsonSchema.inspect(extracted.schema(),json.readTree("{\"id\":12345678901234567890123456789012345678}"),SafeRegex.judgementDeadline()).valid()).isTrue();
        assertThat(ApiJsonSchema.inspect(extracted.schema(),json.readTree("{\"id\":12345678901234567890123456789012345679}"),SafeRegex.judgementDeadline()).valid()).isFalse();
        assertThat(ApiOpenApiSchema.extract(document,"/round","get","404").schema()).contains("null");
    }
    @Test void openapi30NullableBoundsAndLiteralPropertyNamesArePreserved() throws Exception {
        String document="""
          {"openapi":"3.0.3","paths":{"/item":{"get":{"responses":{"200":{"content":{"application/json":{"schema":{"type":"object","required":["nullable","score"],"properties":{"nullable":{"type":"string"},"score":{"type":"number","minimum":1,"exclusiveMinimum":true,"nullable":true}}}}}}}}}}}
          """;
        var extracted=ApiOpenApiSchema.extract(document,"/item","get","200");
        assertThat(ApiJsonSchema.inspect(extracted.schema(),json.readTree("{\"nullable\":\"literal\",\"score\":null}"),SafeRegex.judgementDeadline()).valid()).isTrue();
        assertThat(ApiJsonSchema.inspect(extracted.schema(),json.readTree("{\"nullable\":42,\"score\":1}"),SafeRegex.judgementDeadline()).valid()).isFalse();
    }
    @Test void nullableOnlyWidensExplicitTypeAndKeepsEnumAndCompositionConstraints() throws Exception {
        String base="{\"openapi\":\"3.0.3\",\"paths\":{\"/x\":{\"get\":{\"responses\":{\"200\":{\"content\":{\"application/json\":{\"schema\":%s}}}}}}}}";
        for(String schema:java.util.List.of("{\"type\":\"string\",\"nullable\":true,\"enum\":[\"ok\"]}","{\"nullable\":true,\"allOf\":[{\"type\":\"string\"}]}")){
            String extracted=ApiOpenApiSchema.extract(base.formatted(schema),"/x","get","200").schema();
            assertThat(ApiJsonSchema.inspect(extracted,json.readTree("null"),SafeRegex.judgementDeadline()).valid()).isFalse();
            assertThat(ApiJsonSchema.inspect(extracted,json.readTree("\"ok\""),SafeRegex.judgementDeadline()).valid()).isTrue();
        }
        String allowed=ApiOpenApiSchema.extract(base.formatted("{\"type\":\"string\",\"nullable\":true,\"enum\":[\"ok\",null]}"),"/x","get","200").schema();
        assertThat(ApiJsonSchema.inspect(allowed,json.readTree("null"),SafeRegex.judgementDeadline()).valid()).isTrue();
    }
    @Test void responseRequiredSkipsWriteOnlyPropertiesAndKeepsReadOnlyPropertiesIncludingRefs() throws Exception {
        String document="""
            {"openapi":"3.0.3","paths":{"/x":{"get":{"responses":{"200":{"content":{"application/json":{"schema":{"type":"object","required":["input","output"],"properties":{"input":{"$ref":"#/components/schemas/Input"},"output":{"type":"string","readOnly":true}}}}}}}}}},"components":{"schemas":{"Input":{"type":"string","writeOnly":true}}}}
            """;
        String schema=ApiOpenApiSchema.extract(document,"/x","get","200").schema();
        assertThat(ApiJsonSchema.inspect(schema,json.readTree("{\"output\":\"ok\"}"),SafeRegex.judgementDeadline()).valid()).isTrue();
        assertThat(ApiJsonSchema.inspect(schema,json.readTree("{}"),SafeRegex.judgementDeadline()).valid()).isFalse();
    }
    @Test void missingOperationResponseAndExternalOrCyclicRefsAreReadableFailures() {
        String base="{\"openapi\":\"3.1.0\",\"paths\":{\"/x\":{\"get\":{\"responses\":{\"200\":{\"content\":{\"application/json\":{\"schema\":%s}}}}}}}}";
        for(String schema:java.util.List.of("{\"$ref\":\"https://example.invalid/schema\"}","{\"$ref\":\"file:///tmp/schema\"}","{\"$ref\":\"#/components/missing\"}"))
            assertThatThrownBy(()->ApiOpenApiSchema.extract(base.formatted(schema),"/x","get","200")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->ApiOpenApiSchema.extract(base.formatted("{}"),"/missing","get","200")).hasMessageContaining("路径");
        assertThatThrownBy(()->ApiOpenApiSchema.extract(base.formatted("{}"),"/x","get","500")).hasMessageContaining("响应状态");
    }
}
