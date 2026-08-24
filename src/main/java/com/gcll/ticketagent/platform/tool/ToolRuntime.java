package com.gcll.ticketagent.platform.tool;

import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.persistence.repository.ToolExecutionLogRepository;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolInvocation;
import com.gcll.ticketagent.tool.ToolRegistry;
import com.gcll.ticketagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具运行时——批量工具调用的统一编排：
 * 注册表查找 → 组装 {@link ToolInvocation} → 经 {@link ToolExecutor}（治理包裹）执行
 * → 落 tool_execution_log 审计 + AgentMetrics 打点。
 */
@Component
public class ToolRuntime {

    private static final Logger log = LoggerFactory.getLogger(ToolRuntime.class);

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ToolExecutionLogRepository toolExecutionLogRepository;
    private final AgentMetrics agentMetrics;

    public ToolRuntime(
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            ToolExecutionLogRepository toolExecutionLogRepository,
            AgentMetrics agentMetrics
    ) {
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.toolExecutionLogRepository = toolExecutionLogRepository;
        this.agentMetrics = agentMetrics;
    }

    public List<ToolResult> executeBatch(ToolExecutionContext context, List<ToolCallRequest> requests) {
        List<ToolResult> results = new ArrayList<>();
        if (requests == null || requests.isEmpty()) {
            log.info("No tool calls requested, runId={}", context.runId());
            return results;
        }

        for (ToolCallRequest request : requests) {
            ToolGateway tool = toolRegistry.find(request.toolName()).orElse(null);
            if (tool == null) {
                log.warn("Selected tool not registered, runId={}, toolName={}", context.runId(), request.toolName());
                continue;
            }

            ToolInvocation invocation = new ToolInvocation(
                    context.runId(),
                    request.parameters() == null ? java.util.Map.of() : request.parameters(),
                    context.attributes()
            );
            ToolResult result = toolExecutor.execute(tool, invocation);
            results.add(result);
            recordResult(context, result);
        }
        return results;
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
