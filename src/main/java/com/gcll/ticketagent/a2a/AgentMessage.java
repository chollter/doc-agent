package com.gcll.ticketagent.a2a;

/**
 * Agent 间消息——A2A 通信的基本传输单元。
 * <p>
 * 基于 Google A2A 协议理念：Agent 之间通过结构化消息通信，
 * 每条消息包含发送方、接收方、动作和载荷。
 * <p>
 * 当前为 Kafka 实现，后续可切换为 gRPC / HTTP。
 *
 * @param senderId   发送方 Agent ID
 * @param receiverId 接收方 Agent ID（null=广播）
 * @param action     动作类型（REQUEST_INVESTIGATION / REPORT_EVIDENCE / NOTIFY_RESULT）
 * @param payload    消息载荷（JSON）
 * @param runId      关联工单 ID
 * @param timestamp  时间戳
 */
public record AgentMessage(
        String senderId,
        String receiverId,
        String action,
        String payload,
        String runId,
        long timestamp
) {
    public static AgentMessage of(String senderId, String receiverId,
                                   String action, String payload, String runId) {
        return new AgentMessage(senderId, receiverId, action, payload, runId, System.currentTimeMillis());
    }
}
