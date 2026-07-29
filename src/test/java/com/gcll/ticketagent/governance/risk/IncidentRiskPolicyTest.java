package com.gcll.ticketagent.governance.risk;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IncidentRiskPolicyTest {

    private final IncidentRiskPolicy policy = new IncidentRiskPolicy();

    @Test
    void strongProductionPaymentIncidentAllowsAnalysisAndRequiresHumanConfirm() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", null, null, null, null,
                "生产", "多个用户", null, "支付成功但订单状态还是待支付",
                List.of(), 0.6
        );

        IncidentRiskDecision decision = policy.evaluate(
                "生产环境支付系统，多个用户支付成功但订单状态还是待支付。",
                extract
        );

        assertThat(decision.allowAnalysisDespiteMissingFields()).isTrue();
        assertThat(decision.requireHumanConfirm()).isTrue();
        assertThat(decision.reasons()).contains("PRODUCTION", "NON_SINGLE_IMPACT", "CRITICAL_BUSINESS");
    }

    @Test
    void lowRiskSingleUserIncidentDoesNotRequireHumanConfirm() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "账号系统", "MFA", "mfa/reset", null, "wait admin approval",
                "生产", "单个用户", "上午10点", "单个用户无法登录后台",
                List.of(), 0.8
        );

        IncidentRiskDecision decision = policy.evaluate("", extract);

        assertThat(decision.requireHumanConfirm()).isFalse();
    }

    @Test
    void vagueProductionBusinessIncidentDoesNotBypassFollowUp() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", null, null, null, null,
                "生产", null, null, "生产支付接口偶尔慢",
                List.of(), 0.6
        );

        IncidentRiskDecision decision = policy.evaluate("生产支付接口偶尔慢", extract);

        assertThat(decision.allowAnalysisDespiteMissingFields()).isFalse();
        assertThat(decision.requireHumanConfirm()).isFalse();
    }

    @Test
    void productionDataIntegrityRiskRequiresHumanConfirmEvenForSingleUser() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "报表系统", "导出任务", null, null, null,
                "生产", "单个用户", null, "报表导出任务偶发格式错乱",
                List.of(), 0.7
        );

        IncidentRiskDecision decision = policy.evaluate(
                "生产环境新上线的报表导出任务偶发格式错乱，暂无错误码，单个运营用户反馈。",
                extract
        );

        assertThat(decision.allowAnalysisDespiteMissingFields()).isTrue();
        assertThat(decision.requireHumanConfirm()).isTrue();
        assertThat(decision.reasons()).contains("PRODUCTION", "DATA_INTEGRITY_RISK");
    }

    @Test
    void nonIncidentHasNoRiskSignal() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.CONSULT,
                "支付系统", null, null, null, null,
                null, null, null, null,
                List.of(), 0.8
        );

        IncidentRiskDecision decision = policy.evaluate("如何配置支付回调超时时间？", extract);

        assertThat(decision.score()).isZero();
        assertThat(decision.allowAnalysisDespiteMissingFields()).isFalse();
        assertThat(decision.requireHumanConfirm()).isFalse();
    }
}
