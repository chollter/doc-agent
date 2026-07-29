package com.gcll.ticketagent.investigation;

import com.gcll.ticketagent.triage.TriageResult;
import com.gcll.ticketagent.investigation.InvestigationResult;
import com.gcll.ticketagent.investigation.strategy.InvestigationStrategyType;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

/**
 * 排查完成事件
 */
@Data
@Builder
public class InvestigationCompletedEvent {
    
    private final String eventId;
    private final String runId;
    private final String traceId;
    private final TriageResult triageResult;
    private final InvestigationResult investigationResult;
    private final Timestamp timestamp;
    
    /**
     * 时间戳信息
     */
    @Data
    @Builder
    public static class Timestamp {
        private final Instant completedAt;
        private final Instant publishedAt;
    }
}