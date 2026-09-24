package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
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
 * 简历档案 + 结论缓存端到端（桩 LLM，缓存显式开启——test profile 默认关闭）。
 * 覆盖：同简历同输入命中缓存零 LLM 调用 / 输入变化重新分析 / resumeId 免上传 /
 * forceRefresh 强制重跑并覆盖缓存 / suggestions 置空 / 档案往返序列化。
 * 每个用例用独立简历内容，避免共享 H2 里的缓存互相命中。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "docagent.analysis.react-enabled=false",
        "docagent.analysis.result-cache-enabled=true"
})
class ResumeCacheIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ResumeCacheService resumeCacheService;

    @Autowired
    private AgentRunRepository agentRunRepository;

    @MockitoBean
    private LlmGateway llmGateway;

    /** jd-less 路径（方向画像）最小五角度契约（沿用 FunnelPipelineIntegrationTest 的桩结构）。 */
    private static final String ANALYSIS_JSON = """
            {"summary":"5年经验，AI 应用方向。",
             "presentation":{"score":60,"issues":[]},
             "experienceStrength":[
               {"sectionId":"sec-1","entryRef":"主条目","star":{"situation":true,"task":true,"action":true,"result":true},"resultQuality":"BUSINESS","attribution":"OWNER","concern":""}],
             "leverageCards":[],
             "actionableSuggestions":[],
             "keyPoints":["主导AI系统"],
             "risks":[],
             "suggestions":["本条应被置空"],
             "citations":[],
             "mustHaveCoverage":[],
             "positioning":{"anchored":true,"currentAnchor":"AI应用工程师","suggestedAnchor":null,"comment":null}}
            """;

    private static final String EVAL_V1 = """
            {"overall":"缓存版本一：证据完整，方向清晰。","dimensions":[],"strengths":["强项"],"weaknesses":["弱项"]}
            """;

    private static final String EVAL_V2 = """
            {"overall":"缓存版本二：重跑后的新结论。","dimensions":[],"strengths":["新强项"],"weaknesses":["新弱项"]}
            """;

    @Test
    void sameResumeAndInputsHitCacheWithoutLlmCalls() throws Exception {
        when(llmGateway.invokeStream(anyString(), eq("resume-review.txt"), anyString(), anyString(), any()))
                .thenReturn(LlmResponse.of(ANALYSIS_JSON, 800, 400, "qwen-plus"));
        when(llmGateway.invoke(anyString(), eq("resume-evaluation.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(EVAL_V1, 600, 400, "qwen-plus"));

        String content = "# 缓存命中测试·陈一\n\n## 工作经历\n### 星河科技 2021.07 – 至今\n- 主导AI系统\n";
        String first = submitFile(content, "cache-hit.md", "AI应用开发", false);
        awaitStatus(first, "COMPLETED");

        JsonNode firstResult = resultOf(first);
        // 结果对象已精简：中间产物与停用字段不再落库
        assertThat(firstResult.get("result").has("suggestions")).isFalse();
        assertThat(firstResult.get("result").has("entities")).isFalse();
        assertThat(firstResult.get("result").has("projectFacts")).isFalse();
        assertThat(firstResult.get("result").has("alignmentMatrix")).isFalse();
        String summary = firstResult.get("result").get("summary").asText();

        // 同简历 + 同输入再提交 → 立即完成、复用结论、不产生新的 LLM 调用
        String second = submitFile(content, "cache-hit.md", "AI应用开发", false);
        JsonNode secondResult = resultOf(second);
        assertThat(secondResult.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(secondResult.get("executionMode").asText()).isEqualTo("CACHE_HIT");
        assertThat(secondResult.get("result").get("summary").asText()).isEqualTo(summary);
        assertThat(secondResult.get("result").get("funnelVerdict")).isNotNull();
        // evidenceAssessments 是简历报告需要展示/定位的证据，不应被 API 管线字段投影剥除。
        assertThat(secondResult.get("result").get("funnelVerdict").has("evidenceAssessments")).isTrue();
        assertThat(secondResult.get("result").has("suggestions")).isFalse();

        // API 投影应保留补充证据及来源字段，而不只是保留空数组字段。
        AgentRun cachedRun = agentRunRepository.findById(second).orElseThrow();
        cachedRun.setResultJson("""
                {"summary":"test","citations":[],"funnelVerdict":{"evidenceAssessments":[
                  {"claim":"支付平台","sectionId":"sec-2","sourceQuote":"故障率从 2% 降低到 0.5%",
                   "evidenceLevel":"L3_RESULT","evidenceFound":["故障率从 2% 降低到 0.5%"],
                   "missingFacts":[],"likelyInterviewQuestions":[],"preparationAdvice":[],
                   "hasOriginalBasis":true,"evidenceSource":"REACT"}
                ]}}
                """);
        agentRunRepository.save(cachedRun);
        JsonNode apiEvidence = resultOf(second).get("result").get("funnelVerdict").get("evidenceAssessments").get(0);
        assertThat(apiEvidence.get("evidenceSource").asText()).isEqualTo("REACT");
        assertThat(apiEvidence.get("sectionId").asText()).isEqualTo("sec-2");

        verify(llmGateway, times(1)).invokeStream(anyString(), eq("resume-review.txt"), anyString(), anyString(), any());
        verify(llmGateway, times(1)).invoke(anyString(), eq("resume-evaluation.txt"), anyString(), anyString());
    }

    @Test
    void changedDirectionRunsNewAnalysis() throws Exception {
        when(llmGateway.invokeStream(anyString(), eq("resume-review.txt"), anyString(), anyString(), any()))
                .thenReturn(LlmResponse.of(ANALYSIS_JSON, 800, 400, "qwen-plus"));
        when(llmGateway.invoke(anyString(), eq("resume-evaluation.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(EVAL_V1, 600, 400, "qwen-plus"));

        String content = "# 换方向测试·陈二\n\n## 技能\nJava / LangChain\n";
        String a = submitFile(content, "direction.md", "AI应用开发", false);
        String b = submitFile(content, "direction.md", "数据工程", false);
        awaitStatus(a, "COMPLETED");
        awaitStatus(b, "COMPLETED");

        // 方向是缓存键的一部分：换方向必须重新分析（两次完整流水线）
        assertThat(resultOf(a).get("executionMode").asText()).isEqualTo("LLM");
        assertThat(resultOf(b).get("executionMode").asText()).isEqualTo("LLM");
        verify(llmGateway, times(2)).invokeStream(anyString(), eq("resume-review.txt"), anyString(), anyString(), any());
    }

    @Test
    void submitByResumeIdWithoutFile() throws Exception {
        when(llmGateway.invokeStream(anyString(), eq("resume-review.txt"), anyString(), anyString(), any()))
                .thenReturn(LlmResponse.of(ANALYSIS_JSON, 800, 400, "qwen-plus"));
        when(llmGateway.invoke(anyString(), eq("resume-evaluation.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(EVAL_V1, 600, 400, "qwen-plus"));

        String content = "# 免上传测试·陈三\n\n## 个人概述\n5年AI应用开发经验。\n";
        awaitStatus(submitFile(content, "by-id.md", "AI应用开发", false), "COMPLETED");

        // 档案列表能取到这份简历，且解析结果完整往返
        MvcResult listResult = mockMvc.perform(get("/api/analysis/resumes"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode resumes = objectMapper.readTree(listResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String resumeId = null;
        for (JsonNode r : resumes) {
            if ("by-id.md".equals(r.get("fileName").asText())) {
                resumeId = r.get("id").asText();
            }
        }
        assertThat(resumeId).as("档案列表应包含刚上传的简历").isNotNull();
        assertThat(resumeCacheService.loadDocument(resumeId))
                .hasValueSatisfying(d -> assertThat(d.fullText()).contains("免上传测试·陈三"));

        // 免上传提交（换方向避免命中缓存）→ 正常走完整分析
        MvcResult submit = mockMvc.perform(multipart("/api/analysis/runs")
                        .param("resumeId", resumeId)
                        .param("instruction", "按方向画像分析这份简历")
                        .param("skill", "resume-review")
                        .param("targetDirection", "数据工程"))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = objectMapper.readTree(submit.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("runId").asText();
        awaitStatus(runId, "COMPLETED");
        assertThat(resultOf(runId).get("executionMode").asText()).isEqualTo("LLM");
    }

    @Test
    void forceRefreshOverwritesCachedConclusion() throws Exception {
        when(llmGateway.invokeStream(anyString(), eq("resume-review.txt"), anyString(), anyString(), any()))
                .thenReturn(LlmResponse.of(ANALYSIS_JSON, 800, 400, "qwen-plus"));
        when(llmGateway.invoke(anyString(), eq("resume-evaluation.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(EVAL_V1, 600, 400, "qwen-plus"))
                .thenReturn(LlmResponse.of(EVAL_V2, 600, 400, "qwen-plus"));

        String content = "# 强制重跑测试·陈四\n\n## 工作经历\n### 云海软件 2020.01 – 至今\n- 参与平台开发\n";
        String first = submitFile(content, "force.md", "AI应用开发", false);
        awaitStatus(first, "COMPLETED");
        assertThat(resultOf(first).get("result").get("summary").asText()).contains("缓存版本一");

        // forceRefresh 跳过缓存强制重跑（评价专调返回第二版）→ 覆盖缓存
        String rerun = submitFile(content, "force.md", "AI应用开发", true);
        awaitStatus(rerun, "COMPLETED");
        assertThat(resultOf(rerun).get("executionMode").asText()).isEqualTo("LLM");
        assertThat(resultOf(rerun).get("result").get("summary").asText()).contains("缓存版本二");

        // 再次普通提交 → 命中的是覆盖后的新结论
        String third = submitFile(content, "force.md", "AI应用开发", false);
        assertThat(resultOf(third).get("executionMode").asText()).isEqualTo("CACHE_HIT");
        assertThat(resultOf(third).get("result").get("summary").asText()).contains("缓存版本二");
        verify(llmGateway, times(2)).invoke(anyString(), eq("resume-evaluation.txt"), anyString(), anyString());
    }

    // --- helpers ---

    private String submitFile(String content, String fileName, String direction, boolean forceRefresh) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", fileName, "text/markdown", content.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/analysis/runs")
                        .file(file)
                        .param("instruction", "按方向画像分析这份简历")
                        .param("skill", "resume-review")
                        .param("targetDirection", direction)
                        .param("forceRefresh", String.valueOf(forceRefresh)))
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
