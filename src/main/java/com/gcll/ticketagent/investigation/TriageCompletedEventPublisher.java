package com.gcll.ticketagent.investigation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * 分诊完成事件发布器——分诊 → 排查的解耦点。
 * <p>
 * 分诊（同步，秒级）完成后，通过 Kafka 发布 TriageCompletedEvent，
 * InvestigationConsumer 异步消费并启动排查。
 * <p>
 * 始终激活（不受 opsmind.async.enabled 控制）：
 * 分诊/排查解耦是 v2 架构核心，不是可选功能。
 */
@Component
public class TriageCompletedEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TriageCompletedEventPublisher.class);

    private final KafkaTemplate<String, TriageCompletedEvent> kafkaTemplate;

    public TriageCompletedEventPublisher(KafkaTemplate<String, TriageCompletedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publish(String topic, TriageCompletedEvent event) {
        kafkaTemplate.send(topic, event.runId(), event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("分诊完成事件发送失败, runId={}, topic={}", event.runId(), topic, ex);
                    } else {
                        log.info("分诊完成事件已发送, runId={}, topic={}, partition={}",
                                event.runId(), topic, result.getRecordMetadata().partition());
                    }
                });
    }
}
