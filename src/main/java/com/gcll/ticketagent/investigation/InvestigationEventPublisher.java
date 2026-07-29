package com.gcll.ticketagent.investigation;

import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.triage.TriageResult;
import com.gcll.ticketagent.investigation.strategy.InvestigationStrategyType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 排查阶段事件发布器
 * 
 * 发布排查阶段的相关事件：
 * - InvestigationCompletedEvent：排查完成事件
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvestigationEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 发布排查完成事件
     */
    public void publishInvestigationCompleted(AgentRun run, TriageResult triageResult, InvestigationResult result) {
        InvestigationCompletedEvent event = InvestigationCompletedEvent.builder()
                .eventId("investigation-completed-" + System.currentTimeMillis())
                .runId(run.getId())
                .traceId(run.getTraceId())
                .triageResult(triageResult)
                .investigationResult(result)
                .timestamp(InvestigationCompletedEvent.Timestamp.builder()
                        .completedAt(result.getCompletedAt())
                        .publishedAt(Instant.now())
                        .build())
                .build();
        
        try {
            kafkaTemplate.send("opsmind.investigation.completed", event).get();
            log.info("发布排查完成事件 - runId: {}, strategy: {}", run.getId(), result.getStrategy());
        } catch (Exception e) {
            log.error("发布排查完成事件失败 - runId: {}", run.getId(), e);
            // 不阻塞主流程，异步事件发布失败不影响排查结果
        }
    }
}