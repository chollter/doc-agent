package com.example.opsmcp.config;

import com.example.opsmcp.service.OpsQueryService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * MCP tool 注册：把 query_logs / query_metric 暴露为 MCP tool。
 * <p>用低层 {@link McpServerFeatures.SyncToolSpecification}（手写 JSON schema），
 * 而非高层 @Tool 自动推断——确定性更高，schema 显式可控。
 * <p>Spring AI 1.0.0 GA（MCP SDK 0.10.0）API：handler 为
 * {@code BiFunction<McpSyncServerExchange, Map<String,Object>, CallToolResult>}，
 * 第二个参数直接是 arguments Map，返回 {@link McpSchema.CallToolResult}。
 */
@Configuration
public class McpToolsConfig {

    private static final String LOG_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "systemName": { "type": "string", "description": "受影响的系统名（如 payment-service）" },
                "moduleName": { "type": "string", "description": "受影响的模块名" },
                "query": { "type": "string", "description": "原始查询文本（工单内容或关键词）" },
                "limit": { "type": "integer", "description": "返回条数上限", "default": 5 }
              },
              "required": ["query"]
            }
            """;

    private static final String METRIC_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "systemName": { "type": "string", "description": "受影响的系统名" },
                "moduleName": { "type": "string", "description": "受影响的模块名" },
                "query": { "type": "string", "description": "原始查询文本（工单内容或关键词）" },
                "limit": { "type": "integer", "description": "返回条数上限", "default": 8 }
              },
              "required": ["query"]
            }
            """;

    private static final String VIEW_LOG_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "serviceName": { "type": "string", "description": "服务名（对应日志文件名，如 payment-service）" },
                "keyword": { "type": "string", "description": "过滤关键字（如 OOM、OutOfMemoryError），为空则取最近行" },
                "tailLines": { "type": "integer", "description": "返回最近多少行", "default": 50 }
              },
              "required": ["serviceName"]
            }
            """;

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> opsTools(OpsQueryService queryService) {
        McpServerFeatures.SyncToolSpecification logTool = new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("query_logs", "查询运维日志，按系统/模块/关键词检索近期日志记录", LOG_SCHEMA),
                (exchange, arguments) -> toTextResult(queryService.queryLogs(
                        str(arguments, "systemName"),
                        str(arguments, "moduleName"),
                        str(arguments, "query"),
                        intVal(arguments, "limit", 5)
                ))
        );
        McpServerFeatures.SyncToolSpecification metricTool = new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("query_metric", "查询运维指标，按系统/模块/关键词检索近期指标序列", METRIC_SCHEMA),
                (exchange, arguments) -> toTextResult(queryService.queryMetrics(
                        str(arguments, "systemName"),
                        str(arguments, "moduleName"),
                        str(arguments, "query"),
                        intVal(arguments, "limit", 8)
                ))
        );
        McpServerFeatures.SyncToolSpecification viewLogTool = new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool("view_logs", "查看服务日志文件（通过 grep/tail 命令检索真实日志，适合排查具体错误堆栈）", VIEW_LOG_SCHEMA),
                (exchange, arguments) -> toTextResult(queryService.viewLogFiles(
                        str(arguments, "serviceName"),
                        str(arguments, "keyword"),
                        intVal(arguments, "tailLines", 50)
                ))
        );
        return List.of(logTool, metricTool, viewLogTool);
    }

    private static McpSchema.CallToolResult toTextResult(String text) {
        return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(text)), false);
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static int intVal(Map<String, Object> args, String key, int defaultValue) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        return defaultValue;
    }
}
