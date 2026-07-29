package com.gcll.ticketagent.observability.trace;

/**
 * 一个工单一条 Trace。
 * <p>
 * Trace 是可观测性的顶层容器，关联一个 AgentRun。
 * 内部包含树形结构的 Span 列表，父子关系通过 parentStepId 表达。
 */
public record Trace(
        String traceId,
        String runId
) {
}
