package com.gcll.ticketagent.understanding.completeness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.risk.IncidentRiskPolicy;
import com.gcll.ticketagent.governance.triage.TriageDecisionService;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.NonRetryableCallException;
import com.gcll.ticketagent.understanding.followup.FollowUpQuestionService;
import com.gcll.ticketagent.understanding.followup.LlmFollowUpProvider;
import com.gcll.ticketagent.understanding.followup.TemplateFollowUpProvider;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import com.gcll.ticketagent.understanding.gap.RuleBasedInfoGapAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

class CompletenessDecisionServiceTest {

    private RuleBasedInfoGapAnalysisService infoGapAnalysisService;
    private CompletenessDecisionService service;

    @BeforeEach
    void setUp() {
        SchemaCompletenessChecker schemaCompletenessChecker = new SchemaCompletenessChecker();
        // LlmFollowUpProvider 现在依赖 LlmCallExecutor；mock 一个总是失败的执行器模拟"LLM 不可用"，
        // LlmFollowUpProvider.generate 走 List.of()。本测试关注 CompletenessDecisionService，
        // 追问由 TemplateFollowUpProvider 提供，与 LLM 路径无关。
        LlmCallExecutor noLlmExecutor = Mockito.mock(LlmCallExecutor.class);
        Mockito.when(noLlmExecutor.execute(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.fail(new NonRetryableCallException("no llm"), 0, 0L));
        FollowUpQuestionService followUpQuestionService = new FollowUpQuestionService(
                new TemplateFollowUpProvider(),
                new LlmFollowUpProvider(noLlmExecutor, new StructuredOutputParser(new ObjectMapper()), new ObjectMapper())
        );
        infoGapAnalysisService = new RuleBasedInfoGapAnalysisService(schemaCompletenessChecker);
        service = new CompletenessDecisionService(
                schemaCompletenessChecker,
                followUpQuestionService,
                new TriageDecisionService(new IncidentRiskPolicy())
        );
    }

    @Test
    void incompleteIncidentReturnsFollowUp() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                null, null, null, null, "接口报错",
                null, null, null, null,
                List.of(), 0.2
        );
        var gap = infoGapAnalysisService.analyze("接口报错了", extract).value();

        CompletenessDecision decision = service.decide("接口报错了", extract, gap, null);

        assertThat(decision.needFollowUp()).isTrue();
        assertThat(decision.canProceed()).isFalse();
        assertThat(decision.followUpQuestions()).isNotEmpty();
        assertThat(decision.missingSchemaFields()).contains("systemOrModule", "environment");
    }

    @Test
    void completeIncidentCanProceed() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", "支付回调", "/pay/callback", "500", "HTTP 500",
                "生产", "多个用户", "上午10点", "订单未更新",
                List.of("PRODUCTION"), 0.9
        );
        var gap = infoGapAnalysisService.analyze("生产支付回调500", extract).value();

        CompletenessDecision decision = service.decide("生产支付回调500", extract, gap, null);

        assertThat(decision.canProceed()).isTrue();
        assertThat(decision.needFollowUp()).isFalse();
    }

    @Test
    void batchProcessingSemanticGapBlocksAnalysis() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.UNKNOWN,
                "结算系统", "批处理", null, null, null,
                null, null, null, null,
                List.of(), 0.4
        );
        var gap = infoGapAnalysisService.analyze("结算批处理有时跑不完", extract).value();

        CompletenessDecision decision = service.decide("结算批处理有时跑不完", extract, gap, null);

        assertThat(gap.semanticGaps()).isNotEmpty();
        assertThat(decision.needFollowUp()).isTrue();
        assertThat(decision.followUpQuestions()).anyMatch(q ->
                q.contains("Job") || q.contains("批处理") || q.contains("偶发") || q.contains("结算"));
    }

    @Test
    void strongIncidentSignalCanProceedEvenWhenFieldsAreMissing() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", null, null, null, null,
                "生产", null, null, "支付成功但订单状态还是待支付",
                List.of(), 0.5
        );
        InfoGapAnalysis conservativeGap = new InfoGapAnalysis(
                List.of("apiOrFeature", "errorDetail", "timeRange", "impactScope"),
                List.of("缺少接口、错误详情、时间和影响范围"),
                List.of("请补充接口、错误详情、时间和影响范围"),
                "关键信息缺失",
                false,
                0.4,
                true
        );

        CompletenessDecision decision = service.decide(
                "生产环境支付系统，多个用户支付成功但订单状态还是待支付。",
                extract,
                conservativeGap,
                null
        );

        assertThat(decision.canProceed()).isTrue();
        assertThat(decision.needFollowUp()).isFalse();
    }

    @Test
    void vagueProductionPaymentSlowCaseStillNeedsFollowUp() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", null, null, null, null,
                "生产", null, null, "生产支付接口偶尔慢",
                List.of(), 0.6
        );
        InfoGapAnalysis conservativeGap = new InfoGapAnalysis(
                List.of("apiOrFeature", "errorDetail", "timeRange", "impactScope"),
                List.of("缺少接口、错误详情、时间和影响范围"),
                List.of("请补充接口、错误详情、时间和影响范围"),
                "关键信息缺失",
                false,
                0.4,
                true
        );

        CompletenessDecision decision = service.decide(
                "生产支付接口偶尔慢",
                extract,
                conservativeGap,
                null
        );

        assertThat(decision.canProceed()).isFalse();
        assertThat(decision.needFollowUp()).isTrue();
    }
}
