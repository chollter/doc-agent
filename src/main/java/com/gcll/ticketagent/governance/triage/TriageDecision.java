package com.gcll.ticketagent.governance.triage;

import java.util.List;

public record TriageDecision(
        TriageDecisionType type,
        boolean canAnalyze,
        boolean needFollowUp,
        boolean preAnalysisHumanConfirm,
        String reason,
        List<String> missingFields,
        List<String> semanticGaps,
        List<String> riskSignals
) {
}
