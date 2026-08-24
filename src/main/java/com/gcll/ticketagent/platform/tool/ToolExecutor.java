package com.gcll.ticketagent.platform.tool;

import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import com.gcll.ticketagent.tool.ToolExecutionHolder;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolInvocation;
import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.stereotype.Component;

/**
 * 单工具执行器：把工具调用包进 {@link ExternalCallGateway} 治理
 * （重试/超时/熔断/限流，策略按 callName 前缀 tool.* 内置），
 * 并在执行期间维护 runId ThreadLocal（供 DANGER 门控与审计日志取用）。
 */
@Component
public class ToolExecutor {

    private final ExternalCallGateway externalCallGateway;

    public ToolExecutor(ExternalCallGateway externalCallGateway) {
        this.externalCallGateway = externalCallGateway;
    }

    public ToolResult execute(ToolGateway tool, ToolInvocation invocation) {
        CallResult<ToolResult> callResult = externalCallGateway.execute(
                mapCallName(tool.toolName()),
                () -> executeWithRunContext(tool, invocation)
        );
        if (callResult.success()) {
            return callResult.value();
        }
        String reason = callResult.circuitOpen() ? "circuit open"
                : (callResult.error() != null ? callResult.error().getMessage() : "unknown");
        return ToolResult.failure(
                tool.toolType(),
                tool.toolName(),
                String.valueOf(invocation.parameters()),
                reason,
                callResult.durationMs()
        );
    }

    private ToolResult executeWithRunContext(ToolGateway tool, ToolInvocation invocation) {
        String previousRunId = ToolExecutionHolder.getRunId();
        try {
            if (invocation.runId() != null && !invocation.runId().isBlank()) {
                ToolExecutionHolder.setRunId(invocation.runId());
            }
            return tool.execute(invocation);
        } finally {
            if (previousRunId != null) {
                ToolExecutionHolder.setRunId(previousRunId);
            } else {
                ToolExecutionHolder.clear();
            }
        }
    }

    /** 工具名 → 治理策略名（tool.* 前缀统一走 CallRegistry 内置的 tool-default 策略）。 */
    private String mapCallName(String toolName) {
        return "tool." + toolName.replace('_', '-').toLowerCase();
    }
}
