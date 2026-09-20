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
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 演示模式端到端：mock-result-enabled 开启后，提交简历不调 LLM 直接返回预置结论，
 * 追问返回预置回答——前端联调/展示不需要 API Key。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "docagent.analysis.react-enabled=false",
        "docagent.analysis.mock-result-enabled=true"
})
class MockResultIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LlmGateway llmGateway;

    @Test
    void returnsPresetResultWithoutLlmAndFollowUpAnswersPreset() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.md", "text/markdown",
                "# 演示简历\n\n## 工作经历\n### 星河科技 2021.07 – 至今\n- 主导AI系统\n".getBytes(StandardCharsets.UTF_8));

        MvcResult submit = mockMvc.perform(multipart("/api/analysis/runs")
                        .file(file)
                        .param("instruction", "按方向画像分析这份简历")
                        .param("skill", "resume-review")
                        .param("targetDirection", "AI应用开发"))
                .andExpect(status().isAccepted())
                .andReturn();
        JsonNode start = objectMapper.readTree(submit.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String runId = start.get("runId").asText();
        // 演示模式同步完成：提交返回时已是终态
        assertThat(start.get("status").asText()).isEqualTo("COMPLETED");

        MvcResult detail = mockMvc.perform(get("/api/analysis/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode run = objectMapper.readTree(detail.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(run.get("executionMode").asText()).isEqualTo("MOCK");
        JsonNode result = run.get("result");
        assertThat(result).isNotNull();
        assertThat(result.get("summary").asText()).contains("演示数据");
        assertThat(result.get("funnelVerdict")).isNotNull();
        assertThat(result.get("funnelVerdict").get("evaluation")).isNotNull();
        assertThat(result.has("suggestions")).isFalse();

        // 追问：预置回答，run 保持 COMPLETED，不触发任何 LLM 调用
        mockMvc.perform(post("/api/analysis/runs/" + runId + "/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"空窗期怎么解释？\"}"))
                .andExpect(status().isAccepted());
        MvcResult messages = mockMvc.perform(get("/api/analysis/runs/" + runId + "/messages"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode msgs = objectMapper.readTree(messages.getResponse().getContentAsString(StandardCharsets.UTF_8));
        JsonNode last = msgs.get(msgs.size() - 1);
        assertThat(last.get("role").asText()).isEqualTo("ASSISTANT");
        assertThat(last.get("content").asText()).contains("演示模式");

        verifyNoInteractions(llmGateway);
    }
}
