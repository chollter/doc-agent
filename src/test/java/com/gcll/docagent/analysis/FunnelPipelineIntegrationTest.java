package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.JsonNode;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 漏斗链路端到端集成测试（桩 LLM，无需 API Key）。
 * <p>P12 主链路此前没有任何测试走过全链：实体抽取 → 红旗 → 方向画像 → v2 契约解析 →
 * FunnelVerdict 组装 → 落库 → API 返回。v2 契约的 JSON 字段名或枚举接线笔误，
 * 在这里立即暴露，而不是等到有 Key 跑评测时才暴露。
 * <p>抽取走 LlmGateway 的 2 参 invoke，主分析走 4 参 invoke——两个桩分别打。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "docagent.analysis.react-enabled=false")
class FunnelPipelineIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LlmGateway llmGateway;

    /** 简历正文：时间线与实体桩一致（含 9 个月空窗）；只写"检索增强"不写 RAG（触发词汇缺口）；含手机号。 */
    private static final String RESUME_MD = """
            # 陈远航 — 后端工程师

            电话 13800138000，email chenyh@example.com

            ## 个人概述
            7年后端与 AI 应用开发经验。

            ## 工作经历
            ### 星河科技 · AI应用工程师 2021.07 – 至今
            - 主导检索增强知识库问答系统，日均调用 12 万次

            ### 云海软件 · 后端工程师 2019.07 – 2020.10
            - 参与订单中心开发

            ## 技能
            Java / Spring Boot / LangChain
            """;

    /** 实体抽取桩：两段工作时间（中间 9 个月空窗 → TIMELINE_GAP HIGH）+ 教育/技能/声明/指标。 */
    private static final String ENTITIES_JSON = """
            {"entities":[
              {"type":"TIME_PERIOD","value":"2021.07-至今","context":"星河科技","attributes":{"kind":"work"}},
              {"type":"TIME_PERIOD","value":"2019.07-2020.10","context":"云海软件","attributes":{"kind":"work"}},
              {"type":"TIME_PERIOD","value":"2015.09-2019.06","context":"华南理工","attributes":{"kind":"education"}},
              {"type":"EDUCATION","value":"华南理工 软件工程 本科","context":"教育","attributes":{"school":"华南理工","degree":"本科","major":"软件工程","year":"2019"}},
              {"type":"SKILL","value":"Java","context":"技能"},
              {"type":"SKILL","value":"LangChain","context":"技能"},
              {"type":"CLAIM","value":"主导检索增强知识库问答系统","context":"工作"},
              {"type":"METRIC","value":"日均调用 12 万次","context":"工作"},
              {"type":"WORK_ENTRY","value":"星河科技 AI应用工程师 2021.07-至今 主导检索增强问答系统","context":"工作","attributes":{"kind":"work","metricCount":"1"}}
            ]}
            """;

    /** 主分析桩：v2 五角度契约。要求文本故意只给 id（验证以画像定义合并）。 */
    private static final String ANALYSIS_JSON = """
            {"summary":"7年经验，AI 应用方向。",
             "presentation":{"score":62,"issues":["sec-2: 时态混乱"]},
             "experienceStrength":[
               {"sectionId":"sec-2","entryRef":"星河科技 2021.07-至今","star":{"situation":true,"task":true,"action":true,"result":true},"resultQuality":"BUSINESS","attribution":"LEAD","concern":""},
               {"sectionId":"sec-3","entryRef":"云海软件 2019.07-2020.10","star":{"situation":true,"task":true,"action":true,"result":false},"resultQuality":"NONE","attribution":"PARTICIPANT","concern":"无结果佐证"}],
             "leverageCards":[
               {"kind":"STRENGTH","point":"主导检索增强问答系统","sectionId":"sec-2","likelyQuestion":"召回率怎么优化","prepHint":"准备分块与重排数据"},
               {"kind":"RISK","point":"9个月空窗","sectionId":"sec-3","likelyQuestion":"解释这段空窗","defenseStrategy":"说明原因与期间产出"}],
             "actionableSuggestions":[
               {"severity":"HIGH","target":"云海经历","sectionId":"sec-3","before":"参与订单中心开发","after":"负责订单模块重构，QPS 800→2000","reason":"补 Result 与归因"}],
             "keyPoints":["主导检索增强问答"],
             "risks":["时间空窗"],
             "suggestions":["补结果量化"],
             "citations":[{"sectionId":"sec-2","quote":"主导检索增强知识库问答系统"}],
             "matchDimensions":[],"gaps":[],"interviewQuestions":[],
             "mustHaveCoverage":[
               {"requirementId":"llm-integration","status":"MET","evidence":"封装 LLM 网关","sectionId":"sec-2"},
               {"requirementId":"shipped-app","status":"MISSING","evidence":null,"sectionId":null}],
             "variantFit":[{"variantId":"agent-eng","fit":"HIGH","reason":"编排经验"}],
             "positioning":{"anchored":false,"currentAnchor":"后端工程师","suggestedAnchor":"AI 应用工程师（RAG 方向）","comment":"建议锚定"}}
            """;

    @Test
    void assemblesFunnelVerdictEndToEnd() throws Exception {
        when(llmGateway.invoke(anyString(), anyString()))
                .thenReturn(LlmResponse.of(ENTITIES_JSON, 200, 100, "qwen-plus"));
        when(llmGateway.invoke(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(LlmResponse.of(ANALYSIS_JSON, 800, 400, "qwen-plus"));

        String runId = submit(RESUME_MD, "按方向画像分析这份简历", "AI应用开发");
        awaitStatus(runId, "COMPLETED");
        JsonNode result = resultOf(runId);

        assertThat(path(result, "executionMode").asText()).isEqualTo("LLM");
        JsonNode verdict = result.get("result").get("funnelVerdict");
        assertThat(verdict).isNotNull();

        // ① 红旗：两段工作时间实体间 9 个月空窗 → TIMELINE_GAP / HIGH（代码层，非 LLM）
        JsonNode flags = verdict.get("redFlags");
        assertThat(flags.size()).isGreaterThanOrEqualTo(1);
        assertThat(flags.toString()).contains("TIMELINE_GAP");
        assertThat(flags.toString()).contains("HIGH");

        // ② 方向画像模式 + 词汇diff：正文写"检索增强"未写 RAG → 建议 RAG
        assertThat(path(verdict, "matchMode").asText()).isEqualTo("DIRECTION");
        assertThat(path(verdict, "archetypeId").asText()).isEqualTo("ai-app-dev");
        JsonNode vocab = verdict.get("vocabularyGaps");
        assertThat(vocab.size()).isEqualTo(1);
        assertThat(path(vocab.get(0), "term").asText()).isEqualTo("RAG");
        assertThat(path(vocab.get(0), "usedSynonym").asText()).contains("检索增强");

        // ③ 覆盖：要求文本以画像定义合并（LLM 只给了 id），status 保留
        JsonNode coverage = verdict.get("mustHaveCoverage");
        assertThat(coverage.size()).isEqualTo(2);
        assertThat(path(coverage.get(0), "requirement").asText()).contains("LLM API");
        assertThat(path(coverage.get(0), "status").asText()).isEqualTo("MET");
        assertThat(path(coverage.get(1), "status").asText()).isEqualTo("MISSING");

        // ④ 子方向：名称从画像合并（LLM 只给 variantId）
        assertThat(path(verdict.get("variantFit").get(0), "name").asText()).isEqualTo("Agent 开发");

        // ⑤ 强度：1/2 有结果（BUSINESS/LEAD）+ 1/2 无结果（NONE/PARTICIPANT）→ MIXED
        assertThat(path(path(verdict, "strength"), "band").asText()).isEqualTo("MIXED");
        assertThat(path(path(verdict, "strength"), "entryCount").asInt()).isEqualTo(2);

        // ⑥ 表达：62 分 → C 档（代码档位映射）
        assertThat(path(path(verdict, "presentation"), "score").asInt()).isEqualTo(62);
        assertThat(path(path(verdict, "presentation"), "band").asText()).isEqualTo("C");

        // ⑦ 定位与杠杆卡
        assertThat(path(path(verdict, "positioning"), "anchored").asBoolean()).isFalse();
        assertThat(verdict.get("leverageCards").size()).isEqualTo(2);

        // ⑧ 兼容分映射（确定性）：MIXED(65)×0.4 + 62×0.3 + PARTIAL(70)×0.3 - HIGH红旗10 = 55
        assertThat(scoreOverallOf(runId)).isEqualTo(55);
    }

    @Test
    void marksDegradedAndSuppressesScoreWhenExtractionFails() throws Exception {
        // 抽取挂掉（2 参 invoke 抛异常）→ 降级路径；主分析（4 参）正常返回
        when(llmGateway.invoke(anyString(), anyString()))
                .thenThrow(new RuntimeException("extraction down"));
        when(llmGateway.invoke(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(LlmResponse.of(ANALYSIS_JSON, 800, 400, "qwen-plus"));

        String runId = submit(RESUME_MD, "按方向画像分析这份简历", "AI应用开发");
        awaitStatus(runId, "COMPLETED");
        JsonNode result = resultOf(runId);

        JsonNode verdict = result.get("result").get("funnelVerdict");
        assertThat(verdict).isNotNull();
        // P11 缺陷回归锁：降级必须显式标记，且不出分——"看起来正常的低分"曾掩盖抽取故障
        assertThat(path(verdict, "analysisDegraded").asBoolean()).isTrue();
        assertThat(path(path(verdict, "strength"), "band").asText()).isEqualTo("WEAK");
        assertThat(path(verdict, "matchMode").asText()).isEqualTo("NONE");
        assertThat(scoreOverallOf(runId)).isEqualTo(0);
    }

    // --- helpers ---

    private String submit(String content, String instruction, String direction) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.md", "text/markdown", content.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mockMvc.perform(multipart("/api/analysis/runs")
                        .file(file)
                        .param("instruction", instruction)
                        .param("skill", "resume-review")
                        .param("targetDirection", direction))
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

    private int scoreOverallOf(String runId) throws Exception {
        MvcResult history = mockMvc.perform(get("/api/analysis/optimization-history"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(history.getResponse().getContentAsString(StandardCharsets.UTF_8));
        for (JsonNode item : items) {
            if (runId.equals(item.get("runId").asText())) {
                return item.get("scoreOverall").asInt();
            }
        }
        return -1;
    }

    private static JsonNode path(JsonNode node, String field) {
        JsonNode v = node.get(field);
        assertThat(v).as("字段缺失: " + field).isNotNull();
        return v;
    }
}
