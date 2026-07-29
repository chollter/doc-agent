package com.gcll.ticketagent.governance.triage;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.risk.IncidentRiskDecision;
import com.gcll.ticketagent.governance.risk.IncidentRiskPolicy;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TriageDecisionService {

    private final IncidentRiskPolicy incidentRiskPolicy;

    public TriageDecisionService(IncidentRiskPolicy incidentRiskPolicy) {
        this.incidentRiskPolicy = incidentRiskPolicy;
    }

    public TriageDecision decide(
            String userContent,
            TicketExtractResult extract,
            InfoGapAnalysis gap,
            List<String> missingFields
    ) {
        List<String> missing = missingFields == null ? List.of() : missingFields;
        List<String> semanticGaps = gap.semanticGaps() == null ? List.of() : gap.semanticGaps();
        IncidentRiskDecision risk = incidentRiskPolicy.evaluate(userContent, extract);

        GapDecision gapDecision = classifyGap(userContent, extract, gap, missing, semanticGaps, risk);
        boolean needFollowUp = gapDecision == GapDecision.BLOCKING || gapDecision == GapDecision.CONFLICT;

        if (needFollowUp) {
            return new TriageDecision(
                    TriageDecisionType.NEED_MORE_INFO,
                    false,
                    true,
                    false,
                    followUpReason(gap, gapDecision),
                    List.copyOf(missing),
                    List.copyOf(semanticGaps),
                    risk.reasons()
            );
        }

        boolean preAnalysisHumanConfirm = risk.requireHumanConfirm();
        return new TriageDecision(
                preAnalysisHumanConfirm ? TriageDecisionType.ANALYZE_WITH_HUMAN_CONFIRM : TriageDecisionType.ANALYZE,
                true,
                false,
                preAnalysisHumanConfirm,
                preAnalysisHumanConfirm ? "信息可分析，但存在生产风险信号，需要人工确认" : "信息足够，进入分析",
                List.copyOf(missing),
                List.copyOf(semanticGaps),
                risk.reasons()
        );
    }

    private GapDecision classifyGap(
            String userContent,
            TicketExtractResult extract,
            InfoGapAnalysis gap,
            List<String> missingSchema,
            List<String> semanticGaps,
            IncidentRiskDecision risk
    ) {
        if (hasLowConfidenceGapConflict(gap, semanticGaps)) {
            return GapDecision.CONFLICT;
        }

        if (extract.issueType() != IssueType.INCIDENT) {
            return gap.readyForAnalysis() ? GapDecision.NON_BLOCKING : GapDecision.BLOCKING;
        }

        boolean subjectKnown = hasSubject(extract);
        boolean symptomKnown = hasSymptom(userContent, extract);
        if (!subjectKnown || !symptomKnown) {
            return GapDecision.BLOCKING;
        }

        if (!gap.readyForAnalysis() && severeCoreMissing(missingSchema)) {
            return risk.allowAnalysisDespiteMissingFields() ? GapDecision.RISK_OVERRIDDEN : GapDecision.BLOCKING;
        }

        return risk.allowAnalysisDespiteMissingFields() ? GapDecision.RISK_OVERRIDDEN : GapDecision.NON_BLOCKING;
    }

    private boolean hasSubject(TicketExtractResult extract) {
        return hasText(extract.affectedSystem())
                || hasText(extract.affectedModule());
    }

    private boolean hasSymptom(String userContent, TicketExtractResult extract) {
        return hasText(extract.errorCode())
                || hasText(extract.errorMessage())
                || hasText(extract.businessImpact())
                || hasOperationalText(userContent);
    }

    private boolean hasOperationalText(String userContent) {
        if (!hasText(userContent)) {
            return false;
        }
        String text = userContent.trim();
        return text.length() >= 10;
    }

    private boolean hasLowConfidenceGapConflict(InfoGapAnalysis gap, List<String> semanticGaps) {
        return gap.readyForAnalysis()
                && gap.confidence() > 0
                && gap.confidence() < 0.5
                && !semanticGaps.isEmpty();
    }

    private boolean severeCoreMissing(List<String> missingSchema) {
        int criticalMissing = 0;
        if (missingSchema.contains("systemOrModule")) {
            criticalMissing++;
        }
        if (missingSchema.contains("apiOrFeature")) {
            criticalMissing++;
        }
        if (missingSchema.contains("errorDetail")) {
            criticalMissing++;
        }
        if (missingSchema.contains("timeRange")) {
            criticalMissing++;
        }
        if (missingSchema.contains("impactScope")) {
            criticalMissing++;
        }
        return missingSchema.contains("apiOrFeature") && criticalMissing >= 3;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String followUpReason(InfoGapAnalysis gap, GapDecision gapDecision) {
        if (gapDecision == GapDecision.CONFLICT) {
            return "信息缺口判断存在低置信冲突，需补充关键上下文";
        }
        if (gapDecision == GapDecision.BLOCKING) {
            return "核心主体或故障现象不足，需补充后再分析";
        }
        if (gap.blockingReason() != null && !gap.blockingReason().isBlank()) {
            return gap.blockingReason();
        }
        return "信息不足";
    }

    private enum GapDecision {
        BLOCKING,
        NON_BLOCKING,
        RISK_OVERRIDDEN,
        CONFLICT
    }
}
