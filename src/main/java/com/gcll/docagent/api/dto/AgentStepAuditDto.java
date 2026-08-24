package com.gcll.docagent.api.dto;

import java.time.Instant;

public record AgentStepAuditDto(
        String id,
        String stepName,
        String status,
        String inputSnapshot,
        String outputSnapshot,
        boolean llmUsed,
        String toolUsed,
        long costMs,
        String errorMessage,
        Instant createdAt
) {
}
