package com.gcll.ticketagent.eval;

import java.util.List;
import java.util.Map;

public record EvalReport(
        String suiteName,
        int total,
        int passed,
        int failed,
        java.util.List<EvalGroupReport> groups,
        List<String> failures,
        Map<String, Integer> failureByAssertion,
        List<EvalCaseResult> caseResults,
        EvalQualitySummary qualitySummary,
        EvalMetricsSummary metricsSummary,
        String outputFile
) {
    public EvalReport withOutputFile(String outputFile) {
        return new EvalReport(
                suiteName,
                total,
                passed,
                failed,
                groups,
                failures,
                failureByAssertion,
                caseResults,
                qualitySummary,
                metricsSummary,
                outputFile
        );
    }
}
