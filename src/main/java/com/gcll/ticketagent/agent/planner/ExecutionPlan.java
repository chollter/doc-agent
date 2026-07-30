package com.gcll.ticketagent.agent.planner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * 有序执行计划（阶段3 Plan-and-Execute）。
 *
 * <p>与 {@link AgentPlan} 的区别：
 * <ul>
 *   <li>{@code AgentPlan}：无序动作集合（actions + skipped），只回答"做不做某动作"</li>
 *   <li>{@code ExecutionPlan}：有序步骤序列，每步有明确目标与执行状态，可动态调整</li>
 * </ul>
 *
 * <p>Plan-and-Execute 范式：LLM 先全局规划出完整步骤序列（Plan），执行时按计划顺序逐个
 * 执行证据收集（Execute）。比 ReAct（边走边看）更可控，符合"不依赖 LLM 自由判断、先规划
 * 再执行"的工程化主线。
 *
 * <p><b>状态机</b>：每个 step 有 PENDING/RUNNING/DONE/SKIPPED/FAILED 状态，支持断点续跑
 * （从第一个 PENDING 步继续，不重跑已 DONE 的）。LinearInvestigationStrategy 遍历步骤、
 * 按 action 分派执行、每步完成后 markStep(DONE) 并持久化。
 */
public class ExecutionPlan {

    private String planId;
    /** 工单运行 ID，用于关联 agent_run。 */
    private String runId;
    /** 有序步骤序列（按执行顺序）。 */
    private List<PlanStep> steps;
    /** 规划理由（LLM 解释为什么这么排）。 */
    private String rationale;
    /** 是否用 LLM 规划（false=规则兜底）。 */
    private boolean llmPlanned;

    public ExecutionPlan() {
        this.steps = new ArrayList<>();
    }

    public ExecutionPlan(String runId, List<PlanStep> steps, String rationale, boolean llmPlanned) {
        this.runId = runId;
        this.steps = steps == null ? new ArrayList<>() : new ArrayList<>(steps);
        this.rationale = rationale;
        this.llmPlanned = llmPlanned;
    }

    /** 找第一个待执行的步骤（PENDING），用于断点续跑。 */
    public PlanStep firstPending() {
        return steps.stream()
                .filter(s -> s.status() == StepStatus.PENDING)
                .findFirst()
                .orElse(null);
    }

    /** 标记某步状态（按 stepId）。 */
    public void markStep(String stepId, StepStatus status) {
        for (PlanStep step : steps) {
            if (step.stepId().equals(stepId)) {
                step.setStatus(status);
                return;
            }
        }
    }

    /** 是否全部完成（无 PENDING/RUNNING）。 */
    public boolean allDone() {
        return steps.stream().allMatch(s ->
                s.status() == StepStatus.DONE || s.status() == StepStatus.SKIPPED);
    }

    /** 兼容现有 AgentPlan：把有序 steps 投影成动作集合（用于 knowledge/tool 查询）。 */
    public AgentPlan toAgentPlan() {
        List<AgentAction> actions = new ArrayList<>();
        List<AgentAction> skipped = new ArrayList<>();
        for (PlanStep step : steps) {
            if (step.action() != null) {
                if (step.status() == StepStatus.SKIPPED) {
                    skipped.add(step.action());
                } else {
                    actions.add(step.action());
                }
            }
        }
        return new AgentPlan(actions, skipped, rationale, llmPlanned);
    }

    public String getPlanId() { return planId; }
    public void setPlanId(String planId) { this.planId = planId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public List<PlanStep> getSteps() { return steps; }
    public void setSteps(List<PlanStep> steps) { this.steps = steps; }
    public String getRationale() { return rationale; }
    public void setRationale(String rationale) { this.rationale = rationale; }
    public boolean isLlmPlanned() { return llmPlanned; }
    public void setLlmPlanned(boolean llmPlanned) { this.llmPlanned = llmPlanned; }

    /** 计划中的一个步骤。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PlanStep {
        @JsonProperty("stepId")
        private String stepId;
        @JsonProperty("action")
        private AgentAction action;
        /** 该步的目标（为什么做这步、预期得到什么）。 */
        @JsonProperty("goal")
        private String goal;
        @JsonProperty("status")
        private StepStatus status;

        public PlanStep() { this.status = StepStatus.PENDING; }

        public PlanStep(String stepId, AgentAction action, String goal) {
            this.stepId = stepId;
            this.action = action;
            this.goal = goal;
            this.status = StepStatus.PENDING;
        }

        public String stepId() { return stepId; }
        public AgentAction action() { return action; }
        public String goal() { return goal; }
        public StepStatus status() { return status; }
        public void setStatus(StepStatus status) { this.status = status; }

        public String getStepId() { return stepId; }
        public void setStepId(String stepId) { this.stepId = stepId; }
        public AgentAction getAction() { return action; }
        public void setAction(AgentAction action) { this.action = action; }
        public String getGoal() { return goal; }
        public void setGoal(String goal) { this.goal = goal; }
        public StepStatus getStatus() { return status; }
        public void setStatusPublic(StepStatus status) { this.status = status; }
    }

    /** 步骤状态。 */
    public enum StepStatus {
        PENDING,    // 待执行
        RUNNING,    // 执行中
        DONE,       // 完成
        SKIPPED,    // 跳过（规划后判断不需要）
        FAILED      // 失败（可降级继续）
    }
}
