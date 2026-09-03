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
import static org.mockito.ArgumentMatchers.eq;
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

    /** 评价专调桩（v7）：深度契约——总体档位定论 + 五维评语带原文证据 + 判断式优缺点。 */
    private static final String EVALUATION_JSON = """
            {"overall":"在同年限（7年）候选人中处于上游：检索增强问答系统有日均 12 万次调用的业务级结果，工程完整度扎实；但云海经历零结果叠加 9 个月空窗拉低可信度，初筛大概率通过、空窗必被追问",
             "dimensions":[
               {"dimension":"真实性与可信度","comment":"星河经历有日均 12 万次调用量佐证，可信度较高；但代码预检修出 9 个月空窗，且云海段以参与开头却无任何结果——这组矛盾会让面试官追问归属真实性"},
               {"dimension":"项目经历含金量","comment":"检索增强问答系统是业务级落地，属于 7 年档里少的有真实流量见证的 AI 应用经历；云海订单中心经历无量化结果，含金量存疑"},
               {"dimension":"岗位匹配","comment":"画像共性要求缺上线产品一项：检索问答系统已上线但责任归属未写清；凭真实调用量可以补，但简历必须先写明白"},
               {"dimension":"表达质量","comment":"sec-2 时态混乱（负责/主导/参与混用），重点被淹没；动词强度整体偏弱，削弱了本可更硬的结果"},
               {"dimension":"职业轨迹","comment":"从传统后端转向 AI 应用的方向清晰，但 9 个月空窗打断了连续性；下一步若不能讲清转向动机，会被视为逃逸式跳槽"}],
             "strengths":["检索增强问答系统是业务级落地（原文：主导检索增强知识库问答系统），有真实调用量见证，同期候选人中稀缺"],
             "weaknesses":["9 个月空窗叠加云海段零结果，时间线硬伤会直接触发初筛质疑，需备好口径而非等待被问"]}
            """;

    @Test
    void assemblesFunnelVerdictEndToEnd() throws Exception {
        // 抽取、主分析、评价专调均走 4 参网关（按 prompt 文件分流）
        when(llmGateway.invoke(anyString(), eq("resume-entity-extract.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(ENTITIES_JSON, 200, 100, "qwen-plus"));
        when(llmGateway.invoke(anyString(), eq("resume-review.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(ANALYSIS_JSON, 800, 400, "qwen-plus"));
        when(llmGateway.invoke(anyString(), eq("resume-evaluation.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(EVALUATION_JSON, 600, 400, "qwen-plus"));

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

        // ⑨ 评价（v7 专调链路）：总评档位定论/五维评语带原文证据/判断式优缺点端到端透传
        JsonNode eval = verdict.get("evaluation");
        assertThat(eval).isNotNull();
        assertThat(path(eval, "overall").asText()).contains("上游");
        assertThat(eval.get("dimensions").size()).isEqualTo(5);
        assertThat(path(eval.get("dimensions").get(0), "dimension").asText()).isEqualTo("真实性与可信度");
        // v7 契约：评语必须引证据（旧 v6 契约是一句话敷衍，线上实测被用户判不合格）
        assertThat(path(eval.get("dimensions").get(0), "comment").asText()).contains("空窗");
        assertThat(eval.get("strengths").size()).isEqualTo(1);
        assertThat(eval.get("weaknesses").size()).isEqualTo(1);
    }

    @Test
    void marksDegradedAndSuppressesScoreWhenExtractionFails() throws Exception {
        // 抽取挂掉（4 参 invoke 的 entity-extract 分支抛异常）→ 降级路径；主分析与评价专调正常返回
        when(llmGateway.invoke(anyString(), eq("resume-entity-extract.txt"), anyString(), anyString()))
                .thenThrow(new RuntimeException("extraction down"));
        when(llmGateway.invoke(anyString(), eq("resume-review.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(ANALYSIS_JSON, 800, 400, "qwen-plus"));
        when(llmGateway.invoke(anyString(), eq("resume-evaluation.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(EVALUATION_JSON, 600, 400, "qwen-plus"));

        String runId = submit(RESUME_MD, "按方向画像分析这份简历", "AI应用开发");
        awaitStatus(runId, "COMPLETED");
        JsonNode result = resultOf(runId);

        JsonNode verdict = result.get("result").get("funnelVerdict");
        assertThat(verdict).isNotNull();
        // P11 回归锁（演进）：降级必须显式标记且不出总分——红旗层缺失时总分掩盖故障；
        // 但 LLM 五角度输出基于直读原文，不得整体作废（v1 缺陷：误杀后报告结论空白）
        assertThat(path(verdict, "analysisDegraded").asBoolean()).isTrue();
        assertThat(scoreOverallOf(runId)).isEqualTo(0);
        // 明细保留：强度 MIXED（1 条有结果 + 1 条无结果）/ 表达 62 分 / 杠杆卡 2 张
        assertThat(path(path(verdict, "strength"), "band").asText()).isEqualTo("MIXED");
        assertThat(path(path(verdict, "presentation"), "score").asInt()).isEqualTo(62);
        assertThat(verdict.get("leverageCards").size()).isEqualTo(2);
        // 红旗层退化为文本级粗查（降级不级联）：RESUME_MD 的 9 个月空窗仍被兜底检出，
        // 消息注明粗查口径——不造假实体级红旗，也不静默丢掉硬伤
        assertThat(verdict.get("redFlags").toString()).contains("TIMELINE_GAP").contains("粗查");
        // 评价专调基于 LLM 直读原文，抽取降级不陪葬（与五角度同口径）
        assertThat(path(path(verdict, "evaluation"), "overall").asText()).contains("上游");
    }

    @Test
    void appliesSuggestionToProduceRevisedMarkdown() throws Exception {
        when(llmGateway.invoke(anyString(), eq("resume-entity-extract.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(ENTITIES_JSON, 200, 100, "qwen-plus"));
        when(llmGateway.invoke(anyString(), eq("resume-review.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(ANALYSIS_JSON, 800, 400, "qwen-plus"));
        when(llmGateway.invoke(anyString(), eq("resume-evaluation.txt"), anyString(), anyString()))
                .thenReturn(LlmResponse.of(EVALUATION_JSON, 600, 400, "qwen-plus"));

        String runId = submit(RESUME_MD, "按方向画像分析这份简历", "AI应用开发");
        awaitStatus(runId, "COMPLETED");

        // 采纳第 0 条建议：before="参与订单中心开发" → after="负责订单模块重构，QPS 800→2000"
        MvcResult applied = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/analysis/runs/" + runId + "/apply-suggestions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"indices\":[0]}"))
                .andExpect(status().isOk())
                .andReturn();
        var revision = objectMapper.readTree(applied.getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(revision.get("appliedCount").asInt()).isEqualTo(1);
        assertThat(revision.get("missingBefores").size()).isZero();
        String md = revision.get("revisedMarkdown").asText();
        assertThat(md).contains("负责订单模块重构，QPS 800→2000");
        assertThat(md).doesNotContain("参与订单中心开发");
        // 变更节定位成功（前端据此高亮）
        assertThat(revision.get("changedSectionIds").toString()).contains("sec-");
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
