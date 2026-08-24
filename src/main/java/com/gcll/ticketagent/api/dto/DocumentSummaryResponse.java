package com.gcll.ticketagent.api.dto;

import java.util.List;

public record DocumentSummaryResponse(
        String runId,
        String skill,
        String status,
        String executionMode,
        String summary,
        List<String> keyPoints,
        List<String> risks,
        List<String> todos,
        List<String> citations,
        List<SummaryStep> steps
) {
    public record SummaryStep(String name, String status, String detail) {}
}
