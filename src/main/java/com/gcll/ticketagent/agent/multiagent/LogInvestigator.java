package com.gcll.ticketagent.agent.multiagent;

import com.gcll.ticketagent.execution.evidence.EvidenceCollectionService;
import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.tool.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 日志调查子智能体：查运维日志文件 → 推理错误位置与堆栈。
 *
 * <p>角色："你是日志分析专家"。
 *
 * <p>查询策略（体现真实运维排障）：
 * <ol>
 *   <li><b>优先 view_logs</b>：通过 MCP 执行 grep/tail 查真实日志文件（如 payment-service.log），
 *       这是真实运维场景——日志在文件里，不是数据库</li>
 *   <li><b>降级 query_logs</b>：MCP 不可用时查结构化日志表（已入库的 ERROR 日志）</li>
 * </ol>
 * 查到原始日志后用自己的 LLM 会话推理"错误发生在哪、堆栈指向什么"。
 */
@Component
public class LogInvestigator extends AbstractWorkerAgent {

    private static final org.slf4j.Logger log = LoggerFactory.getLogger(LogInvestigator.class);

    private static final String ROLE = "log";
    private static final String PROMPT_FILE = "worker-log-investigator.txt";
    private static final String CALL_NAME = "llm.worker-log";

    private final EvidenceCollectionService evidenceCollectionService;
    private final ObjectProvider<McpSyncClient> mcpClientProvider;

    public LogInvestigator(LlmCallExecutor llmCallExecutor,
                           EvidenceCollectionService evidenceCollectionService,
                           ObjectProvider<McpSyncClient> mcpClientProvider) {
        super(llmCallExecutor, PROMPT_FILE, CALL_NAME);
        this.evidenceCollectionService = evidenceCollectionService;
        this.mcpClientProvider = mcpClientProvider;
    }

    @Override
    public String role() {
        return ROLE;
    }

    @Override
    protected String gatherRawEvidence(AgentRunContext ctx) {
        TicketExtractResult extract = ctx.extract();
        String serviceName = resolveServiceName(extract);

        // 1. 优先 view_logs：通过 MCP 查真实日志文件（grep/tail）
        McpSyncClient mcpClient = mcpClientProvider.getIfAvailable();
        if (mcpClient != null && serviceName != null) {
            String logs = callViewLogs(mcpClient, serviceName, extract);
            if (logs != null && !logs.isBlank() && !logs.contains("未找到")) {
                log.info("view_logs hit for service={}, runId={}", serviceName, ctx.runId());
                return logs;
            }
            log.info("view_logs no hit for service={}, fallback to query_logs, runId={}", serviceName, ctx.runId());
        }

        // 2. 降级 query_logs：查结构化日志表
        ToolSelection selection = new ToolSelection(List.of("query_logs"), Map.of(), "log-investigator", false);
        List<ToolResult> results = evidenceCollectionService.collect(ctx.runId(), extract, ctx.originalContent(), selection);
        StringBuilder sb = new StringBuilder();
        for (ToolResult r : results) {
            if (r.success() && r.output() != null) {
                sb.append("[").append(r.toolName()).append("]\n").append(r.output()).append("\n\n");
            }
        }
        return sb.toString();
    }

    /**
     * 从 extract 推断日志文件对应的服务名。
     * extract.affectedSystem 可能是 "Payment service"，转成文件名 "payment-service"。
     */
    private String resolveServiceName(TicketExtractResult extract) {
        String system = extract.affectedSystem();
        if (system == null || system.isBlank()) {
            return null;
        }
        // 空格转连字符 + 转小写，匹配日志文件名（Payment service → payment-service）
        return system.trim().toLowerCase().replaceAll("\\s+", "-");
    }

    /** 调 MCP 的 view_logs 工具，查日志文件。 */
    private String callViewLogs(McpSyncClient mcpClient, String serviceName, TicketExtractResult extract) {
        try {
            // 关键字：优先用 errorMessage/errorCode，没有就用 affectedModule
            String keyword = extract.errorMessage();
            if (keyword == null || keyword.isBlank()) {
                keyword = extract.errorCode();
            }
            Map<String, Object> arguments = new java.util.HashMap<>();
            arguments.put("serviceName", serviceName);
            if (keyword != null && !keyword.isBlank()) {
                arguments.put("keyword", keyword);
            }
            arguments.put("tailLines", 30);

            McpSchema.CallToolResult result = mcpClient.callTool(
                    new io.modelcontextprotocol.spec.McpSchema.CallToolRequest("view_logs", arguments));
            if (Boolean.TRUE.equals(result.isError())) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (McpSchema.Content content : result.content()) {
                if (content instanceof McpSchema.TextContent text) {
                    sb.append(text.text());
                }
            }
            return sb.toString();
        } catch (Exception ex) {
            log.warn("view_logs MCP call failed for service={}: {}", serviceName, ex.getMessage());
            return null;
        }
    }
}
