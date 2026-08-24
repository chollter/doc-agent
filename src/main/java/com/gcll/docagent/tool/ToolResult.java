package com.gcll.docagent.tool;

public record ToolResult(
        ToolType toolType,
        String toolName,
        String input,
        String output,
        long durationMs,
        boolean success,
        String errorMessage
) {
    public static ToolResult success(ToolType type, String name, String input, String output, long durationMs) {
        return new ToolResult(type, name, input, output, durationMs, true, null);
    }

    public static ToolResult failure(ToolType type, String name, String input, String errorMessage, long durationMs) {
        return new ToolResult(type, name, input, null, durationMs, false, errorMessage);
    }
}
