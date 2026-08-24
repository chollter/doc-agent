package com.gcll.docagent.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record LlmRunStatsDto(
        String runId,
        int totalCalls,
        int successCalls,
        int failedCalls,
        int fallbackSignals,
        long totalDurationMs,
        Map<String, CallNameStats> byCallName,
        List<LlmCallBrief> calls
) {
    public record CallNameStats(
            int totalCalls,
            int successCalls,
            int failedCalls,
            long totalDurationMs,
            long maxDurationMs
    ) {
    }

    public record LlmCallBrief(
            String callName,
            boolean success,
            boolean circuitOpen,
            int attempts,
            long durationMs,
            String model,
            int promptTokens,
            int completionTokens,
            String errorType,
            Instant occurredAt
    ) {
    }
}
