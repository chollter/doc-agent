package com.gcll.ticketagent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A2A 协调器——排查流程中调用团队 Agent 的入口。
 *
 * <h3>两种调用模式</h3>
 * <ol>
 *   <li><b>同步调用（同进程）</b>：直接调 {@link AgentEndpoint#handle}，立即获取响应。
 *       适用于主 Agent 和团队 Agent 在同一进程的场景（当前项目默认部署模式）。</li>
 *   <li><b>异步调用（跨进程）</b>：通过 {@link AgentMessageBus#send} 发 Kafka 消息，
 *       团队 Agent 各自消费处理。适用于团队 Agent 独立部署的场景。</li>
 * </ol>
 *
 * <p>当前默认用同步模式（更简单、更可控），异步模式由 {@link AgentMessageConsumer} 支持。
 *
 * <h3>排查流程中的调用方式</h3>
 * <pre>
 * // 在 LinearInvestigationStrategy 中：
 * A2aCoordinationResult result = a2aCoordinator.requestInvestigation(
 *     "db-team-agent", runId, triageResult, extract);
 * if (result.success()) {
 *     // 合并团队 Agent 返回的额外证据
 *     evidenceSummary += result.evidence();
 * }
 * </pre>
 */
@Component
public class A2ACoordinator {

    private static final Logger log = LoggerFactory.getLogger(A2ACoordinator.class);

    private final Map<String, AgentEndpoint> endpointMap;
    private final ObjectMapper objectMapper;

    public A2ACoordinator(List<AgentEndpoint> endpoints, ObjectMapper objectMapper) {
        this.endpointMap = endpoints.stream()
                .collect(Collectors.toMap(AgentEndpoint::agentId, Function.identity()));
        this.objectMapper = objectMapper;
        log.info("A2A 协调器已注册团队Agent: {}", endpointMap.keySet());
    }

    /**
     * 同步请求团队 Agent 协助排查。
     *
     * @param teamAgentId  团队 Agent ID（如 "db-team-agent"）
     * @param runId        工单 ID
     * @param triageResult 分诊结果（提取系统信息用于排查）
     * @param extract      结构化抽取
     * @return 协调结果
     */
    public A2aCoordinationResult requestInvestigation(
            String teamAgentId, String runId,
            com.gcll.ticketagent.triage.TriageResult triageResult,
            com.gcll.ticketagent.extract.TicketExtractResult extract) {

        AgentEndpoint endpoint = endpointMap.get(teamAgentId);
        if (endpoint == null) {
            log.warn("A2A团队Agent不存在, teamAgentId={}, runId={}", teamAgentId, runId);
            return A2aCoordinationResult.failed("Agent not found: " + teamAgentId);
        }

        try {
            // 构造请求消息
            String payload = buildRequestPayload(triageResult, extract);
            AgentMessage request = AgentMessage.of(
                    "triage-agent", teamAgentId,
                    "REQUEST_INVESTIGATION", payload, runId
            );

            log.info("A2A同步调用, from=triage-agent, to={}, action=REQUEST_INVESTIGATION, runId={}",
                    teamAgentId, runId);

            // 同步调用（同进程，直接 handle）
            AgentMessage response = endpoint.handle(request);

            if (response == null) {
                return A2aCoordinationResult.failed("Agent returned null: " + teamAgentId);
            }

            return A2aCoordinationResult.success(
                    response.senderId(),
                    response.payload(),
                    extractEvidenceFromPayload(response.payload())
            );

        } catch (Exception ex) {
            log.error("A2A协调失败, teamAgentId={}, runId={}", teamAgentId, runId, ex);
            return A2aCoordinationResult.failed(ex.getMessage());
        }
    }

    /**
     * 检查团队 Agent 是否可用。
     */
    public boolean isAgentAvailable(String teamAgentId) {
        return endpointMap.containsKey(teamAgentId);
    }

    /**
     * 获取所有可用团队 Agent ID。
     */
    public List<String> availableAgents() {
        return List.copyOf(endpointMap.keySet());
    }

    private String buildRequestPayload(
            com.gcll.ticketagent.triage.TriageResult triageResult,
            com.gcll.ticketagent.extract.TicketExtractResult extract) {
        try {
            Map<String, Object> payload = Map.of(
                    "issueType", triageResult.issueType().name(),
                    "priority", triageResult.priority().name(),
                    "affectedSystem", extract.affectedSystem() != null ? extract.affectedSystem() : "unknown",
                    "affectedModule", extract.affectedModule() != null ? extract.affectedModule() : "unknown",
                    "routedTeam", triageResult.routedTeam() != null ? triageResult.routedTeam() : "unassigned",
                    "requestTime", System.currentTimeMillis()
            );
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{\"error\":\"payload build failed\"}";
        }
    }

    /**
     * 从证据 payload 中提取摘要。
     */
    private String extractEvidenceFromPayload(String payload) {
        if (payload == null || payload.isBlank()) return "";
        try {
            var node = objectMapper.readTree(payload);
            if (node.has("error")) {
                return "团队排查失败: " + node.get("error").asText();
            }
            StringBuilder sb = new StringBuilder();
            if (node.has("team")) sb.append("[").append(node.get("team").asText()).append("] ");
            if (node.has("findings")) {
                var findings = node.get("findings");
                if (findings.isArray()) {
                    for (var f : findings) {
                        sb.append("- ").append(f.asText()).append("\n");
                    }
                }
            }
            if (node.has("suggestion")) {
                sb.append("建议: ").append(node.get("suggestion").asText());
            }
            return sb.toString().trim();
        } catch (Exception ex) {
            return payload.length() > 200 ? payload.substring(0, 200) + "..." : payload;
        }
    }

    /**
     * A2A 协调结果。
     */
    public record A2aCoordinationResult(
            boolean success,
            String agentId,
            String rawPayload,
            String evidenceSummary,
            String error
    ) {
        static A2aCoordinationResult success(String agentId, String rawPayload, String evidenceSummary) {
            return new A2aCoordinationResult(true, agentId, rawPayload, evidenceSummary, null);
        }

        static A2aCoordinationResult failed(String error) {
            return new A2aCoordinationResult(false, null, null, null, error);
        }
    }
}
