package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** EVAL 回放不调用 LLM，且在运行记录中与前端 MOCK 明确区分。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "docagent.analysis.execution-mode=EVAL",
        "docagent.analysis.mock-result-enabled=false",
        "docagent.analysis.react-enabled=false"
})
class EvalReplayIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean LlmGateway llmGateway;

    @Test
    void replaysStableResultWithoutCallingProvider() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "eval.md", MediaType.TEXT_MARKDOWN_VALUE,
                "# Eval\n\n## Experience\nJava backend".getBytes(StandardCharsets.UTF_8));

        String body = mockMvc.perform(multipart("/api/analysis/runs")
                        .file(file).param("skill", "resume-review"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String runId = objectMapper.readTree(body).get("runId").asText();

        JsonNode detail = objectMapper.readTree(mockMvc.perform(get("/api/analysis/runs/" + runId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("executionMode").asText()).isEqualTo("EVAL_REPLAY");
        assertThat(detail.get("result").get("summary").asText()).contains("演示数据");
        verifyNoInteractions(llmGateway);
    }
}
