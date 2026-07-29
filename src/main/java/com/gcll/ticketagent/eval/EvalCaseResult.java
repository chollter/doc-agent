package com.gcll.ticketagent.eval;

import java.util.List;

public record EvalCaseResult(
        String caseId,
        String scenarioType,
        String runId,
        boolean passed,
        List<EvalAssertionResult> assertions,
        List<EvalAuditStepResult> auditSteps
) {
}
