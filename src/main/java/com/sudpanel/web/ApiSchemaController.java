package com.sudpanel.web;

import com.sudpanel.api.ApiJsonSchema;
import com.sudpanel.api.ApiOpenApiSchema;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/apitest/schema")
public class ApiSchemaController {
    public record OpenApiInput(String document,String path,String method,String status) {}
    public record SchemaInput(String schema) {}
    @PostMapping("/validate")
    public java.util.Map<String,Object> validate(@RequestBody SchemaInput input) {
        ApiJsonSchema.validateConfiguration(input.schema());
        return java.util.Map.of("valid",true,"drafts",java.util.List.of("draft-07","2020-12"),"externalResources",false);
    }
    @PostMapping("/openapi")
    public ApiOpenApiSchema.Extracted openapi(@RequestBody OpenApiInput input) {
        return ApiOpenApiSchema.extract(input.document(),input.path(),input.method(),input.status());
    }
}
