package com.edu.agent.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;

/** Strict ReAct envelope; actions still execute explicitly through the existing MCP callbacks. */
public final class AgentLoopOutputSchema {
    private static final JSONObject FINAL_SCHEMA = loadFinalSchema();
    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private AgentLoopOutputSchema() { }

    private static JSONObject loadFinalSchema() {
        try (var stream = new ClassPathResource("prompts/agent-final-answer.schema.json").getInputStream()) {
            return JSON.parseObject(stream.readAllBytes());
        } catch (IOException e) { throw new IllegalStateException("Missing Agent final-answer schema", e); }
    }

    public static ResponseFormat responseFormat(List<ToolCallback> tools) {
        JSONArray variants = new JSONArray();
        for (ToolCallback tool : tools) {
            var definition = tool.getToolDefinition();
            JSONObject args = JSON.parseObject(definition.inputSchema());
            closeObjects(args);
            variants.add(object(JSONObject.of("tool", JSONObject.of("type", "string", "enum", List.of(definition.name())),
                    "args", args)));
        }
        JSONObject action = JSONObject.of("type", "null");
        if (!variants.isEmpty()) {
            // Groq requires concrete direct action variants; an anyOf-only wrapper is rejected.
            JSONArray actionVariants = new JSONArray();
            actionVariants.addAll(variants);
            actionVariants.add(JSONObject.of("type", "array", "items", JSONObject.of("anyOf", variants)));
            actionVariants.add(JSONObject.of("type", "null"));
            action = JSONObject.of("anyOf", actionVariants);
        }
        JSONObject schema = object(JSONObject.of(
                "thought", JSONObject.of("type", "string"),
                "plan", nullable(JSONObject.of("type", "array", "items", JSONObject.of("type", "string"))),
                "action", action,
                "final_answer", nullable(FINAL_SCHEMA)));
        return ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                .jsonSchema(ResponseFormat.JsonSchema.builder().name("educare_react_turn").strict(true)
                        .schema(schema.toJSONString()).build()).build();
    }

    private static JSONObject nullable(JSONObject schema) {
        return JSONObject.of("anyOf", List.of(schema, JSONObject.of("type", "null")));
    }
    private static JSONObject object(JSONObject properties) {
        return JSONObject.of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }
    private static void closeObjects(Object value) {
        if (value instanceof JSONObject schema) {
            schema.remove("$schema");
            if (schema.get("properties") instanceof JSONObject properties) {
                JSONArray originallyRequired = schema.getJSONArray("required");
                for (String key : List.copyOf(properties.keySet())) {
                    if (originallyRequired == null || !originallyRequired.contains(key)) {
                        properties.put(key, nullable(properties.getJSONObject(key)));
                    }
                }
                schema.put("additionalProperties", false);
                schema.put("required", List.copyOf(properties.keySet()));
            }
            schema.values().forEach(AgentLoopOutputSchema::closeObjects);
        } else if (value instanceof JSONArray values) values.forEach(AgentLoopOutputSchema::closeObjects);
    }

    /**
     * Provider strict mode accepts "" for every string, while these fields render as "—" in UI/PDF;
     * rejecting them burned repair rounds without making the report any more truthful.
     */
    private static final Set<String> BLANK_ALLOWED_FIELDS = Set.of("owner", "deadline", "horizon");
    /** Urgency must be justified for medium/high risk (psychological-screening red line), not for low/none. */
    private static final Set<String> BLANK_URGENCY_LEVELS = Set.of("low", "none");
    private static final int MAX_REPORTED_KEYS = 5;

    /** Local schema enforcement also covers compatible providers without constrained decoding. */
    public static JSONObject riskSchema() {
        return JSON.parseObject(FINAL_SCHEMA.getJSONObject("properties").getJSONObject("risk_analysis").toJSONString());
    }
    public static boolean validRisk(JSONObject risk) {
        String violation = violation(riskSchema(), risk, "risk_analysis");
        return violation == null && scoreViolation(risk, "risk_analysis") == null;
    }
    public static JSONObject interventionPlanSchema() {
        return JSON.parseObject(FINAL_SCHEMA.getJSONObject("properties").getJSONObject("intervention_plan").toJSONString());
    }
    public static boolean validInterventionPlan(JSONObject plan) {
        return violation(interventionPlanSchema(), plan, "intervention_plan") == null;
    }

    public static boolean validFinalAnswer(JSONObject answer) {
        return finalAnswerViolation(answer) == null;
    }
    /** First violated path in model-chosen field names only; never echoes field values into logs or prompts. */
    public static String finalAnswerViolation(JSONObject answer) {
        String violation = violation(FINAL_SCHEMA, answer, "final_answer");
        return violation != null ? violation
                : scoreViolation(answer.getJSONObject("risk_analysis"), "final_answer.risk_analysis");
    }
    public static String finalAnswerViolation(String text) {
        JSONObject answer;
        try {
            answer = parseStrictObject(text);
        } catch (Exception e) {
            return "final_answer 不是单个合法 JSON 对象";
        }
        return finalAnswerViolation(answer);
    }
    public static JSONObject parseFinalAnswer(String text) {
        try {
            JSONObject answer = parseStrictObject(text);
            return validFinalAnswer(answer) ? answer : null;
        } catch (Exception ignored) { return null; }
    }
    private static String scoreViolation(JSONObject risk, String path) {
        BigInteger score = new BigInteger(risk.get("risk_score").toString());
        return score.signum() >= 0 && score.compareTo(BigInteger.valueOf(100)) <= 0
                ? null : path + ".risk_score 必须在 0 到 100 之间";
    }
    public static JSONObject parseStrictObject(String text) throws IOException {
        var tree = STRICT_JSON.readTree(text);
        if (tree == null || !tree.isObject()) throw new IOException("Expected JSON object");
        return JSON.parseObject(tree.toString());
    }
    /** Constrained output emits optional fields as null; restore MCP omission/default semantics. */
    public static String toolArguments(ToolCallback tool, String arguments) {
        JSONObject args = JSON.parseObject(arguments);
        JSONObject schema = JSON.parseObject(tool.getToolDefinition().inputSchema());
        removeOptionalNulls(schema, args);
        return args.toJSONString();
    }
    private static void removeOptionalNulls(JSONObject schema, Object value) {
        if (value instanceof JSONObject object && schema.get("properties") instanceof JSONObject properties) {
            JSONArray required = schema.getJSONArray("required");
            for (String key : List.copyOf(object.keySet())) {
                if (object.get(key) == null && properties.containsKey(key)
                        && (required == null || !required.contains(key))) object.remove(key);
                else if (properties.get(key) instanceof JSONObject child) removeOptionalNulls(child, object.get(key));
            }
        } else if (value instanceof JSONArray array && schema.get("items") instanceof JSONObject child) {
            array.forEach(item -> removeOptionalNulls(child, item));
        }
    }
    /** @return null when valid, otherwise a short Chinese description of the first violated path. */
    private static String violation(JSONObject schema, Object value, String path) {
        if (value == null) return path + " 不能为 null";
        String type = schema.getString("type");
        switch (type == null ? "" : type) {
            case "object": {
                if (!(value instanceof JSONObject object)) return path + " 必须是对象";
                JSONObject properties = schema.getJSONObject("properties");
                Set<String> missing = new LinkedHashSet<>(properties.keySet());
                missing.removeAll(object.keySet());
                if (!missing.isEmpty()) return path + " 缺少字段 " + firstKeys(missing);
                Set<String> extra = new LinkedHashSet<>(object.keySet());
                extra.removeAll(properties.keySet());
                if (!extra.isEmpty()) return path + " 含 schema 之外的字段 " + firstKeys(extra);
                for (String key : properties.keySet()) {
                    Object child = object.get(key);
                    if (blankAllowed(key, object, child)) continue;
                    String violation = violation(properties.getJSONObject(key), child, path + "." + key);
                    if (violation != null) return violation;
                }
                return null;
            }
            case "array": {
                if (!(value instanceof JSONArray array)) return path + " 必须是数组";
                for (int i = 0; i < array.size(); i++) {
                    String violation = violation(schema.getJSONObject("items"), array.get(i), path + "[" + i + "]");
                    if (violation != null) return violation;
                }
                return null;
            }
            case "string": {
                if (!(value instanceof String text)) return path + " 必须是字符串";
                if (text.isBlank()) return path + " 不能为空字符串";
                JSONArray allowed = schema.getJSONArray("enum");
                return allowed == null || allowed.contains(text) ? null : path + " 必须是 " + allowed + " 之一";
            }
            case "integer":
                return value instanceof Integer || value instanceof Long || value instanceof BigInteger
                        ? null : path + " 必须是整数";
            default:
                return path + " 的 schema 类型不受支持";
        }
    }
    private static boolean blankAllowed(String key, JSONObject parent, Object value) {
        if (!(value instanceof String text) || !text.isBlank()) return false;
        return BLANK_ALLOWED_FIELDS.contains(key)
                || "urgency_reason".equals(key) && BLANK_URGENCY_LEVELS.contains(parent.getString("risk_level"));
    }
    private static String firstKeys(Set<String> keys) {
        List<String> shown = keys.stream().limit(MAX_REPORTED_KEYS)
                .map(k -> k.length() > 40 ? k.substring(0, 40) + "…" : k).toList();
        return shown + (keys.size() > MAX_REPORTED_KEYS ? " 等 " + keys.size() + " 个" : "");
    }
}
