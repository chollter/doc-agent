package com.gcll.ticketagent.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.agent.planner.AgentPlan;
import com.gcll.ticketagent.agent.planner.ExecutionPlan;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AgentRunContextPersister {

    private static final Logger log = LoggerFactory.getLogger(AgentRunContextPersister.class);

    private final ObjectMapper objectMapper;
    private final AgentRunRepository agentRunRepository;

    public AgentRunContextPersister(ObjectMapper objectMapper, AgentRunRepository agentRunRepository) {
        this.objectMapper = objectMapper;
        this.agentRunRepository = agentRunRepository;
    }

    public void persist(AgentRun run, InfoGapAnalysis gap, AgentPlan plan, ToolSelection selection) {
        try {
            if (gap != null) {
                run.setGapAnalysisJson(objectMapper.writeValueAsString(gap));
            }
            if (plan != null) {
                run.setAgentPlanJson(objectMapper.writeValueAsString(plan));
            }
            if (selection != null) {
                run.setToolSelectionJson(objectMapper.writeValueAsString(selection));
            }
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize agent context, runId={}, error={}", run.getId(), ex.getMessage());
        }
    }

    /**
     * 阶段3：持久化有序执行计划。复用 agentPlanJson 字段（存 ExecutionPlan 的 JSON），
     * 不新增 schema 列。断点续跑时从这里反序列化恢复未完成的步骤。
     *
     * <p><b>修复</b>：此前只 setAgentPlanJson 改了内存对象，没调 save 落盘——
     * 应用崩溃后 DB 里还是初始状态，断点续跑会重跑所有步骤。现加 save 确保每步状态落盘。
     */
    public void persistExecutionPlan(AgentRun run, ExecutionPlan plan) {
        if (plan == null) {
            return;
        }
        try {
            run.setAgentPlanJson(objectMapper.writeValueAsString(plan));
            agentRunRepository.save(run);  // ★ 落盘——此前漏了这行
        } catch (Exception ex) {
            log.warn("Failed to persist execution plan, runId={}, error={}", run.getId(), ex.getMessage());
        }
    }

    /**
     * 阶段3：从 agentPlanJson 反序列化恢复 ExecutionPlan（断点续跑用）。
     * 若 JSON 不是 ExecutionPlan 结构（旧格式 AgentPlan），返回 null 由调用方按线性重跑。
     */
    public ExecutionPlan loadExecutionPlan(AgentRun run) {
        String json = run.getAgentPlanJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ExecutionPlan.class);
        } catch (Exception ex) {
            // 可能是旧格式 AgentPlan JSON，反序列化失败属正常
            log.debug("Cannot load ExecutionPlan from agentPlanJson (maybe legacy AgentPlan), runId={}", run.getId());
            return null;
        }
    }
}
