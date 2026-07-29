package com.gcll.ticketagent.a2a;

/**
 * Agent 消息总线——A2A 通信的传输层。
 * <p>
 * 当前基于 Kafka 实现，后续可替换为 gRPC / HTTP。
 * <p>
 * 使用方式：
 * <pre>
 * messageBus.send(AgentMessage.of("triage-agent", "db-team-agent",
 *     "REQUEST_INVESTIGATION", evidencePayload, runId));
 * </pre>
 */
public interface AgentMessageBus {

    /**
     * 发送消息到指定 Agent
     *
     * @param message 消息
     */
    void send(AgentMessage message);

    /**
     * 广播消息到所有 Agent
     *
     * @param message 消息（receiverId 设为 null）
     */
    default void broadcast(AgentMessage message) {
        send(new AgentMessage(
                message.senderId(), null, message.action(),
                message.payload(), message.runId(), message.timestamp()
        ));
    }
}
