package com.sudpanel.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline response schema validation. Schema data never grants access to URLs, paths or classpath resources. */
public final class ApiJsonSchema {
    public static final int MAX_SCHEMA_CHARACTERS = 200_000;
    public static final int MAX_ERRORS = 50;
    private static final String DRAFT7 = "http://json-schema.org/draft-07/schema#";
    private static final String DRAFT2020 = "https://json-schema.org/draft/2020-12/schema";
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ApiJsonSchema() {}

    public record Issue(String instancePath, String schemaPath, String message) {}
    public record Result(boolean valid, List<Issue> errors, boolean errorsLimited) {}
    private record Compiled(Schema schema, int expandedNodes) {}

    public static void validateConfiguration(String text) { compile(text, SafeRegex.judgementDeadline()); }

    public static Result inspect(String schemaText, JsonNode instance, long deadline) {
        try {
            Compiled compiled = compile(schemaText, deadline);
            int nodes = count(instance, 0, 64, 10_000, new int[1]);
            if ((long) nodes * compiled.expandedNodes() > 500_000)
                throw new IllegalArgumentException("Schema 与响应组合超过安全计算预算，请缩小校验路径或简化 Schema");
            var errors = compiled.schema().validate(instance, (java.util.function.Consumer<com.networknt.schema.ExecutionContext>) context -> {
                context.executionConfig(c -> c.formatAssertionsEnabled(false));
                context.setErrors(new LimitedErrors(deadline));
            });
            if (System.nanoTime() >= deadline) throw new IllegalArgumentException("Schema 校验预算已用尽，未判定通过");
            return new Result(errors.isEmpty(), errors.stream().limit(MAX_ERRORS).map(ApiJsonSchema::issue).toList(), errors.size() > MAX_ERRORS);
        } catch (ErrorLimit limit) {
            return new Result(false, List.copyOf(limit.errors), true);
        } catch (Exception | StackOverflowError failure) {
            String reason = failure instanceof StackOverflowError ? "Schema 或响应递归过深，校验已停止" : failure.getMessage();
            return new Result(false, List.of(new Issue("", "", reason == null ? "Schema 校验失败" : reason)), false);
        }
    }

    public static String failure(String text, JsonNode instance, long deadline) {
        Result result = inspect(text, instance, deadline);
        if (result.valid()) return null;
        StringBuilder message = new StringBuilder("Schema 不匹配：");
        for (Issue error : result.errors()) {
            if (message.length() > 3000) { message.append("；其余错误省略"); break; }
            message.append("实例 ").append(error.instancePath().isEmpty() ? "/" : error.instancePath())
                    .append("，规则 ").append(error.schemaPath()).append("：").append(error.message()).append("；");
        }
        if (result.errorsLimited()) message.append("错误已达上限，校验未通过");
        return message.toString();
    }

    private static Compiled compile(String text, long deadline) {
        if (text == null || text.isBlank() || text.length() > MAX_SCHEMA_CHARACTERS)
            throw new IllegalArgumentException("Schema 须为非空 JSON，最多20万字符");
        try {
            JsonNode root = JSON.readTree(text);
            if (root == null || (!root.isObject() && !root.isBoolean())) throw new IllegalArgumentException("Schema 须为对象或布尔值");
            String dialect = root.path("$schema").asText(DRAFT2020);
            if (dialect.equals("https://json-schema.org/draft-07/schema#")) dialect = DRAFT7;
            if (!Set.of(DRAFT7, DRAFT2020).contains(dialect)) throw new IllegalArgumentException("仅支持 JSON Schema draft-07 / 2020-12");
            int expanded = checkSchema(root, root, 0, new HashSet<>(), new int[1], true);
            SchemaRegistry registry = SchemaRegistry.withDefaultDialect(dialect.equals(DRAFT7) ? SpecificationVersion.DRAFT_7 : SpecificationVersion.DRAFT_2020_12,
                    builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder().regularExpressionFactory(expression -> {
                        if (expression.isEmpty()) return value -> true;
                        SafeRegex.validate(expression);
                        return value -> {
                            var match = SafeRegex.find(expression, value, deadline);
                            if (match.error() != null) throw new IllegalArgumentException(match.error());
                            return match.matched();
                        };
                    }).build()));
            var metaErrors = registry.getSchema(SchemaLocation.of(dialect)).validate(root);
            if (!metaErrors.isEmpty()) throw new IllegalArgumentException("Schema 配置无效：" + metaErrors.getFirst().getInstanceLocation() + " " + metaErrors.getFirst().getMessage());
            Schema schema = registry.getSchema(SchemaLocation.of("urn:sudpanel:local-schema"), root);
            schema.initializeValidators();
            return new Compiled(schema, expanded);
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception | StackOverflowError error) { throw new IllegalArgumentException("Schema 无法安全解析：" + (error.getMessage() == null ? "递归或引用无效" : error.getMessage())); }
    }

    private static int checkSchema(JsonNode node, JsonNode root, int depth, Set<String> visiting, int[] count, boolean schemaNode) {
        if (depth > 32 || ++count[0] > 2000) throw new IllegalArgumentException("Schema 最多32层、展开后2000节点");
        if (node.isObject()) {
            if (schemaNode && (node.has("$id") || node.has("$dynamicRef") || node.has("$recursiveRef")))
                throw new IllegalArgumentException("离线 Schema 不支持 $id / 动态引用，仅允许本文件 JSON Pointer $ref");
            if (schemaNode && node.has("$ref")) {
                String ref = node.path("$ref").asText();
                if (!ref.startsWith("#/")) throw new IllegalArgumentException("Schema $ref 仅允许 #/ 开头的本文件 JSON Pointer，禁止外部资源");
                JsonNode target = root.at(java.net.URLDecoder.decode(ref.substring(1).replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8));
                if (target.isMissingNode()) throw new IllegalArgumentException("Schema 本地引用不存在：" + ref);
                if (!visiting.add(ref)) throw new IllegalArgumentException("Schema 循环引用未启用，请使用有限结构");
                checkSchema(target, root, depth + 1, visiting, count, true); visiting.remove(ref);
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var field=fields.next();String key=field.getKey();JsonNode value=field.getValue();
                if(schemaNode && key.equals("$ref"))continue;
                if(schemaNode && Set.of("properties","patternProperties","$defs","definitions","dependentSchemas","dependencies").contains(key) && value.isObject()) {
                    if(++count[0]>2000)throw new IllegalArgumentException("Schema 展开超过2000节点");
                    for(JsonNode child:value)checkSchema(child,root,depth+1,visiting,count,true);
                } else if(schemaNode && Set.of("allOf","anyOf","oneOf","prefixItems").contains(key) && value.isArray()) {
                    if(++count[0]>2000)throw new IllegalArgumentException("Schema 展开超过2000节点");
                    for(JsonNode child:value)checkSchema(child,root,depth+1,visiting,count,true);
                } else checkSchema(value,root,depth+1,visiting,count,schemaNode && Set.of("items","contains","additionalProperties","unevaluatedProperties","propertyNames","not","if","then","else","additionalItems","unevaluatedItems","contentSchema").contains(key));
            }
        } else if (node.isArray()) for (JsonNode child : node) checkSchema(child, root, depth + 1, visiting, count, schemaNode);
        return count[0];
    }

    private static int count(JsonNode node, int depth, int maximumDepth, int maximum, int[] count) {
        if (depth > maximumDepth || ++count[0] > maximum || node.isArray() && node.size() > 1000)
            throw new IllegalArgumentException("Schema 响应校验最多64层、1万节点、单数组1000项；请缩小校验路径");
        if (node.isContainerNode()) for (JsonNode child : node) count(child, depth + 1, maximumDepth, maximum, count);
        return count[0];
    }
    private static Issue issue(com.networknt.schema.Error error) { return new Issue(error.getInstanceLocation().toString(), error.getSchemaLocation().toString(), error.getMessage()); }
    private static final class ErrorLimit extends RuntimeException {
        final List<Issue> errors;
        ErrorLimit(List<Issue> errors) { super("Schema 错误达到50条", null, false, false); this.errors = errors; }
    }
    private static final class LimitedErrors extends ArrayList<com.networknt.schema.Error> {
        final long deadline;
        LimitedErrors(long deadline) { this.deadline = deadline; }
        @Override public boolean add(com.networknt.schema.Error error) {
            if (size() >= MAX_ERRORS) throw new ErrorLimit(stream().map(ApiJsonSchema::issue).toList());
            if (System.nanoTime() >= deadline) throw new IllegalArgumentException("Schema 校验预算已用尽，未判定通过");
            return super.add(error);
        }
    }
}
