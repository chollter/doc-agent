package com.gcll.ticketagent.eval;

import java.util.Map;

public record EvalMetricsSummary(
        int totalCases,
        int passedCases,
        double passRate,
        int analyzedCases,
        int followUpCases,
        int humanConfirmCases,
        int ragSearchCases,
        int ragDegradedCases,
        int toolCallCases,
        int totalToolCalls,
        int successfulToolCalls,
        int failedToolCalls,
        double toolSuccessRate,
        long averageRunCostMs,
        long p95RunCostMs,
        int totalLlmCalls,
        int successfulLlmCalls,
        int failedLlmCalls,
        long totalLlmDurationMs,
        int totalPromptTokens,
        int totalCompletionTokens,
        Map<String, Integer> casesByScenarioType
) {
    public static EvalMetricsSummary empty() {
        return new EvalMetricsSummary(
                0, 0, 0.0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0.0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()
        );
    }
}
