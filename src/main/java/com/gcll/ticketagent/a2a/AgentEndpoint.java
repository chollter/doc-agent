package com.gcll.ticketagent.a2a;

/**
 * Agent 通信端点——A2A 协议的核心接口。
 * <p>
 * 每个 Agent 实现此接口，定义自己能处理的消息类型和返回格式。
 * 主 Agent（分诊 Agent）通过此接口与团队 Agent 通信。
 * <p>
 * 通信模型：
 * - 主 Agent 发送请求（如：请团队X排查某工单）
 * - 团队 Agent 返回结果（如：证据包 + 参考诊断）
 * - 通信通过 Kafka 异步，不阻塞主流程
 * <p>
 * TODO: 后续可切换为 LangChain4j A2A 模块（标准化的 Agent 间通信）
 */
public interface AgentEndpoint {

    /**
     * Agent 标识（如 "triage-agent", "db-team-agent"）
     */
    String agentId();

    /**
     * 处理来自其他 Agent 的消息
     *
     * @param message 消息
     * @return 响应消息（null 表示无需响应）
     */
    AgentMessage handle(AgentMessage message);

    /**
     * 此 Agent 能处理的 action 列表
     */
    default String[] supportedActions() {
        return new String[0];
    }
}
