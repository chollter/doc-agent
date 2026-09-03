package com.gcll.docagent.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredOutputParserTest {

    private final StructuredOutputParser parser = new StructuredOutputParser(new ObjectMapper());

    /** 容错：剥 ```json ``` 代码块后解析（原有用例）。 */
    @Test
    void parsesJsonWithCodeFence() {
        String json = """
                ```json
                {"issueType":"INCIDENT","confidence":0.8}
                ```
                """;
        TestJson result = parser.parse(json, TestJson.class);
        assertThat(result.issueType()).isEqualTo("INCIDENT");
        assertThat(result.confidence()).isEqualTo(0.8);
    }

    /** 优化2：formatInstructions 自动生成包含字段名的 schema 提示。 */
    @Test
    void formatInstructionsContainsSchemaFields() {
        String instructions = parser.formatInstructions(TestJson.class);

        assertThat(instructions).isNotEmpty();
        // 应包含字段名的 schema 信息（自动生成，替代手写）
        assertThat(instructions).contains("issueType");
        assertThat(instructions).contains("confidence");
    }

    /** 容错：纯 JSON（无代码块）正常解析。 */
    @Test
    void parsesPlainJson() {
        TestJson result = parser.parse("{\"issueType\":\"CONSULT\",\"confidence\":0.5}", TestJson.class);
        assertThat(result.issueType()).isEqualTo("CONSULT");
    }

    /** 容错：非法 JSON 抛 LlmParseException（调用方应 catch 降级）。 */
    @Test
    void invalidJsonThrowsParseException() {
        assertThatThrownBy(() -> parser.parse("not a json at all", TestJson.class))
                .isInstanceOf(LlmParseException.class);
    }

    private record TestJson(String issueType, double confidence) {
    }
}
