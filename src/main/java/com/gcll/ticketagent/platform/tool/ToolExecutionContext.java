package com.gcll.ticketagent.platform.tool;

import java.util.Map;

/**
 * 一批工具调用的共享执行上下文。
 *
 * @param runId      当前 Agent 运行 ID
 * @param phase      调用阶段名（如 REACT_ANALYZE），落审计日志用
 * @param attributes 编排层注入的环境上下文（如原文、文档句柄），透传给每个工具
 */
public record ToolExecutionContext(String runId, String phase, Map<String, Object> attributes) {

    public static ToolExecutionContext of(String runId, String phase) {
        return new ToolExecutionContext(runId, phase, Map.of());
    }
}
