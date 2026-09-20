package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.resilience.LlmResponse;
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
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 端到端集成测试：上传 → 异步分析（ReAct 关闭，直连 LLM 用桩替换）→ 结果落库 → 详情/文档/审计可查。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "docagent.analysis.react-enabled=false")
class DocumentAnalysisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LlmGateway llmGateway;

    private static final String MD = """
            # 项目背景

            本项目是一个文档分析 Agent。

            # 技术方案

            采用 ReAct 循环阅读文档，工具调用统一治理。

            # 风险

            LLM 输出不稳定需要降级兜底。
            """;

    @Test
    void completesWithLlmResultAndAuditTrail() throws Exception {
        String llmJson = """
                {"summary":"文档描述了一个采用 ReAct 的分析 Agent。",
                 "keyPoints":["ReAct 循环阅读","工具统一治理"],
                 "risks":["LLM 输出不稳定"],
                 "suggestions":["保持降级链路"],
                 "citations":[{"sectionId":"sec-2","quote":"采用 ReAct 循环阅读文档"},
                               {"sectionId":"sec-2","quote":"该章节不存在的伪造内容"},
                               {"sectionId":"sec-99","quote":"编造的引用"}]}
                """;
        // 2026-09-18：DIRECT_LLM 主路径改流式 invokeStream，此处的 4 参 invoke stub 同步迁移
        when(llmGateway.invokeStream(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(LlmResponse.of(llmJson, 100, 50, "qwen-plus"));

        String runId = submit(MD, "提炼要点");
        awaitStatus(runId, "COMPLETED");

        MvcResult detail = mockMvc.perform(get("/api/analysis/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        var node = objectMapper.readTree(detail.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(node.get("executionMode").asText()).isEqualTo("LLM");
        assertThat(node.get("skill").asText()).isEqualTo("document-analysis");
        assertThat(node.get("summary").asText()).contains("ReAct");
        // 不存在的 sectionId，以及真实 sectionId 下的伪造 quote 都会被剔除
        assertThat(node.get("result").get("citations").size()).isEqualTo(1);
        assertThat(node.get("result").get("citations").get(0).get("sectionId").asText()).isEqualTo("sec-2");

        // 文档分节视图
        MvcResult docView = mockMvc.perform(get("/api/analysis/runs/" + runId + "/document"))
                .andExpect(status().isOk())
                .andReturn();
        var doc = objectMapper.readTree(docView.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(doc.get("sections").size()).isEqualTo(3);

        // 审计步链：PARSE → DIRECT_LLM → CITATION_VERIFY → REPORT（ReAct 关闭）
        MvcResult audit = mockMvc.perform(get("/api/audit/agent-runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        var steps = objectMapper.readTree(audit.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(steps.toString()).contains("PARSE").contains("DIRECT_LLM")
                .contains("CITATION_VERIFY").contains("REPORT");

        // 历史列表包含该 run
        MvcResult list = mockMvc.perform(get("/api/analysis/runs"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(list.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains(runId);
    }

    @Test
    void fallsBackToRuleModeWhenLlmFails() throws Exception {
        when(llmGateway.invokeStream(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("llm down"));

        String runId = submit(MD, "提炼要点");
        awaitStatus(runId, "COMPLETED");

        MvcResult detail = mockMvc.perform(get("/api/analysis/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        var node = objectMapper.readTree(detail.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(node.get("executionMode").asText()).isEqualTo("FALLBACK");
        assertThat(node.get("summary").asText()).contains("规则模式");
    }

    /**
     * 回归锁（真实故障复现）：模型违反对象契约把对象数组字段输出为字符串数组时，
     * 解析必须容错幸存，而不是整个结果作废落入 FALLBACK。
     * 载体用仍在契约内的 mustHaveCoverage（gaps/interviewQuestions 已下线）。
     */
    @Test
    void toleratesStringArrayCoverageInsteadOfFallingBack() throws Exception {
        String llmJson = """
                {"summary":"候选人具备后端经验。",
                 "keyPoints":["Java 开发"],
                 "risks":[],
                 "citations":[],
                 "mustHaveCoverage":["缺少微服务经验","未说明团队规模"]}
                """;
        when(llmGateway.invokeStream(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(LlmResponse.of(llmJson, 100, 50, "qwen-plus"));

        String runId = submit(MD, "分析匹配度");
        awaitStatus(runId, "COMPLETED");

        MvcResult detail = mockMvc.perform(get("/api/analysis/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        var node = objectMapper.readTree(detail.getResponse().getContentAsString(StandardCharsets.UTF_8));
        // 修复前：字符串数组导致整个结果反序列化失败 → FALLBACK；修复后：容错解析，保持 LLM 模式
        assertThat(node.get("executionMode").asText()).isEqualTo("LLM");
        // LLM 结论幸存（未被 FALLBACK 规则结果替换）
        assertThat(node.get("result").get("summary").asText()).contains("后端经验");
    }

    @Test
    void rejectsUnsupportedFileType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.xls", "application/octet-stream", "x".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/analysis/runs").file(file))
                .andExpect(status().isBadRequest());
    }

    // --- helpers ---

    private String submit(String content, String instruction) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "demo.md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/analysis/runs")
                        .file(file)
                        .param("instruction", instruction))
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("runId").asText();
    }

    private void awaitStatus(String runId, String expected) {
        await().atMost(java.time.Duration.ofSeconds(15)).untilAsserted(() -> {
            MvcResult result = mockMvc.perform(get("/api/analysis/runs/" + runId))
                    .andExpect(status().isOk())
                    .andReturn();
            var node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(node.get("status").asText()).isEqualTo(expected);
        });
    }
}
