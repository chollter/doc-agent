package com.gcll.docagent.platform.tool;

import java.util.Map;

public record ToolCallRequest(
        String toolName,
        Map<String, String> parameters
) {
    public ToolCallRequest {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }
}
