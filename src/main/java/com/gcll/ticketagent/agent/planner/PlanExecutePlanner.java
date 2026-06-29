package com.gcll.ticketagent.agent.planner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Plan-and-Execute 规划器（阶段3）：LLM 先规划有序步骤序列，按计划顺序执行证据收集。
 *
 * <p>激活条件：{@code opsmind.agent.planner-strategy=plan-execute}（默认走 SpringAiAgentPlanner）。
 * 与 {@link SpringAiAgentPlanner}（@Primary）共存：本类不是 Primary，通过配置开关显式启用时，
 * 由调用方（AnalysisWorkflowService）优先选择本规划器。
 *
 * <p><b>与现有 planner 的关系</b>：
 * <ul>
 *   <li>{@link SpringAiAgentPlanner}：输出无序动作集合（actions/skipped），"做不做某动作"</li>
 *   <li>本类：输出有序步骤 + 每步目标（{@link ExecutionPlan}），"按什么顺序做、为什么"</li>
 * </ul>
 *
 * <p><b>计划如何驱动执行（重构后）</b>：
 * AnalysisWorkflowService.executeEvidenceByPlan 遍历计划步骤，按 action 类型分派执行
 * （KNOWLEDGE_SEARCH→知识检索，QUERY_LOGS→查日志，QUERY_METRIC→查指标），
 * 每步执行后标记 DONE 并持久化——支持断点续跑（从第一个 PENDING 继续，跳过已 DONE 的）。
 *
 * <p><b>降级</b>：LLM 规划失败/输出非法 → 回退到 {@link SpringAiAgentPlanner}（把它输出的
 * AgentPlan 转成单步 ExecutionPlan），保证 plan-execute 出问题不致命。
 *
 * <p><b>与 ReAct（阶段2）的关系</b>：两者是不同范式。Plan-and-Execute = 先全局规划再执行
 * （可控，linear 模式用），ReAct = 边走边看（LLM 自主，react 模式用）。同一时间只走一种。
 */
@Service
@ConditionalOnProperty(prefix = "opsmind.agent", name = "planner-strategy", havingValue = "plan-execute")
@Order(0)
public class PlanExecutePlanner {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutePlanner.class);

    private final LlmCallExecutor llmCallExecutor;
    private final StructuredOutputParser parser;
    private final ObjectMapper objectMapper;
    private final SpringAiAgentPlanner fallback;

    public PlanExecutePlanner(
            LlmCallExecutor llmCallExecutor,
            StructuredOutputParser parser,
            ObjectMapper objectMapper,
            SpringAiAgentPlanner fallback
    ) {
        this.llmCallExecutor = llmCallExecutor;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
    }

    /**
     * 规划有序调查步骤。
     *
     * @param runId       工单运行 ID（关联 agent_run）
     * @param userContent 工单原文
     * @param extract     结构化抽取结果
     * @return 有序执行计划（含步骤、目标、规划理由）；失败回退单步计划
     */
    public ExecutionPlan plan(String runId, String userContent, TicketExtractResult extract) {
        // 咨询类不需要复杂规划，走兜底（现有逻辑：CONSULT 走 RuleBasedAgentPlanner）
        if (extract.issueType() == IssueType.CONSULT) {
            return fallbackPlan(runId, userContent, extract);
        }
        try {
            String input = buildInput(userContent, extract);
            CallResult<LlmResponse> result = llmCallExecutor.execute(
                    "llm.plan-execute", "plan-execute.txt", input);
            if (!result.success()) {
                log.info("Plan-Execute LLM failed, fallback to SpringAiAgentPlanner, runId={}", runId);
                return fallbackPlan(runId, userContent, extract);
            }
            PlanJson json = parser.parse(result.value().content(), PlanJson.class);
            ExecutionPlan plan = buildPlan(runId, json);
            if (plan.getSteps().isEmpty()) {
                log.info("Plan-Execute produced empty steps, fallback, runId={}", runId);
                return fallbackPlan(runId, userContent, extract);
            }
            log.info("Plan-Execute done, runId={}, steps={}, rationale={}",
                    runId, plan.getSteps().size(), plan.getRationale());
            return plan;
        } catch (Exception ex) {
            log.warn("Plan-Execute failed, fallback, runId={}: {}", runId, ex.getMessage());
            return fallbackPlan(runId, userContent, extract);
        }
    }

    /** 把 LLM 输出的 JSON 转成 ExecutionPlan。 */
    private ExecutionPlan buildPlan(String runId, PlanJson json) {
        List<ExecutionPlan.PlanStep> steps = new ArrayList<>();
        if (json.steps() != null) {
            int idx = 1;
            for (StepJson sj : json.steps()) {
                AgentAction action = AgentAction.fromName(sj.action());
                if (action == null) {
                    log.warn("Plan-Execute step has unknown action, skipped: {}", sj.action());
                    continue;
                }
                String goal = sj.goal() == null || sj.goal().isBlank()
                        ? action.name() : sj.goal();
                steps.add(new ExecutionPlan.PlanStep("step-" + idx, action, goal));
                idx++;
            }
        }
        String rationale = json.rationale() == null ? "LLM plan-execute" : json.rationale();
        return new ExecutionPlan(runId, steps, rationale, true);
    }

    /**
     * 回退：用 SpringAiAgentPlanner 出 AgentPlan，转成单步 ExecutionPlan。
     * 保证 plan-execute 失败时仍有可用计划。
     */
    private ExecutionPlan fallbackPlan(String runId, String userContent, TicketExtractResult extract) {
        StepOutcome<AgentPlan> outcome = fallback.plan(userContent, extract);
        AgentPlan ap = outcome.value();
        List<ExecutionPlan.PlanStep> steps = new ArrayList<>();
        int idx = 1;
        for (AgentAction action : ap.actions()) {
            steps.add(new ExecutionPlan.PlanStep(
                    "step-" + idx, action, action.name() + " (fallback)"));
            idx++;
        }
        return new ExecutionPlan(runId, steps, ap.reason(), ap.llmUsed());
    }

    private String buildInput(String userContent, TicketExtractResult extract) throws Exception {
        return """
                抽取结果 JSON：
                %s

                用户原文：
                %s
                """.formatted(objectMapper.writeValueAsString(extract), userContent);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record PlanJson(
            @JsonProperty("steps") List<StepJson> steps,
            @JsonProperty("rationale") String rationale
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StepJson(
            @JsonProperty("action") String action,
            @JsonProperty("goal") String goal
    ) {}
}
