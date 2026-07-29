package com.gcll.ticketagent.investigation;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.triage.TriageResult;

/**
 * 分诊完成事件——分诊阶段 → 排查阶段的解耦点。
 * <p>
 * 分诊（同步，秒级）完成后发布此事件，排查阶段异步消费。
 * Kafka 消息格式，需要可序列化。
 */
public record TriageCompletedEvent(
        String runId,
        String traceId,
        TriageResult triageResult,
        TicketExtractResult extract,
        String draftContent,
        boolean extractLlmUsed,
        long timestamp
) {
    public static TriageCompletedEvent of(
            String runId, String traceId,
            TriageResult triageResult, TicketExtractResult extract,
            String draftContent, boolean extractLlmUsed
    ) {
        return new TriageCompletedEvent(
                runId, traceId, triageResult, extract,
                draftContent, extractLlmUsed,
                System.currentTimeMillis()
        );
    }
}
