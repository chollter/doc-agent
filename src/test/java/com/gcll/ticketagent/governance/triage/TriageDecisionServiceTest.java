package com.gcll.ticketagent.governance.triage;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.risk.IncidentRiskPolicy;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TriageDecisionServiceTest {

    private final TriageDecisionService service = new TriageDecisionService(new IncidentRiskPolicy());

    @Test
    void vagueProductionBusinessIncidentNeedsMoreInfo() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", null, null, null, null,
                "生产", null, null, "生产支付接口偶尔慢",
                List.of(), 0.6
        );
        InfoGapAnalysis gap = new InfoGapAnalysis(
                List.of("apiOrFeature", "errorDetail", "timeRange", "impactScope"),
                List.of("缺少具体接口、时间范围、影响范围"),
                List.of("请补充具体接口、时间范围、影响范围"),
                "关键信息缺失",
                false,
                0.4,
                true
        );

        TriageDecision decision = service.decide(
                "生产支付接口偶尔慢",
                extract,
                gap,
                gap.schemaMissing()
        );

        assertThat(decision.type()).isEqualTo(TriageDecisionType.NEED_MORE_INFO);
        assertThat(decision.canAnalyze()).isFalse();
        assertThat(decision.needFollowUp()).isTrue();
    }

    @Test
    void knownSubjectAndSymptomCanAnalyzeWithNonBlockingMissingFields() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", null, "支付接口", null, null,
                "生产", null, null, "支付接口响应变慢",
                List.of(), 0.65
        );
        InfoGapAnalysis gap = new InfoGapAnalysis(
                List.of("errorDetail", "timeRange", "impactScope"),
                List.of("缺少时间范围和影响范围"),
                List.of("请补充开始时间和影响范围"),
                "必填字段不足",
                false,
                0.65,
                true
        );

        TriageDecision decision = service.decide(
                "生产支付接口响应变慢，暂未提供具体时间和影响范围。",
                extract,
                gap,
                gap.schemaMissing()
        );

        assertThat(decision.type()).isEqualTo(TriageDecisionType.ANALYZE);
        assertThat(decision.canAnalyze()).isTrue();
        assertThat(decision.needFollowUp()).isFalse();
    }

    @Test
    void lowConfidenceReadyConflictNeedsMoreInfoWithoutRiskOverride() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", null, "支付接口", null, null,
                "生产", null, null, "生产支付接口偶尔慢",
                List.of(), 0.6
        );
        InfoGapAnalysis gap = new InfoGapAnalysis(
                List.of("timeRange"),
                List.of("未说明偶发还是必现", "未说明是读接口还是写接口/交易接口"),
                List.of("问题是必现还是偶发？", "影响的是查询类接口还是下单/支付等写接口？"),
                null,
                true,
                0.35,
                true
        );

        TriageDecision decision = service.decide(
                "生产支付接口偶尔慢",
                extract,
                gap,
                gap.schemaMissing()
        );

        assertThat(decision.type()).isEqualTo(TriageDecisionType.NEED_MORE_INFO);
        assertThat(decision.canAnalyze()).isFalse();
        assertThat(decision.needFollowUp()).isTrue();
    }

    @Test
    void lowConfidenceReadyConflictIsNotOverriddenByStrongRiskSignal() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", null, "支付接口", null, null,
                "生产", "多个用户", null, "多个用户反馈生产支付接口偶尔慢",
                List.of(), 0.6
        );
        InfoGapAnalysis gap = new InfoGapAnalysis(
                List.of("timeRange"),
                List.of("未说明偶发还是必现"),
                List.of("问题是必现还是偶发？"),
                null,
                true,
                0.35,
                true
        );

        TriageDecision decision = service.decide(
                "生产支付接口偶尔慢，影响多个用户",
                extract,
                gap,
                gap.schemaMissing()
        );

        assertThat(decision.type()).isEqualTo(TriageDecisionType.NEED_MORE_INFO);
        assertThat(decision.riskSignals()).contains("PRODUCTION", "NON_SINGLE_IMPACT");
    }

    @Test
    void lowConfidenceOperationalNonIncidentGapNeedsMoreInfo() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.CONSULT,
                "结算系统", null, "结算批处理", null, null,
                null, null, null, "结算批处理有时跑不完",
                List.of(), 0.6
        );
        InfoGapAnalysis gap = new InfoGapAnalysis(
                List.of("environment", "timeRange", "errorDetail"),
                List.of("未说明偶发还是必现", "未提供具体批处理 Job 名", "未说明结算窗口是否受影响"),
                List.of("具体是哪个批处理 Job？", "是否影响当日结算窗口？"),
                null,
                true,
                0.35,
                true
        );

        TriageDecision decision = service.decide(
                "结算批处理有时跑不完",
                extract,
                gap,
                gap.schemaMissing()
        );

        assertThat(decision.type()).isEqualTo(TriageDecisionType.NEED_MORE_INFO);
        assertThat(decision.canAnalyze()).isFalse();
    }

    @Test
    void productionDataIntegrityRiskCanAnalyzeWithHumanConfirm() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "报表系统", "导出任务", null, null, null,
                "生产", "单个用户", null, "报表导出任务偶发格式错乱",
                List.of(), 0.7
        );
        InfoGapAnalysis gap = new InfoGapAnalysis(
                List.of("apiOrFeature", "errorDetail", "timeRange"),
                List.of("缺少错误详情"),
                List.of("请补充错误详情"),
                "关键信息缺失",
                false,
                0.4,
                true
        );

        TriageDecision decision = service.decide(
                "生产环境新上线的报表导出任务偶发格式错乱，暂无错误码，单个运营用户反馈。",
                extract,
                gap,
                gap.schemaMissing()
        );

        assertThat(decision.type()).isEqualTo(TriageDecisionType.ANALYZE_WITH_HUMAN_CONFIRM);
        assertThat(decision.canAnalyze()).isTrue();
        assertThat(decision.preAnalysisHumanConfirm()).isTrue();
        assertThat(decision.riskSignals()).contains("DATA_INTEGRITY_RISK");
    }

    @Test
    void completeIncidentCanAnalyze() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", "支付回调", "/pay/callback", "500", "HTTP 500",
                "生产", "多个用户", "上午10点", "订单未更新",
                List.of(), 0.9
        );
        InfoGapAnalysis gap = new InfoGapAnalysis(
                List.of(),
                List.of(),
                List.of(),
                null,
                true,
                0.9,
                true
        );

        TriageDecision decision = service.decide(
                "生产支付回调 500，多个用户上午10点开始失败",
                extract,
                gap,
                gap.schemaMissing()
        );

        assertThat(decision.type()).isEqualTo(TriageDecisionType.ANALYZE_WITH_HUMAN_CONFIRM);
        assertThat(decision.canAnalyze()).isTrue();
        assertThat(decision.needFollowUp()).isFalse();
    }
}
