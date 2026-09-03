package com.gcll.docagent.api.dto;

public record StepEventDto(
        String runId,
        String stepName,
        String status,
        String message,
        long timestamp
) {
}
