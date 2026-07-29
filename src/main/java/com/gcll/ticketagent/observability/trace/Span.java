package com.gcll.ticketagent.observability.trace;

/**
 * Span ID 生成工具。
 * <p>
 * spanId 格式：{traceId}:{序号}，确定性指纹 ID，便于日志关联。
 * <p>
 * 注：Span 的领域模型已统一到 {@link com.gcll.ticketagent.domain.AgentStep}，
 * 本类仅保留 spanId 生成工具方法。
 */
public final class Span {

    private Span() {}

    /**
     * 生成 spanId：{traceId}:{序号}
     */
    public static String generateSpanId(String traceId, int sequence) {
        return traceId + ":" + sequence;
    }
}
