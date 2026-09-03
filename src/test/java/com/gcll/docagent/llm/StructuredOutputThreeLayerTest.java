package com.gcll.docagent.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 结构化输出三层链路测试——验证 Prompt 约束 → 解析层容错 → 降层规则兜底的完整链路。
 *
 * <h3>三层机制</h3>
 * <ol>
 *   <li><b>Prompt 层</b>：{@link StructuredOutputParser#formatInstructions(Class)} 自动生成 JSON schema，
 *       注入 prompt 约束 LLM 输出格式</li>
 *   <li><b>解析层</b>：{@link StructuredOutputParser#parse(String, Class)} 容错解析——
 *       剥 markdown 代码块、容许多余文字、Jackson 反序列化</li>
 *   <li><b>降层规则</b>：解析失败抛 {@link LlmParseException}，业务层 catch 后走 fallback 规则服务</li>
 * </ol>
 *
 * <p>本测试验证三层链路的端到端行为，特别是第三层（降层兜底）——
 * 这是面试中"为什么不用 ChatClient.entity()"的核心论据。
 */
class StructuredOutputThreeLayerTest {

    private StructuredOutputParser parser;

    // 模拟业务层的降层规则
    private RuleBasedFallback fallback;

    @BeforeEach
    void setUp() {
        parser = new StructuredOutputParser(new ObjectMapper());
        fallback = new RuleBasedFallback();
    }

    // ========== 第一层：Prompt 约束 ==========

    @Test
    @DisplayName("第一层-Prompt约束：formatInstructions 生成包含目标类字段的 schema")
    void layer1_formatInstructionsContainsSchemaFields() {
        String instructions = parser.formatInstructions(TriageJson.class);

        assertThat(instructions).isNotEmpty();
        assertThat(instructions).contains("issueType");
        assertThat(instructions).contains("priority");
        assertThat(instructions).contains("confidence");
    }

    @Test
    @DisplayName("第一层-Prompt约束：不同目标类生成不同的 schema")
    void layer1_differentClassGeneratesDifferentSchema() {
        String triageSchema = parser.formatInstructions(TriageJson.class);
        String routingSchema = parser.formatInstructions(RoutingJson.class);

        assertThat(triageSchema).isNotEqualTo(routingSchema);
        assertThat(routingSchema).contains("primaryTeam");
    }

    // ========== 第二层：解析层容错 ==========

    @Test
    @DisplayName("第二层-解析层容错：正常 JSON 直接解析")
    void layer2_parsesPlainJson() {
        String json = "{\"issueType\":\"INCIDENT\",\"priority\":\"P0\",\"confidence\":0.9}";
        TriageJson result = parser.parse(json, TriageJson.class);

        assertThat(result.issueType()).isEqualTo("INCIDENT");
        assertThat(result.priority()).isEqualTo("P0");
        assertThat(result.confidence()).isEqualTo(0.9);
    }

    @Test
    @DisplayName("第二层-解析层容错：剥 ```json ``` 代码块后解析")
    void layer2_stripsCodeFenceAndParses() {
        String llmOutput = """
                ```json
                {"issueType":"CONSULT","priority":"P2","confidence":0.7}
                ```
                """;
        TriageJson result = parser.parse(llmOutput, TriageJson.class);

        assertThat(result.issueType()).isEqualTo("CONSULT");
        assertThat(result.priority()).isEqualTo("P2");
    }

    @Test
    @DisplayName("第二层-解析层容错：剥无语言标记的代码块后解析")
    void layer2_stripsPlainCodeFenceAndParses() {
        String llmOutput = """
                ```
                {"issueType":"INCIDENT","priority":"P1","confidence":0.8}
                ```
                """;
        TriageJson result = parser.parse(llmOutput, TriageJson.class);

        assertThat(result.issueType()).isEqualTo("INCIDENT");
    }

    // ========== 第三层：降层规则兜底 ==========

    @Test
    @DisplayName("第三层-降层兜底：非法 JSON 抛 LlmParseException，业务层 catch 后走 fallback")
    void layer3_invalidJsonTriggersFallback() {
        String badJson = "This is not JSON at all";

        // 模拟业务层代码（类似 SpringAiTicketExtractService.extract()）
        TriageJson result;
        try {
            result = parser.parse(badJson, TriageJson.class);
        } catch (LlmParseException ex) {
            // 第三层：降层规则兜底
            result = fallback.triageFallback();
        }

        // fallback 返回规则结果
        assertThat(result.issueType()).isEqualTo("UNKNOWN");
        assertThat(result.priority()).isEqualTo("P3");
        assertThat(result.confidence()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("第三层-降层兜底：部分字段缺失的 JSON 仍可解析（Jackson 宽松模式）")
    void layer3_partialJsonStillParses() {
        String partialJson = "{\"issueType\":\"INCIDENT\"}";
        TriageJson result = parser.parse(partialJson, TriageJson.class);

        assertThat(result.issueType()).isEqualTo("INCIDENT");
        assertThat(result.priority()).isNull(); // 缺失字段为 null
        assertThat(result.confidence()).isEqualTo(0.0); // 基本类型默认值
    }

    @Test
    @DisplayName("第三层-降层兜底：多余字段不报错（Jackson ignoreUnknown）")
    void layer3_extraFieldsIgnored() {
        String jsonWithExtras = "{\"issueType\":\"INCIDENT\",\"priority\":\"P1\",\"confidence\":0.8,\"unknownField\":\"value\"}";
        TriageJson result = parser.parse(jsonWithExtras, TriageJson.class);

        assertThat(result.issueType()).isEqualTo("INCIDENT");
    }

    @Test
    @DisplayName("完整三层链路：Prompt 约束 → LLM 输出（带代码块）→ 解析成功")
    void fullThreeLayerChain_success() {
        // 第一层：生成 Prompt 约束
        String instructions = parser.formatInstructions(TriageJson.class);
        assertThat(instructions).contains("issueType");

        // 模拟 LLM 输出（假设 LLM 遵循了 Prompt 约束，但带了代码块）
        String llmOutput = """
                ```json
                {"issueType":"INCIDENT","priority":"P0","confidence":0.95}
                ```
                """;

        // 第二层：容错解析
        TriageJson result = parser.parse(llmOutput, TriageJson.class);

        // 第三层：解析成功，不需要降层
        assertThat(result.issueType()).isEqualTo("INCIDENT");
        assertThat(result.priority()).isEqualTo("P0");
        assertThat(result.confidence()).isEqualTo(0.95);
    }

    @Test
    @DisplayName("完整三层链路：Prompt 约束 → LLM 输出非法 → 解析失败 → 降层兜底")
    void fullThreeLayerChain_fallback() {
        // 第一层：生成 Prompt 约束
        String instructions = parser.formatInstructions(TriageJson.class);

        // 模拟 LLM 输出不符合约束
        String badLlmOutput = "I think this is an incident with high priority.";

        // 第二层+第三层
        TriageJson result;
        try {
            result = parser.parse(badLlmOutput, TriageJson.class);
        } catch (LlmParseException ex) {
            // 第三层：降层规则兜底
            result = fallback.triageFallback();
        }

        assertThat(result.issueType()).isEqualTo("UNKNOWN");
        assertThat(result.priority()).isEqualTo("P3");
    }

    // === 辅助 ===

    /** 模拟业务层的规则降级服务（如 RuleBasedTicketExtractService / RuleBasedRoutingSuggestionService） */
    static class RuleBasedFallback {
        TriageJson triageFallback() {
            return new TriageJson("UNKNOWN", "P3", 0.0);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TriageJson(String issueType, String priority, double confidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoutingJson(String primaryTeam, double confidence) {
    }
}
