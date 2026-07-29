package com.gcll.ticketagent.governance.risk;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class IncidentRiskPolicy {

    private static final int ANALYSIS_THRESHOLD = 40;
    private static final int HUMAN_CONFIRM_THRESHOLD = 60;

    public IncidentRiskDecision evaluate(String userContent, TicketExtractResult extract) {
        if (extract == null || extract.issueType() != IssueType.INCIDENT) {
            return new IncidentRiskDecision(false, false, false, false, 0, List.of());
        }

        String text = normalize(String.join(" ",
                nullToBlank(userContent),
                nullToBlank(extract.affectedSystem()),
                nullToBlank(extract.affectedModule()),
                nullToBlank(extract.apiName()),
                nullToBlank(extract.errorCode()),
                nullToBlank(extract.errorMessage()),
                nullToBlank(extract.environment()),
                nullToBlank(extract.impactScope()),
                nullToBlank(extract.businessImpact()),
                extract.severitySignals() == null ? "" : String.join(" ", extract.severitySignals())
        ));

        List<String> reasons = new ArrayList<>();
        int score = 0;
        if (isProduction(text, extract)) {
            score += 20;
            reasons.add("PRODUCTION");
        }
        if (hasNonSingleImpact(text, extract)) {
            score += 20;
            reasons.add("NON_SINGLE_IMPACT");
        }
        if (hasCriticalBusinessSignal(text)) {
            score += 20;
            reasons.add("CRITICAL_BUSINESS");
        }
        if (hasTechnicalFailureSignal(text)) {
            score += 20;
            reasons.add("TECHNICAL_FAILURE");
        }
        if (hasConsistencyRisk(text)) {
            score += 25;
            reasons.add("CONSISTENCY_RISK");
        }
        if (hasDataIntegrityRisk(text)) {
            score += 25;
            reasons.add("DATA_INTEGRITY_RISK");
        }
        if (extract.confidence() > 0 && extract.confidence() < 0.5) {
            score += 10;
            reasons.add("LOW_EXTRACT_CONFIDENCE");
        }

        boolean actionableEvidence = hasActionableEvidence(text, extract);
        boolean strongIncidentSignal = score >= ANALYSIS_THRESHOLD && actionableEvidence;
        boolean highRisk = score >= HUMAN_CONFIRM_THRESHOLD;
        boolean requireHumanConfirm = isProduction(text, extract)
                && ((highRisk && hasExplicitNonSingleImpact(text, extract)) || hasDataIntegrityRisk(text));
        return new IncidentRiskDecision(
                strongIncidentSignal,
                highRisk,
                strongIncidentSignal,
                requireHumanConfirm,
                score,
                List.copyOf(reasons)
        );
    }

    private boolean isProduction(String text, TicketExtractResult extract) {
        return "生产".equals(extract.environment()) || containsAny(text, "生产", "线上", "prod", "production");
    }

    private boolean hasNonSingleImpact(String text, TicketExtractResult extract) {
        String impactScope = extract.impactScope();
        if (impactScope != null && impactScope.contains("单")) {
            return false;
        }
        return impactScope == null || containsAny(text,
                "多个用户", "部分用户", "所有用户", "全量", "多个商户", "好几个用户", "several users");
    }

    private boolean hasExplicitNonSingleImpact(String text, TicketExtractResult extract) {
        String impactScope = extract.impactScope();
        if (impactScope != null && impactScope.contains("单")) {
            return false;
        }
        return impactScope != null && !impactScope.isBlank()
                || containsAny(text, "多个用户", "部分用户", "所有用户", "全量", "多个商户", "好几个用户", "several users");
    }

    private boolean hasCriticalBusinessSignal(String text) {
        return containsAny(text, "支付", "订单", "结算", "库存", "资金", "账务", "网关", "gateway", "认证", "登录", "出库");
    }

    private boolean hasTechnicalFailureSignal(String text) {
        return containsAny(text,
                "500", "504", "timeout", "oom", "heap space", "certificate expired",
                "consumer lag", "dead letter", "lock wait timeout", "no space left");
    }

    private boolean hasConsistencyRisk(String text) {
        return containsAny(text, "支付成功", "订单状态", "状态未更新", "待支付", "数据不一致", "重复执行");
    }

    private boolean hasDataIntegrityRisk(String text) {
        return containsAny(text, "格式错乱", "数据错乱", "金额错误", "账单未生成", "状态不一致");
    }

    private boolean hasActionableEvidence(String text, TicketExtractResult extract) {
        return hasTechnicalFailureSignal(text)
                || hasConsistencyRisk(text)
                || hasDataIntegrityRisk(text)
                || (isProduction(text, extract) && hasExplicitNonSingleImpact(text, extract) && hasCriticalBusinessSignal(text));
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
