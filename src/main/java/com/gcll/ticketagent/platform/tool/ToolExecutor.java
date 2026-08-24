package com.gcll.ticketagent.platform.tool;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import com.gcll.ticketagent.tool.ToolExecutionHolder;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.stereotype.Component;

@Component
public class ToolExecutor {

    private final ExternalCallGateway externalCallGateway;

    public ToolExecutor(ExternalCallGateway externalCallGateway) {
        this.externalCallGateway = externalCallGateway;
    }

    public ToolResult execute(ToolGateway tool, TicketExtractResult extract, String originalContent, String runId) {
        CallResult<ToolResult> callResult = externalCallGateway.execute(
                mapCallName(tool.toolName()),
                () -> executeWithRunContext(tool, extract, originalContent, runId)
        );
        if (callResult.success()) {
            return callResult.value();
        }
        String reason = callResult.circuitOpen() ? "circuit open"
                : (callResult.error() != null ? callResult.error().getMessage() : "unknown");
        return ToolResult.failure(
                tool.toolType(),
                tool.toolName(),
                originalContent,
                reason,
                callResult.durationMs()
        );
    }

    private ToolResult executeWithRunContext(
            ToolGateway tool,
            TicketExtractResult extract,
            String originalContent,
            String runId
    ) {
        String previousRunId = ToolExecutionHolder.getRunId();
        try {
            if (runId != null && !runId.isBlank()) {
                ToolExecutionHolder.setRunId(runId);
            }
            return tool.execute(extract, originalContent);
        } finally {
            if (previousRunId != null) {
                ToolExecutionHolder.setRunId(previousRunId);
            } else {
                ToolExecutionHolder.clear();
            }
        }
    }

    /** 把工具实例名映射到 opsmind.resilience.call-mappings 的策略名；未映射的走 plain 路径。 */
    private String mapCallName(String toolName) {
        return switch (toolName) {
            case "query_logs" -> "tool.query-logs";
            case "searchSimilarCases" -> "tool.similar-cases";
            case "notifyOncall" -> "tool.notify-oncall";
            case "executeRemediation" -> "tool.execute-remediation";
            default -> "tool.default";
        };
    }
}
