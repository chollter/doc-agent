package com.gcll.ticketagent.platform.tool;

import com.gcll.ticketagent.eval.EvalFaultInjection;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.persistence.repository.ToolExecutionLogRepository;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolRegistry;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.react.ToolArgMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ToolRuntime {

    private static final Logger log = LoggerFactory.getLogger(ToolRuntime.class);

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ToolArgMerger toolArgMerger;
    private final ToolExecutionLogRepository toolExecutionLogRepository;
    private final AgentMetrics agentMetrics;

    public ToolRuntime(
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            ToolArgMerger toolArgMerger,
            ToolExecutionLogRepository toolExecutionLogRepository,
            AgentMetrics agentMetrics
    ) {
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.toolArgMerger = toolArgMerger;
        this.toolExecutionLogRepository = toolExecutionLogRepository;
        this.agentMetrics = agentMetrics;
    }

    public List<ToolResult> executeBatch(ToolExecutionContext context, List<ToolCallRequest> requests) {
        List<ToolResult> results = new ArrayList<>();
        if (requests == null || requests.isEmpty()) {
            log.info("No tool calls requested, runId={}", context.runId());
            return results;
        }

        String sanitizedContent = EvalFaultInjection.sanitize(context.originalContent());
        for (ToolCallRequest request : requests) {
            ToolGateway tool = toolRegistry.find(request.toolName()).orElse(null);
            if (tool == null) {
                log.warn("Selected tool not registered, runId={}, toolName={}", context.runId(), request.toolName());
                continue;
            }

            ToolResult result = executeOne(context, sanitizedContent, request, tool);
            results.add(result);
            recordResult(context, result);
        }
        return results;
    }

    private ToolResult executeOne(
            ToolExecutionContext context,
            String sanitizedContent,
            ToolCallRequest request,
            ToolGateway tool
    ) {
        if (EvalFaultInjection.shouldFail(context.originalContent(), tool.toolName())) {
            log.warn("Tool [{}] failed by eval injection", tool.toolName());
            return ToolResult.failure(
                    tool.toolType(),
                    tool.toolName(),
                    sanitizedContent,
                    "injected failure for eval",
                    0
            );
        }

        TicketExtractResult mergedExtract = toolArgMerger.merge(context.extract(), request.parameters());
        return toolExecutor.execute(tool, mergedExtract, sanitizedContent, context.runId());
    }

    private void recordResult(ToolExecutionContext context, ToolResult result) {
        toolExecutionLogRepository.save(context.runId(), context.phase(), result);
        agentMetrics.recordToolCall(result.toolName(), result.success());
        if (result.success()) {
            log.info("Tool [{}] executed successfully, durationMs={}", result.toolName(), result.durationMs());
            return;
        }
        agentMetrics.recordFallback("tool." + result.toolName());
        log.warn("Tool [{}] failed: {}", result.toolName(), result.errorMessage());
    }
}
