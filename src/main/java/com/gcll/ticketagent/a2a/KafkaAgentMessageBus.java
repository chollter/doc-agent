package com.gcll.ticketagent.a2a;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kafka Agent 消息总线实现——当前 A2A 通信的默认实现。
 * <p>
 * 所有 Agent 间消息通过 Kafka topic "opsmind.a2a" 传输。
 * 团队 Agent 各自消费自己关心的 action 类型。
 */
@Component
public class KafkaAgentMessageBus implements AgentMessageBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaAgentMessageBus.class);
    private static final String TOPIC = "opsmind.a2a";

    private final KafkaTemplate<String, AgentMessage> kafkaTemplate;

    public KafkaAgentMessageBus(KafkaTemplate<String, AgentMessage> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void send(AgentMessage message) {
        kafkaTemplate.send(TOPIC, message.runId(), message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("A2A消息发送失败, from={}, to={}, action={}, runId={}",
                                message.senderId(), message.receiverId(), message.action(), message.runId(), ex);
                    } else {
                        log.info("A2A消息已发送, from={}, to={}, action={}, runId={}",
                                message.senderId(), message.receiverId(), message.action(), message.runId());
                    }
                });
    }
}
