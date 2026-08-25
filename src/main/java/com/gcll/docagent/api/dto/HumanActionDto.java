package com.gcll.docagent.api.dto;

import java.time.Instant;

/** 待人工确认动作的对外视图（HITL 闭环）。 */
public record HumanActionDto(
        String id,
        String runId,
        String actionType,
        String status,
        String payload,
        String reason,
        Instant createdAt
) {
}
