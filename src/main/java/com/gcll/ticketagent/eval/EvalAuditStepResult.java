package com.gcll.ticketagent.eval;

public record EvalAuditStepResult(
        String stepName,
        String status,
        boolean llmUsed,
        String toolUsed,
        long costMs,
        String inputSnapshot,
        String outputSnapshot,
        String errorMessage,
        String createdAt
) {
}
