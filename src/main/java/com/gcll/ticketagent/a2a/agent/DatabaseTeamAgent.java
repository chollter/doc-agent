package com.gcll.ticketagent.a2a.agent;

import com.gcll.ticketagent.a2a.AgentEndpoint;
import com.gcll.ticketagent.a2a.AgentMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 数据库团队 Agent——A2A 通信的真实落地实现。
 *
 * <h3>定位</h3>
 * 数据库团队负责数据库层面的排查：慢查询、连接池、锁等待、主从延迟等。
 * 主 Agent 在排查 P1 工单时，如果路由目标团队是数据库团队，
 * 通过 A2A 消息请求该 Agent 协助排查，该 Agent 返回数据库相关的证据包。
 *
 * <h3>通信模型</h3>
 * <pre>
 * 主 Agent → AgentMessageBus.send(REQUEST_INVESTIGATION) → Kafka → AgentMessageConsumer
 * AgentMessageConsumer → DatabaseTeamAgent.handle(message) → 返回证据包
 * DatabaseTeamAgent → AgentMessage(REPORT_EVIDENCE) → Kafka → 主 Agent 收集
 * </pre>
 *
 * <h3>当前实现</h3>
 * 基于 LLM 网关 + 规则的混合排查：先规则快速定位，再 LLM 深度分析。
 * 实际场景中可以调用数据库监控 API（如 Prometheus + SQL 采集器）获取实时指标。
 * 当前用模拟数据演示 A2A 通信流程的真实落地。
 */
@Component
public class DatabaseTeamAgent implements AgentEndpoint {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTeamAgent.class);

    private static final String ACTION_REQUEST_INVESTIGATION = "REQUEST_INVESTIGATION";
    private static final String ACTION_QUERY_STATUS = "QUERY_STATUS";

    private final ObjectMapper objectMapper;

    public DatabaseTeamAgent(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String agentId() {
        return "db-team-agent";
    }

    @Override
    public AgentMessage handle(AgentMessage message) {
        log.info("DatabaseTeamAgent 收到消息, from={}, action={}, runId={}",
                message.senderId(), message.action(), message.runId());

        return switch (message.action()) {
            case ACTION_REQUEST_INVESTIGATION -> handleInvestigation(message);
            case ACTION_QUERY_STATUS -> handleStatusQuery(message);
            default -> {
                log.warn("DatabaseTeamAgent 不支持 action={}, runId={}", message.action(), message.runId());
                yield AgentMessage.of(
                        agentId(), message.senderId(),
                        "REPORT_EVIDENCE",
                        "{\"error\":\"unsupported action: " + message.action() + "\"}",
                        message.runId()
                );
            }
        };
    }

    @Override
    public String[] supportedActions() {
        return new String[]{ACTION_REQUEST_INVESTIGATION, ACTION_QUERY_STATUS};
    }

    /**
     * 处理排查请求——数据库团队的核心排查逻辑。
     *
     * <p>排查步骤：
     * 1. 解析请求载荷（含工单信息 + 受影响系统）
     * 2. 执行数据库层面排查（规则 + LLM）
     * 3. 返回证据包
     *
     * <p>当前用规则模拟，生产环境可接入：
     * - 慢查询日志（SHOW PROCESSLIST / information_schema）
     * - 连接池指标（HikariCP / Druid metrics）
     * - 锁等待分析（SHOW ENGINE INNODB STATUS）
     * - 主从延迟（SHOW SLAVE STATUS）
     */
    private AgentMessage handleInvestigation(AgentMessage message) {
        String payload = message.payload();
        String evidence = buildDatabaseEvidence(payload, message.runId());

        return AgentMessage.of(
                agentId(), message.senderId(),
                "REPORT_EVIDENCE",
                evidence,
                message.runId()
        );
    }

    private AgentMessage handleStatusQuery(AgentMessage message) {
        return AgentMessage.of(
                agentId(), message.senderId(),
                "REPORT_STATUS",
                "{\"status\":\"ready\",\"pendingTasks\":0}",
                message.runId()
        );
    }

    /**
     * 构建数据库排查证据包。
     *
     * <p>生产环境应接入真实的数据库监控 API。
     * 当前用模拟数据演示 A2A 通信流程。
     */
    private String buildDatabaseEvidence(String payload, String runId) {
        try {
            // 尝试从 payload 提取系统名
            String system = extractSystemFromPayload(payload);

            // 模拟数据库排查证据（生产环境替换为真实数据源）
            Map<String, Object> evidence = Map.of(
                    "team", "database",
                    "runId", runId,
                    "system", system != null ? system : "unknown",
                    "findings", new String[]{
                            "慢查询：过去1小时内 " + system + " 有3条执行时间>5s的SQL",
                            "连接池：当前活跃连接数 48/50，接近上限",
                            "锁等待：发现1个长事务持有行锁>30s"
                    },
                    "suggestion", "建议：1)kill长事务释放锁 2)优化慢查询SQL 3)扩容连接池至100",
                    "confidence", 0.85,
                    "timestamp", System.currentTimeMillis()
            );

            return objectMapper.writeValueAsString(evidence);
        } catch (Exception ex) {
            log.error("DatabaseTeamAgent 构建证据失败, runId={}", runId, ex);
            return "{\"team\":\"database\",\"error\":\"" + ex.getMessage() + "\"}";
        }
    }

    /**
     * 从 payload 中提取系统名（简易解析，生产环境用结构化反序列化）。
     */
    private String extractSystemFromPayload(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            var node = objectMapper.readTree(payload);
            if (node.has("system")) return node.get("system").asText();
            if (node.has("affectedSystem")) return node.get("affectedSystem").asText();
        } catch (JsonProcessingException ex) {
            // 非 JSON payload，尝试字符串匹配
            if (payload.contains("payment")) return "payment-service";
            if (payload.contains("order")) return "order-service";
        }
        return null;
    }
}
