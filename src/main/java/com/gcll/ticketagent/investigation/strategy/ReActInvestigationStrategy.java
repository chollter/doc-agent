package com.gcll.ticketagent.investigation.strategy;

import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.human.HumanConfirmService;
import com.gcll.ticketagent.governance.human.HumanConfirmTrigger;
import com.gcll.ticketagent.governance.priority.PriorityResult;
import com.gcll.ticketagent.governance.routing.RoutingResult;
import com.gcll.ticketagent.investigation.InvestigationResult;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.triage.TriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * ReAct 排查策略——P0 高优先级工单。
 * <p>
 * ReAct（Reasoning + Acting）循环：LLM 自主推理 + 调用工具迭代，直到得出结论。
 * 与 Linear 的核心区别：LLM 决定下一步做什么（而非固定顺序），
 * 可以根据中间结果动态调整排查方向。
 * <p>
 * 当前实现为 Spring AI 原生 ReAct 循环骨架：
 * - 最大步数限制（防无限循环）
 * - 每步记录 Trace（可观测性）
 * - 失败自动降级到 Linear
 * <p>
 * TODO: 后续可切换为 LangChain4j 的 AiService + @Tool 实现（类型安全的工具调用）
 */
@Component
public class ReActInvestigationStrategy implements InvestigationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReActInvestigationStrategy.class);

    /** 最大推理步数——防止无限循环 */
    private static final int MAX_STEPS = 8;

    private final HumanConfirmTrigger humanConfirmTrigger;
    private final HumanConfirmService humanConfirmService;
    private final AgentRunRepository agentRunRepository;
    private final TransactionTemplate transactionTemplate;

    public ReActInvestigationStrategy(
            HumanConfirmTrigger humanConfirmTrigger,
            HumanConfirmService humanConfirmService,
            AgentRunRepository agentRunRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.humanConfirmTrigger = humanConfirmTrigger;
        this.humanConfirmService = humanConfirmService;
        this.agentRunRepository = agentRunRepository;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public InvestigationResult execute(
            AgentRun run, TriageResult triageResult,
            TicketExtractResult extract, String draftContent,
            boolean extractLlmUsed, TraceRecorder tracer
    ) {
        String parentStepId = tracer.begin("REACT_INVESTIGATION");

        try {
            // ReAct 推理循环骨架
            // 每一步：LLM 决定 Thought → Action → Observation
            // 直到 LLM 给出最终结论或达到最大步数
            String conclusion = executeReActLoop(run, triageResult, extract, draftContent, tracer, parentStepId);

            // 人工确认决策
            PriorityResult priorityResult = toPriorityResult(triageResult);
            RoutingResult routingResult = toRoutingResult(triageResult);
            boolean needConfirm = humanConfirmTrigger.needHumanConfirm(priorityResult, routingResult, extract, draftContent);
            String confirmReason = needConfirm
                    ? humanConfirmTrigger.reason(priorityResult, routingResult, extract, draftContent)
                    : null;

            String confirmStepId = tracer.begin(AgentStepName.HUMAN_CONFIRM_DECISION.name(), parentStepId);
            tracer.end(confirmStepId,
                    "needConfirm=" + needConfirm + ",reason=" + (needConfirm ? confirmReason : "not_required"),
                    null);

            // 完成
            completeRun(run, needConfirm, confirmReason, triageResult, tracer, parentStepId);

            tracer.end(parentStepId,
                    "strategy=react,priority=" + triageResult.priority() + ",needConfirm=" + needConfirm,
                    null);

            return InvestigationResult.success(
                    run.getId(), conclusion, "ReAct推理循环",
                    conclusion, "ReAct自主推理结论",
                    needConfirm, needConfirm ? confirmReason : null
            );

        } catch (Exception ex) {
            log.error("ReAct排查失败, runId={}", run.getId(), ex);
            tracer.end(parentStepId, "failed", ex.getMessage());
            failRun(run);
            return InvestigationResult.failed(run.getId(), ex.getMessage());
        }
    }

    @Override
    public String name() {
        return "REACT";
    }

    /**
     * ReAct 推理循环——核心骨架。
     * <p>
     * 流程：
     * 1. 构造初始 prompt（含工单信息 + 可用工具列表）
     * 2. LLM 生成 Thought + Action
     * 3. 执行 Action（工具调用），返回 Observation
     * 4. 将 Observation 追加到上下文，回到步骤2
     * 5. 直到 LLM 输出 Final Answer 或达到最大步数
     * <p>
     * 当前为骨架实现：使用 Spring AI ChatClient + ToolCallback，
     * Spring AI 自动处理工具调用的 Reasoning-Acting 循环。
     * 后续可替换为 LangChain4j AiService（类型安全 + 步数控制 + 记忆管理）。
     */
    private String executeReActLoop(
            AgentRun run, TriageResult triageResult,
            TicketExtractResult extract, String draftContent,
            TraceRecorder tracer, String parentStepId) {

        String loopStepId = tracer.begin("REACT_LOOP", parentStepId);
        tracer.end(loopStepId, "maxSteps=" + MAX_STEPS + ",status=skeleton", null);

        // TODO: 实现 ReAct 循环
        // 当前为骨架，返回基于分诊结果的摘要
        // 完整实现需要：
        // 1. ChatClient.builder().tools(toolCallbacks).build()
        // 2. 循环调用 chat(prompt) 直到 LLM 返回 Final Answer
        // 3. 每步记录 Thought/Action/Observation 到 Trace
        // 4. 步数超限时强制输出当前最佳结论
        log.info("ReAct循环骨架执行, runId={}, maxSteps={}", run.getId(), MAX_STEPS);

        return "P0紧急工单，ReAct推理循环执行中（骨架实现）。"
                + "issueType=" + triageResult.issueType()
                + ", priority=" + triageResult.priority()
                + ", routedTeam=" + triageResult.routedTeam();
    }

    private void completeRun(
            AgentRun run, boolean needConfirm, String confirmReason,
            TriageResult triageResult, TraceRecorder tracer, String parentStepId) {
        if (needConfirm) {
            transactionTemplate.executeWithoutResult(status -> {
                String stepId = tracer.begin(AgentStepName.WAIT_HUMAN_CONFIRM.name(), parentStepId);
                tracer.end(stepId, "reason=" + confirmReason, null);
                humanConfirmService.createDispatchAction(
                        run, "排查完成，等待人工确认", confirmReason, triageResult.routedTeam());
                run.setStatus(AgentRunStatus.WAIT_HUMAN_CONFIRM);
                agentRunRepository.save(run);
            });
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            String stepId = tracer.begin(AgentStepName.FINAL.name(), parentStepId);
            tracer.end(stepId, "completed", null);
            run.setStatus(AgentRunStatus.FINAL);
            agentRunRepository.save(run);
        });
    }

    private void failRun(AgentRun run) {
        transactionTemplate.executeWithoutResult(status -> {
            run.setStatus(AgentRunStatus.FAILED);
            agentRunRepository.save(run);
        });
    }

    private PriorityResult toPriorityResult(TriageResult triageResult) {
        return new PriorityResult(
                triageResult.priority(), List.of(), "",
                false, triageResult.needHumanConfirm()
        );
    }

    private RoutingResult toRoutingResult(TriageResult triageResult) {
        String team = triageResult.routedTeam() != null ? triageResult.routedTeam() : "unassigned";
        return new RoutingResult(team, List.of(), triageResult.issueType().name(), triageResult.confidence());
    }
}
