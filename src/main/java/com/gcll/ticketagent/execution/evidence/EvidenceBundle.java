package com.gcll.ticketagent.execution.evidence;

import java.util.List;

public record EvidenceBundle(
        List<String> logSignals,
        List<String> metricSignals,
        List<String> riskSignals,
        List<String> unknowns,
        String summary
) {
    public boolean hasEvidence() {
        return !(logSignals.isEmpty() && metricSignals.isEmpty() && riskSignals.isEmpty());
    }
}
