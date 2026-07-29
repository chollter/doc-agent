package com.gcll.ticketagent.a2a;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A2A 消息消费者——监听 Kafka A2A topic，分发给对应的团队 Agent。
 *
 * <h3>通信模型</h3>
 * <pre>
 * 主 Agent → KafkaAgentMessageBus.send(message) → Kafka topic "opsmind.a2a"
 * 本消费者 → onMessage(message) → 按 receiverId 查找 AgentEndpoint → handle(message)
 * AgentEndpoint.handle(message) → 返回响应消息 → KafkaAgentMessageBus.send(response)
 * </pre>
 *
 * <h3>路由</h3>
 * 按 message.receiverId 查找对应的 AgentEndpoint bean。
 * receiverId 为 null 时广播给所有 Agent（每个 Agent 自行决定是否处理）。
 *
 * <h3>可靠性</h3>
 * 消费失败时记录错误日志但不重试（避免死循环）。
 * 生产环境应加重试 + 死信队列。
 */
@Component
public class AgentMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(AgentMessageConsumer.class);

    private final Map<String, AgentEndpoint> endpointMap;
    private final AgentMessageBus messageBus;

    public AgentMessageConsumer(List<AgentEndpoint> endpoints, AgentMessageBus messageBus) {
        this.endpointMap = endpoints.stream()
                .collect(Collectors.toMap(AgentEndpoint::agentId, Function.identity()));
        this.messageBus = messageBus;
        log.info("A2A Agent 端点注册: {}", endpointMap.keySet());
    }

    /**
     * 监听 A2A topic，接收并分发消息。
     */
    @KafkaListener(
            topics = "opsmind.a2a",
            groupId = "opsmind-a2a-consumer"
    )
    public void onMessage(AgentMessage message) {
        log.info("A2A消息接收, from={}, to={}, action={}, runId={}",
                message.senderId(), message.receiverId(), message.action(), message.runId());

        try {
            if (message.receiverId() != null) {
                // 定向消息：分发给指定 Agent
                AgentEndpoint endpoint = endpointMap.get(message.receiverId());
                if (endpoint == null) {
                    log.warn("A2A目标Agent不存在, receiverId={}, runId={}", message.receiverId(), message.runId());
                    return;
                }
                dispatchToAgent(endpoint, message);
            } else {
                // 广播消息：分发给所有 Agent
                for (AgentEndpoint endpoint : endpointMap.values()) {
                    dispatchToAgent(endpoint, message);
                }
            }
        } catch (Exception ex) {
            log.error("A2A消息处理失败, from={}, to={}, action={}, runId={}",
                    message.senderId(), message.receiverId(), message.action(), message.runId(), ex);
        }
    }

    /**
     * 分发消息给指定 Agent，并将响应消息发回 Kafka。
     */
    private void dispatchToAgent(AgentEndpoint endpoint, AgentMessage message) {
        // 检查 action 是否支持
        String[] supportedActions = endpoint.supportedActions();
        if (supportedActions.length > 0) {
            boolean supported = false;
            for (String action : supportedActions) {
                if (action.equals(message.action())) {
                    supported = true;
                    break;
                }
            }
            if (!supported) {
                log.debug("Agent {} 不支持 action={}, 跳过", endpoint.agentId(), message.action());
                return;
            }
        }

        AgentMessage response = endpoint.handle(message);
        if (response != null) {
            messageBus.send(response);
            log.info("A2A响应已发送, from={}, to={}, action={}, runId={}",
                    response.senderId(), response.receiverId(), response.action(), response.runId());
        }
    }
}
