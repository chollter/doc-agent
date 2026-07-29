package com.gcll.ticketagent.governance.risk;

import java.util.List;

public record IncidentRiskDecision(
        boolean strongIncidentSignal,
        boolean highRisk,
        boolean allowAnalysisDespiteMissingFields,
        boolean requireHumanConfirm,
        int score,
        List<String> reasons
) {
}
