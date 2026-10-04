package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.resilience.LlmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 2 S3 端到端：绑定基线的再分析走增量评价——LLM 只重写受变化事实波及的维度，
 * 未重写维度由代码沿用并记入 carriedDimensions（评语级血缘）；上下文账本新增
 * inc-facts / baseline-digest 两段，可从审计端点反查"基线内容为什么进了窗口"。
 * 未绑定基线的运行仍走全量评价（用例内以两次提交各自命中的 prompt 文件钉住分叉）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "docagent.analysis.react-enabled=false")
class ResumeIncrementalEvaluationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LlmGateway llmGateway;

    /** jd-less 五角度契约桩（沿用 ResumeCacheIntegrationTest 结构），两版分析复用同一候选结论。 */
    private static final String ANALYSIS_JSON = """
            {"summary":"5年经验，AI 应用方向。",
             "presentation":{"score":60,"issues":[]},
             "experienceStrength":[
               {"sectionId":"sec-1","entryRef":"主条目","star":{"situation":true,"task":true,"action":true,"result":true},"resultQuality":"BUSINESS","attribution":"OWNER","concern":""}],
             "leverageCards":[],
             "actionableSuggestions":[],
             "keyPoints":["主导AI系统"],
             "risks":[],
             "suggestions":[],
             "citations":[],
             "mustHaveCoverage":[],
             "positioning":{"anchored":true,"currentAnchor":"AI应用工程师","suggestedAnchor":null,"comment":null}}
            """;

    private static final String EVAL_BASE = """
            {"overall":"基线总评：证据完整但缺结果指标。",
             "dimensions":[
               {"dimension":"RESULT_IMPACT","level":"MEDIUM","comment":"缺结果指标","evidence":[],"issueType":"EVIDENCE_INSUFFICIENT"},
               {"dimension":"TECHNICAL_DEPTH","level":"WEAK","comment":"机制未展开","evidence":[],"issueType":"EXPRESSION_PROBLEM"}],
             "strengths":["主导AI系统"],"weaknesses":[]}
            """;

    /** 增量输出只含被重写的 RESULT_IMPACT；TECHNICAL_DEPTH 缺席 → 代码沿用基线。 */
    private static final String EVAL_INC = """
            {"overall":"增量总评：结果指标已落地。",
             "dimensions":[
               {"dimension":"RESULT_IMPACT","level":"MEDIUM","comment":"增量：故障率从2%降到0.5%已写入","evidence":["故障率从2%降到0.5%"],"issueType":"NONE"}],
             "strengths":[],"weaknesses":[]}
            """;

    @Test
    void baseBoundRunUsesIncrementalEvaluationWithCarriedLineage() throws Exception {
        when(llmGateway.invokeStream(anyString(), eq("resume-review.txt"), anyString(), anyString(), any()))
                .thenReturn(LlmResponse.of(ANALYSIS_JSON, 800, 400, "qwen-plus"));
        when(llmGateway.invoke(anyString(), eq("resume-evaluation.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(EVAL_BASE, 600, 400, "qwen-max"));
        when(llmGateway.invoke(anyString(), eq("resume-evaluation-incremental.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(EVAL_INC, 700, 300, "qwen-max"));

        // 第一版：无基线 → 全量评价
        String v1 = submit("# 增量血缘·郭一\n\n## 工作经历\n### 星河科技 2021.07 – 至今\n- 主导 AI 应用开发\n",
                "inc-eval.md", null);
        awaitStatus(v1, "COMPLETED");

        // 第二版：显式绑定基线 → 增量评价
        String v2 = submit("# 增量血缘·郭一\n\n## 工作经历\n### 星河科技 2021.07 – 至今\n- 主导 AI 应用开发，故障率从2%降到0.5%\n",
                "inc-eval-v2.md", v1);
        awaitStatus(v2, "COMPLETED");

        JsonNode evaluation = resultOf(v2).get("result").get("funnelVerdict").get("evaluation");
        assertThat(evaluation.get("overall").asText()).contains("增量总评");
        // 被重写维度取增量文本；缺席维度沿用基线原文并挂 carried 血缘
        JsonNode dims = evaluation.get("dimensions");
        assertThat(dims.get(0).get("comment").asText()).contains("故障率从2%降到0.5%已写入");
        assertThat(dims.get(1).get("comment").asText()).isEqualTo("机制未展开");
        assertThat(evaluation.get("carriedDimensions").toString()).contains("TECHNICAL_DEPTH");
        // strengths 无变化依据 → 沿用基线
        assertThat(evaluation.get("strengths").toString()).contains("主导AI系统");

        // 分叉钉死：全量 prompt 只被 v1 用过一次，增量 prompt 只被 v2 用过一次
        verify(llmGateway, times(1)).invoke(anyString(), eq("resume-evaluation.txt"), anyString(), anyString());
        verify(llmGateway, times(1)).invoke(anyString(), eq("resume-evaluation-incremental.txt"),
                anyString(), anyString());

        // 上下文账本：基线两段上下文的入选理由可从审计端点反查
        MvcResult audit = mockMvc.perform(get("/api/audit/agent-runs/" + v2))
                .andExpect(status().isOk())
                .andReturn();
        String steps = audit.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(steps)
                .contains("EVALUATION_INCREMENTAL")
                .contains("inc-facts(KEPT")
                .contains("baseline-digest(KEPT");
    }

    // --- helpers ---

    private String submit(String content, String fileName, String baseRunId) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, "text/markdown", content.getBytes(StandardCharsets.UTF_8));
        var request = multipart("/api/analysis/runs")
                .file(file)
                .param("instruction", "按方向画像分析这份简历")
                .param("skill", "resume-review")
                .param("targetDirection", "AI应用开发");
        if (baseRunId != null) {
            request = request.param("baseRunId", baseRunId);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isAccepted())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("runId").asText();
    }

    private void awaitStatus(String runId, String expected) {
        await().atMost(java.time.Duration.ofSeconds(20)).untilAsserted(() -> {
            MvcResult result = mockMvc.perform(get("/api/analysis/runs/" + runId))
                    .andExpect(status().isOk())
                    .andReturn();
            var node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(node.get("status").asText()).isEqualTo(expected);
        });
    }

    private JsonNode resultOf(String runId) throws Exception {
        MvcResult detail = mockMvc.perform(get("/api/analysis/runs/" + runId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(detail.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
