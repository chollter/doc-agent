package com.gcll.ticketagent.agent.multiagent;

import com.gcll.ticketagent.execution.evidence.EvidenceCollectionService;
import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 指标分析子智能体：查运维日志 → 推理资源瓶颈。
 *
 * <p>角色："你是指标分析专家"。复用 {@link EvidenceCollectionService} 调 query_logs，
 * 再用自己的 LLM 会话推理"是否有资源瓶颈、异常波动"。
 */
@Component
public class MetricAnalyst extends AbstractWorkerAgent {

    private static final String ROLE = "metric";
    private static final String PROMPT_FILE = "worker-metric-analyst.txt";
    private static final String CALL_NAME = "llm.worker-metric";

    private final EvidenceCollectionService evidenceCollectionService;

    public MetricAnalyst(LlmCallExecutor llmCallExecutor, EvidenceCollectionService evidenceCollectionService) {
        super(llmCallExecutor, PROMPT_FILE, CALL_NAME);
        this.evidenceCollectionService = evidenceCollectionService;
    }

    @Override
    public String role() {
        return ROLE;
    }

    @Override
    protected String gatherRawEvidence(AgentRunContext ctx) {
        // 复用现有证据收集：调 query_logs
        TicketExtractResult extract = ctx.extract();
        ToolSelection selection = new ToolSelection(List.of("query_logs"), java.util.Map.of(), "metric-analyst", false);
        List<ToolResult> results = evidenceCollectionService.collect(ctx.runId(), extract, ctx.originalContent(), selection);
        StringBuilder sb = new StringBuilder();
        for (ToolResult r : results) {
            if (r.success() && r.output() != null) {
                sb.append("[").append(r.toolName()).append("]\n").append(r.output()).append("\n\n");
            }
        }
        return sb.toString();
    }
}
