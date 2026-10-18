package com.sudpanel.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;

class ApiJsonSchemaTest {
    private final ObjectMapper json = new ObjectMapper();
    private ApiJsonSchema.Result validate(String schema, String instance) throws Exception {
        return ApiJsonSchema.inspect(schema, json.readTree(instance), SafeRegex.judgementDeadline());
    }
    @Test void requiredTypeEnumArrayAndAdditionalPropertiesHaveLocations() throws Exception {
        String schema = """
          {"type":"object","required":["id","values"],"properties":{"id":{"type":"integer","enum":[12345678901234567890123456789012345678]},"values":{"type":"array","items":{"type":"string"},"minItems":1}},"additionalProperties":false}
          """;
        assertThat(validate(schema,"{\"id\":12345678901234567890123456789012345678,\"values\":[\"ok\"]}").valid()).isTrue();
        var result = validate(schema,"{\"values\":[42],\"extra\":true}");
        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(result.errors()).allMatch(e -> !e.schemaPath().isBlank());
        assertThat(result.errors()).anyMatch(e -> e.instancePath().contains("values"));
        assertThat(validate(schema,"{\"id\":12345678901234567890123456789012345679,\"values\":[\"ok\"]}").valid()).isFalse();
    }
    @Test void localRefsDraft7AndOneOfAreActuallyValidated() throws Exception {
        String schema = """
           {"$schema":"http://json-schema.org/draft-07/schema#","definitions":{"value":{"oneOf":[{"type":"integer"},{"type":"string","minLength":2}]}},"$ref":"#/definitions/value"}
           """;
        assertThat(validate(schema,"42").valid()).isTrue();
        assertThat(validate(schema,"\"ab\"").valid()).isTrue();
        assertThat(validate(schema,"true").valid()).isFalse();
        assertThat(validate("false","null").valid()).isFalse();
    }
    @Test void invalidSchemaAndExternalResourcesFailBeforeAnyNetworkOrExecution() {
        for (String schema : List.of("{\"type\":\"madeup\"}","{\"required\":42}","[]","{} {}",
                "{\"$ref\":\"http://127.0.0.1:1/schema\"}","{\"$ref\":\"file:///tmp/schema\"}",
                "{\"$ref\":\"classpath:/schema\"}","{\"$ref\":\"#/missing\"}","{\"$ref\":\"#/$defs/a\",\"$defs\":{\"a\":{\"$ref\":\"#/$defs/a\"}}}"))
            assertThatThrownBy(() -> ApiJsonSchema.validateConfiguration(schema)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void errorsAndSizeAreBoundedWithoutFalsePass() throws Exception {
        var instance = json.createArrayNode(); for (int i=0;i<100;i++) instance.add(i);
        var result = ApiJsonSchema.inspect("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}", instance, SafeRegex.judgementDeadline());
        assertThat(result.valid()).isFalse(); assertThat(result.errors()).hasSize(50); assertThat(result.errorsLimited()).isTrue();
        assertThatThrownBy(() -> ApiJsonSchema.validateConfiguration(" ".repeat(200_001))).isInstanceOf(IllegalArgumentException.class);
        instance.addAll(json.createArrayNode().addAll(java.util.Collections.nCopies(1001,json.getNodeFactory().nullNode())));
        assertThat(ApiJsonSchema.inspect("true",instance,SafeRegex.judgementDeadline()).valid()).isFalse();
    }
    @Test void schemaRuleUsesTheSelectedPathAndLiteralSchemaText() throws Exception {
        String text = "[{\"path\":\"data\",\"op\":\"schema\",\"expect\":\"{\\\"type\\\":\\\"object\\\",\\\"required\\\":[\\\"x\\\"]}\"}]";
        ApiBodyAssert.validateRules(text);
        assertThat(ApiBodyAssert.judgeAll("{\"data\":{\"x\":1}}",ApiBodyAssert.parse(text)).getFirst().ok()).isTrue();
        assertThat(ApiBodyAssert.judgeAll("{\"data\":{}}",ApiBodyAssert.parse(text)).getFirst().ok()).isFalse();
        String literal = "{\"const\":\"{{not-a-variable}}\"}";
        var environment = new ApiRequestEngine.Environment(1,"test","now",ApiVariables.VarSet.empty(),new ApiSigner.Config(false,null,null,null,null));
        var rules = ApiRequestEngine.assertionRules(ApiRequestEngine.stringify(List.of(new ApiBodyAssert.Rule("","schema",literal))),environment,Map.of(),Map.of());
        assertThat(rules.getFirst().expect()).isEqualTo(literal);
    }
    @Test void pathologicalRegexCannotHoldTheValidator() throws Exception {
        long start=System.nanoTime();
        var result=validate("{\"pattern\":\"(a+)+$\"}",json.writeValueAsString("a".repeat(200_000)+"!"));
        assertThat(result.valid()).isFalse();
        assertThat(java.time.Duration.ofNanos(System.nanoTime()-start)).isLessThan(java.time.Duration.ofSeconds(2));
        assertThat(result.errors().getFirst().message()).contains("ms");
    }
    @Test void literalRefAndIdPropertiesAreDataRatherThanResourceAccess() throws Exception {
        String schema="{\"const\":{\"$ref\":\"https://example.invalid/literal\",\"$id\":\"literal\"}}";
        assertThat(validate(schema,"{\"$ref\":\"https://example.invalid/literal\",\"$id\":\"literal\"}").valid()).isTrue();
        assertThat(validate("{\"$defs\":{\"a+b\":{\"type\":\"integer\"}},\"$ref\":\"#/$defs/a+b\"}","42").valid()).isTrue();
        assertThat(ApiBodyAssert.judgeAll("plain text",List.of(new ApiBodyAssert.Rule("","schema","{\"type\":\"string\"}"))).getFirst().ok()).isFalse();
    }
}
