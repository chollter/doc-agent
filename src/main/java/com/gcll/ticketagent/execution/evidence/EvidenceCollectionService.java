package com.gcll.ticketagent.execution.evidence;

import com.gcll.ticketagent.eval.EvalFaultInjection;
import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.persistence.repository.ToolExecutionLogRepository;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolRegistry;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.react.ToolArgMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class EvidenceCollectionService {

    private static final Logger log = LoggerFactory.getLogger(EvidenceCollectionService.class);

    private final ToolRegistry toolRegistry;
    private final ToolExecutionLogRepository toolExecutionLogRepository;
    private final ExternalCallGateway externalCallGateway;
    private final ToolArgMerger toolArgMerger;
    private final AgentMetrics agentMetrics;

    public EvidenceCollectionService(
            ToolRegistry toolRegistry,
            ToolExecutionLogRepository toolExecutionLogRepository,
            ExternalCallGateway externalCallGateway,
            ToolArgMerger toolArgMerger,
            AgentMetrics agentMetrics
    ) {
        this.toolRegistry = toolRegistry;
        this.toolExecutionLogRepository = toolExecutionLogRepository;
        this.externalCallGateway = externalCallGateway;
        this.toolArgMerger = toolArgMerger;
        this.agentMetrics = agentMetrics;
    }

    public List<ToolResult> collect(
            String runId,
            TicketExtractResult extract,
            String originalContent,
            ToolSelection selection
    ) {
        List<ToolResult> results = new ArrayList<>();
        if (selection == null || selection.selectedToolNames() == null || selection.selectedToolNames().isEmpty()) {
            log.info("No tools selected for evidence collection, runId={}", runId);
            return results;
        }
        String sanitizedContent = EvalFaultInjection.sanitize(originalContent);

        for (String toolName : selection.selectedToolNames()) {
            ToolGateway tool = toolRegistry.find(toolName).orElse(null);
            if (tool == null) {
                log.warn("Selected tool not registered, runId={}, toolName={}", runId, toolName);
                continue;
            }
            if (EvalFaultInjection.shouldFail(originalContent, tool.toolName())) {
                ToolResult injectedFailure = ToolResult.failure(
                        tool.toolType(),
                        tool.toolName(),
                        sanitizedContent,
                        "injected failure for eval",
                        0
                );
                results.add(injectedFailure);
                toolExecutionLogRepository.save(runId, "EVIDENCE_COLLECTION", injectedFailure);
                agentMetrics.recordToolCall(tool.toolName(), false);
                agentMetrics.recordFallback("tool." + tool.toolName());
                log.warn("Tool [{}] failed by eval injection", tool.toolName());
                continue;
            }
            // 工具调用经 ExternalCallGateway 治理（tool-default：默认不重试，因工具有副作用）。
            // 阶段B：合并 LLM 工具选择时生成的参数到 extract（覆盖 system/module），再执行。
            // 超时/熔断/异常统一由 Gateway 处理；成功用 tool 自带的 ToolResult，失败降级为 failure。
            TicketExtractResult mergedExtract = toolArgMerger.merge(extract, selection.parameters());
            CallResult<ToolResult> callResult = externalCallGateway.execute(
                    mapCallName(tool.toolName()),
                    () -> tool.execute(mergedExtract, sanitizedContent)
            );
            ToolResult result;
            if (callResult.success()) {
                result = callResult.value();
            } else {
                String reason = callResult.circuitOpen() ? "circuit open"
                        : (callResult.error() != null ? callResult.error().getMessage() : "unknown");
                result = ToolResult.failure(
                        tool.toolType(), tool.toolName(), sanitizedContent, reason, callResult.durationMs());
            }
            results.add(result);
            toolExecutionLogRepository.save(runId, "EVIDENCE_COLLECTION", result);
            agentMetrics.recordToolCall(tool.toolName(), result.success());
            if (result.success()) {
                log.info("Tool [{}] executed successfully, durationMs={}", tool.toolName(), result.durationMs());
            } else {
                agentMetrics.recordFallback("tool." + tool.toolName());
                log.warn("Tool [{}] failed: {}", tool.toolName(), result.errorMessage());
            }
        }

        return results;
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
