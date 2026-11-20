package com.edu.agent.core;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AgentLoopOutputSchemaTest {
    private static final String FINAL = """
        {"risk_analysis":{"risk_level":"none","risk_score":0,"primary_risk_type":"无风险",
         "root_cause_analysis":"合成数据正常","key_indicators":["出勤98%"],"recommended_intervention_types":[],"urgency_reason":"无需紧急干预"},
         "intervention_plan":{"report_title":"正常跟进","summary":"合成验证","immediate_actions":[],"long_term_plan":[],
         "talk_outline":"保持沟通","resources":[],"references":[]}}
        """;
    @Test void completeFinalAnswerPassesButMissingWrongAndUnknownFieldsFail() {
        assertNotNull(AgentLoopOutputSchema.parseFinalAnswer(FINAL));
        JSONObject answer = JSON.parseObject(FINAL);
        answer.getJSONObject("risk_analysis").remove("risk_level");
        assertFalse(AgentLoopOutputSchema.validFinalAnswer(answer));
        answer = JSON.parseObject(FINAL); answer.getJSONObject("risk_analysis").put("risk_score", "0");
        assertFalse(AgentLoopOutputSchema.validFinalAnswer(answer));
        answer = JSON.parseObject(FINAL); answer.getJSONObject("risk_analysis").put("risk_score", 101);
        assertFalse(AgentLoopOutputSchema.validFinalAnswer(answer));
        answer = JSON.parseObject(FINAL); answer.getJSONObject("intervention_plan").put("extra", true);
        assertFalse(AgentLoopOutputSchema.validFinalAnswer(answer));
        assertNull(AgentLoopOutputSchema.parseFinalAnswer(FINAL + " {}"));
        assertNull(AgentLoopOutputSchema.parseFinalAnswer(FINAL.replace("\"risk_score\":0", "\"risk_score\":0,\"risk_score\":1")));
    }
    @Test void schemaRetainsTypedToolActionsAndStrictFinalObjects() {
        ToolCallback tool = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("synthetic_tool").description("synthetic")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"studentId\":{\"type\":\"integer\"}},\"required\":[\"studentId\"]}").build();
            }
            public String call(String input) { return "{}"; }
        };
        var format = AgentLoopOutputSchema.responseFormat(List.of(tool));
        assertTrue(format.getJsonSchema().getStrict());
        JSONObject schema = JSON.parseObject(JSON.toJSONString(format.getJsonSchema().getSchema()));
        assertFalse(schema.getBooleanValue("additionalProperties"));
        assertEquals(4, schema.getJSONArray("required").size());
        assertTrue(schema.toJSONString().contains("synthetic_tool"));
        assertTrue(schema.toJSONString().contains("risk_analysis"));
    }
    @Test void singleToolUnionHasConcreteGroqVariantsAndPreservesParallelCalls() {
        ToolCallback tool = fixtureTool("get_profile", """
                {"type":"object","properties":{"studentId":{"type":"integer"}},"required":["studentId"]}
                """);
        JSONObject schema = schema(List.of(tool));
        JSONArray action = schema.getJSONObject("properties").getJSONObject("action").getJSONArray("anyOf");
        // Groq rejects an untyped anyOf-only object as a direct action variant.
        assertEquals("object", action.getJSONObject(0).getString("type"));
        assertEquals(3, action.size());
        assertToolVariant(action.getJSONObject(0), "get_profile", List.of("studentId"));
        assertEquals("integer", action.getJSONObject(0).getJSONObject("properties")
                .getJSONObject("args").getJSONObject("properties").getJSONObject("studentId").getString("type"));
        assertEquals("array", action.getJSONObject(1).getString("type"));
        assertEquals(action.getJSONObject(0), action.getJSONObject(1).getJSONObject("items")
                .getJSONArray("anyOf").getJSONObject(0));
        assertEquals("null", action.getJSONObject(2).getString("type"));
        assertEquals(4, schema.getJSONArray("required").size());
    }
    @Test void multipleToolUnionPreservesEveryTypedArgumentAndNullableDefault() {
        List<ToolCallback> tools = List.of(
                fixtureTool("get_profile", """
                        {"type":"object","properties":{"studentId":{"type":"integer"}},"required":["studentId"]}
                        """),
                fixtureTool("search_policy", """
                        {"type":"object","properties":{"query":{"type":"string"},
                        "top_k":{"type":"integer","default":5}},"required":["query"]}
                        """));
        JSONArray variants = schema(tools).getJSONObject("properties").getJSONObject("action").getJSONArray("anyOf");
        assertEquals("object", variants.getJSONObject(0).getString("type"));
        assertEquals(4, variants.size());
        assertToolVariant(variants.getJSONObject(0), "get_profile", List.of("studentId"));
        assertToolVariant(variants.getJSONObject(1), "search_policy", List.of("query", "top_k"));
        JSONObject args = variants.getJSONObject(1).getJSONObject("properties").getJSONObject("args");
        assertEquals("string", args.getJSONObject("properties").getJSONObject("query").getString("type"));
        JSONArray topK = args.getJSONObject("properties").getJSONObject("top_k").getJSONArray("anyOf");
        assertEquals("integer", topK.getJSONObject(0).getString("type"));
        assertEquals(5, topK.getJSONObject(0).getIntValue("default"));
        assertEquals("null", topK.getJSONObject(1).getString("type"));
        JSONObject parallel = variants.getJSONObject(2);
        assertEquals("array", parallel.getString("type"));
        JSONArray parallelTools = parallel.getJSONObject("items").getJSONArray("anyOf");
        assertEquals(2, parallelTools.size());
        assertEquals(variants.getJSONObject(0), parallelTools.getJSONObject(0));
        assertEquals(variants.getJSONObject(1), parallelTools.getJSONObject(1));
        assertEquals("null", variants.getJSONObject(3).getString("type"));
    }
    @Test void noToolsRestrictsActionToNullAndKeepsStrictFinalAnswer() {
        var format = AgentLoopOutputSchema.responseFormat(List.of());
        assertTrue(format.getJsonSchema().getStrict());
        JSONObject schema = schema(List.of());
        JSONObject action = schema.getJSONObject("properties").getJSONObject("action");
        assertEquals(JSONObject.of("type", "null"), action);
        assertEquals(List.of("thought", "plan", "action", "final_answer").stream().sorted().toList(),
                schema.getJSONArray("required").toJavaList(String.class).stream().sorted().toList());
        JSONObject answer = schema.getJSONObject("properties").getJSONObject("final_answer")
                .getJSONArray("anyOf").getJSONObject(0);
        assertFalse(answer.getBooleanValue("additionalProperties"));
        assertTrue(answer.getJSONArray("required").containsAll(List.of("risk_analysis", "intervention_plan")));
    }
    private static JSONObject schema(List<ToolCallback> tools) {
        return JSON.parseObject(JSON.toJSONString(AgentLoopOutputSchema.responseFormat(tools).getJsonSchema().getSchema()));
    }
    private static ToolCallback fixtureTool(String name, String inputSchema) {
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("Protocol fixture").inputSchema(inputSchema).build();
            }
            public String call(String input) { throw new AssertionError("Schema tests must never execute a tool"); }
        };
    }
    private static void assertToolVariant(JSONObject variant, String name, List<String> argNames) {
        assertFalse(variant.containsKey("anyOf"));
        assertEquals("object", variant.getString("type"));
        assertFalse(variant.getBooleanValue("additionalProperties"));
        assertEquals(List.of("args", "tool"), variant.getJSONArray("required").toJavaList(String.class)
                .stream().sorted().toList());
        assertEquals(List.of(name), variant.getJSONObject("properties").getJSONObject("tool")
                .getJSONArray("enum").toJavaList(String.class));
        JSONObject args = variant.getJSONObject("properties").getJSONObject("args");
        assertEquals("object", args.getString("type"));
        assertFalse(args.getBooleanValue("additionalProperties"));
        assertEquals(argNames.stream().sorted().toList(), args.getJSONArray("required").toJavaList(String.class)
                .stream().sorted().toList());
    }
    @Test void optionalMcpParametersAreNullableAndNullRestoresDefaults() {
        ToolCallback tool = new ToolCallback() {
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("search_psychology_knowledge").description("synthetic")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},\"top_k\":{\"type\":\"integer\",\"default\":5}},\"required\":[\"query\"]}").build();
            }
            public String call(String input) { return input; }
        };
        JSONObject schema = JSON.parseObject(JSON.toJSONString(AgentLoopOutputSchema.responseFormat(List.of(tool)).getJsonSchema().getSchema()));
        JSONObject args = schema.getJSONObject("properties").getJSONObject("action").getJSONArray("anyOf")
                .getJSONObject(0).getJSONObject("properties").getJSONObject("args");
        assertEquals(2, args.getJSONArray("required").size());
        assertEquals("null", args.getJSONObject("properties").getJSONObject("top_k").getJSONArray("anyOf").getJSONObject(1).getString("type"));
        assertEquals("{\"query\":\"synthetic\"}", AgentLoopOutputSchema.toolArguments(tool, "{\"query\":\"synthetic\",\"top_k\":null}"));
        assertTrue(AgentLoopOutputSchema.toolArguments(tool, "{\"query\":\"synthetic\",\"top_k\":3}").contains("\"top_k\":3"));
    }
    @Test void rawEmbeddedFinalRejectsDuplicateAndNonJsonBeforeNormalization() {
        AgentLoop loop = new AgentLoop(org.mockito.Mockito.mock(org.springframework.ai.chat.client.ChatClient.class),
                org.mockito.Mockito.mock(com.edu.agent.config.LangfuseClient.class));
        String envelope = "{\"thought\":\"synthetic\",\"action\":null,\"final_answer\":" + FINAL + "}";
        assertNull(loop.parseLlmJson(envelope).parseError());
        assertNotNull(loop.parseLlmJson(envelope.replace("\"risk_score\":0", "\"risk_score\":0,\"risk_score\":1")).parseError());
        assertNotNull(loop.parseLlmJson(envelope.replace("\"thought\"", "'thought'")).parseError());
        assertNotNull(loop.parseLlmJson(envelope.replace("\"resources\":[],", "\"resources\":[],,")).parseError());
        assertNotNull(loop.parseLlmJson(envelope + " {}" ).parseError());
    }

    private static final String MEDIUM_FINAL = """
        {"risk_analysis":{"risk_level":"medium","risk_score":58,"primary_risk_type":"学业滑坡",
         "root_cause_analysis":"合成数据两门挂科","key_indicators":["挂科:2"],"recommended_intervention_types":["学业辅导"],"urgency_reason":"补考临近"},
         "intervention_plan":{"report_title":"合成方案","summary":"合成摘要",
         "immediate_actions":[{"action":"安排补考辅导","owner":"学业导师","deadline":"2周内"}],
         "long_term_plan":[{"goal":"恢复学业节奏","milestones":["第4周复核"],"horizon":"1个月"}],
         "talk_outline":"先共情再帮扶","resources":["学业中心"],"references":["synthetic-policy-001"]}}
        """;

    @Test void auxiliaryBlankFieldsPassBecauseProviderStrictModeAcceptsThem() {
        JSONObject answer = JSON.parseObject(MEDIUM_FINAL);
        JSONObject action = answer.getJSONObject("intervention_plan").getJSONArray("immediate_actions").getJSONObject(0);
        action.put("owner", ""); action.put("deadline", " ");
        answer.getJSONObject("intervention_plan").getJSONArray("long_term_plan").getJSONObject(0).put("horizon", "");
        assertNull(AgentLoopOutputSchema.finalAnswerViolation(answer));
        assertTrue(AgentLoopOutputSchema.validInterventionPlan(answer.getJSONObject("intervention_plan")));
    }

    @Test void blankUrgencyIsOnlyAcceptedWhenRiskIsLowOrNone() {
        JSONObject none = JSON.parseObject(FINAL);
        none.getJSONObject("risk_analysis").put("urgency_reason", "");
        assertNull(AgentLoopOutputSchema.finalAnswerViolation(none));
        assertTrue(AgentLoopOutputSchema.validRisk(none.getJSONObject("risk_analysis")));

        JSONObject medium = JSON.parseObject(MEDIUM_FINAL);
        medium.getJSONObject("risk_analysis").put("urgency_reason", "");
        assertEquals("final_answer.risk_analysis.urgency_reason 不能为空字符串",
                AgentLoopOutputSchema.finalAnswerViolation(medium));
        assertFalse(AgentLoopOutputSchema.validRisk(medium.getJSONObject("risk_analysis")));
    }

    @Test void coreBlankAndStructuralErrorsNameTheFirstViolatedPathWithoutValues() {
        JSONObject answer = JSON.parseObject(MEDIUM_FINAL);
        answer.getJSONObject("intervention_plan").getJSONArray("immediate_actions").getJSONObject(0).put("action", "");
        assertEquals("final_answer.intervention_plan.immediate_actions[0].action 不能为空字符串",
                AgentLoopOutputSchema.finalAnswerViolation(answer));

        answer = JSON.parseObject(MEDIUM_FINAL);
        answer.getJSONObject("risk_analysis").getJSONArray("key_indicators").add("");
        assertEquals("final_answer.risk_analysis.key_indicators[1] 不能为空字符串",
                AgentLoopOutputSchema.finalAnswerViolation(answer));

        answer = JSON.parseObject(MEDIUM_FINAL);
        answer.getJSONObject("intervention_plan").remove("talk_outline");
        answer.getJSONObject("intervention_plan").remove("references");
        assertEquals("final_answer.intervention_plan 缺少字段 [talk_outline, references]",
                AgentLoopOutputSchema.finalAnswerViolation(answer));

        answer = JSON.parseObject(MEDIUM_FINAL);
        answer.getJSONObject("risk_analysis").put("risk_level", "HIGH");
        assertEquals("final_answer.risk_analysis.risk_level 必须是 [\"high\",\"medium\",\"low\",\"none\"] 之一",
                AgentLoopOutputSchema.finalAnswerViolation(answer));

        answer = JSON.parseObject(MEDIUM_FINAL);
        answer.getJSONObject("risk_analysis").put("risk_score", 101);
        assertEquals("final_answer.risk_analysis.risk_score 必须在 0 到 100 之间",
                AgentLoopOutputSchema.finalAnswerViolation(answer));

        answer = JSON.parseObject(MEDIUM_FINAL);
        answer.getJSONObject("intervention_plan").put("学生姓名张三", "x");
        String extra = AgentLoopOutputSchema.finalAnswerViolation(answer);
        assertEquals("final_answer.intervention_plan 含 schema 之外的字段 [学生姓名张三]", extra);
        assertFalse(extra.contains("\"x\""));

        assertEquals("final_answer 不是单个合法 JSON 对象", AgentLoopOutputSchema.finalAnswerViolation("not json"));
        assertNull(AgentLoopOutputSchema.finalAnswerViolation(MEDIUM_FINAL));
    }
}
