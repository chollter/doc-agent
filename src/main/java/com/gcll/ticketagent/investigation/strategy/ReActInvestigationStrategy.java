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
import com.gcll.ticketagent.langchain4j.ReActAssistant;
import com.gcll.ticketagent.langchain4j.ReActContextHolder;
import com.gcll.ticketagent.langchain4j.ReActToolProvider;
import com.gcll.ticketagent.langchain4j.ReActTraceHolder;
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
 * 实现基于 LangChain4j AiService + @Tool：
 * <ul>
 *   <li>AiService 自动处理 Thought → Action → Observation 循环</li>
 *   <li>@Tool 方法通过 {@link ReActToolProvider} 适配现有 {@link com.gcll.ticketagent.tool.ToolGateway}</li>
 *   <li>步数限制由 AiServices.builder().maxToolCallingRoundTrips() 控制（{@code MAX_TOOL_CALLING_ROUNDS=8}）</li>
 *   <li>每步记录 Trace（可观测性）</li>
 *   <li>失败自动降级到 Linear 策略（真正调用 LinearInvestigationStrategy.execute()）</li>
 * </ul>
 * <p>
 * LangChain4j 与 Spring AI Alibaba 共存：
 * Spring AI 管分诊/抽取/建议等流程调用，LangChain4j 管 ReAct 自主推理+工具迭代。
 * 两者通过 OpenAI 兼容协议接入同一个 dashscope 模型，互不冲突。
 */
@Component
public class ReActInvestigationStrategy implements InvestigationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReActInvestigationStrategy.class);

    /** 最大推理步数——防止无限循环 */
    private static final int MAX_STEPS = 8;

    private final ReActAssistant reActAssistant;
    private final HumanConfirmTrigger humanConfirmTrigger;
    private final HumanConfirmService humanConfirmService;
    private final AgentRunRepository agentRunRepository;
    private final TransactionTemplate transactionTemplate;
    /** 降级策略——ReAct 失败时调用 Linear 策略重新排查 */
    private final LinearInvestigationStrategy linearStrategy;

    public ReActInvestigationStrategy(
            ReActAssistant reActAssistant,
            HumanConfirmTrigger humanConfirmTrigger,
            HumanConfirmService humanConfirmService,
            AgentRunRepository agentRunRepository,
            TransactionTemplate transactionTemplate,
            LinearInvestigationStrategy linearStrategy
    ) {
        this.reActAssistant = reActAssistant;
        this.humanConfirmTrigger = humanConfirmTrigger;
        this.humanConfirmService = humanConfirmService;
        this.agentRunRepository = agentRunRepository;
        this.transactionTemplate = transactionTemplate;
        this.linearStrategy = linearStrategy;
    }

    @Override
    public InvestigationResult execute(
            AgentRun run, TriageResult triageResult,
            TicketExtractResult extract, String draftContent,
            boolean extractLlmUsed, TraceRecorder tracer
    ) {
        String parentStepId = tracer.begin("REACT_INVESTIGATION");

        try {
            // ReAct 推理循环（LangChain4j AiService 驱动）
            // 返回 null 表示已降级到 Linear（Linear 自己做了完整 execute，含人工确认+状态更新）
            String conclusion = executeReActLoop(run, triageResult, extract, draftContent, tracer, parentStepId);

            // 降级到 Linear——executeReActLoop 已调用 linearStrategy.execute() 并返回 null
            if (conclusion == null) {
                tracer.end(parentStepId, "strategy=react→linear_degraded", null);
                // 返回一个降级标记结果（run 状态已由 Linear 更新）
                return InvestigationResult.success(
                        run.getId(), "ReAct降级到Linear排查完成", "Linear排查证据",
                        "ReAct降级，Linear排查结论", "ReAct降级，Linear排查建议",
                        false, null
                );
            }

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
     * ReAct 推理循环——LangChain4j AiService 驱动的真实实现。
     * <p>
     * 流程：
     * 1. 构造系统提示词（含工单上下文 + 排查指令 + 输出格式）
     * 2. 设置 ThreadLocal 上下文（系统提示词 + extract + originalContent）
     * 3. 调用 AiService.investigate()，LangChain4j 自动执行：
     *    - LLM 生成 Thought + Action
     *    - 执行 Action（调用 @Tool 方法）
     *    - 返回 Observation，追加到上下文
     *    - 循环直到 Final Answer 或 MAX_STEPS
     * 4. 清除 ThreadLocal
     * 5. 每步记录 Trace
     * <p>
     * AiService 内部已处理步数限制（maxToolCallingRoundTrips=8）和超时，
     * 此处额外做：Trace 记录 + 失败降级。
     *
     * @return ReAct 结论；null 表示已降级到 Linear（Linear 已完成完整 execute）
     */
    private String executeReActLoop(
            AgentRun run, TriageResult triageResult,
            TicketExtractResult extract, String draftContent,
            TraceRecorder tracer, String parentStepId) {

        String loopStepId = tracer.begin("REACT_LOOP", parentStepId);

        try {
            // 1. 构造系统提示词并设置到 ThreadLocal（systemMessageProvider 会读取）
            String systemPrompt = buildSystemPrompt(triageResult, extract);
            ReActContextHolder.setSystemPrompt(systemPrompt);

            // 2. 设置工具上下文（ReActToolProvider 的 @Tool 方法会读取）
            //    同时传递 TraceRecorder + loopStepId，用于步级 Trace 记录
            ReActToolProvider.setContext(extract, draftContent, tracer, loopStepId);

            // 3. 设置 Trace 上下文（AiService Listener 会读取）
            //    Listener（ToolExecutedEventListener / ResponseReceivedListener）
            //    通过 ReActTraceHolder 获取 tracer + parentStepId，记录子 Span
            ReActTraceHolder.set(tracer, loopStepId);

            // 4. 调用 AiService（LangChain4j 自动执行 ReAct 循环）
            String loopTraceStepId = tracer.begin("REACT_LLM_INVOKE", loopStepId);
            tracer.recordMeta(loopTraceStepId, true, "LangChain4j");

            String userMessage = buildUserMessage(draftContent);
            String conclusion = reActAssistant.investigate(userMessage);

            tracer.end(loopTraceStepId,
                    TraceRecorder.fingerprint("conclusion", conclusion), null);
            tracer.end(loopStepId,
                    "maxSteps=" + MAX_STEPS + ",status=completed,rounds=auto", null);

            log.info("ReAct循环完成, runId={}, conclusionLength={}", run.getId(), conclusion.length());
            return conclusion;

        } catch (Exception ex) {
            tracer.end(loopStepId, "failed", ex.getMessage());
            log.error("ReAct循环异常, runId={}, error={}", run.getId(), ex.getMessage());

            // 降级到 Linear 策略——真正调用 LinearInvestigationStrategy.execute()
            // Linear 策略会走完整证据收集+根因分析+建议生成+人工确认流程，
            // 产出有工程价值的排查结果，而不是一个无证据的错误摘要。
            String degradeStepId = tracer.begin("REACT_DEGRADE_TO_LINEAR", parentStepId);
            tracer.recordMeta(degradeStepId, false, "LinearInvestigationStrategy");
            tracer.end(degradeStepId, "reason=" + ex.getMessage(), null);
            log.warn("ReAct降级到Linear策略, runId={}", run.getId());

            // 调用 Linear 策略的完整 execute()——它会处理人工确认和 run 状态更新
            linearStrategy.execute(run, triageResult, extract, draftContent, false, tracer);

            // 返回 null 表示已降级，execute() 检测到 null 后跳过 ReAct 自己的人工确认逻辑
            return null;
        } finally {
            // 清除所有 ThreadLocal（防止内存泄漏）
            ReActContextHolder.clear();
            ReActToolProvider.clearContext();
            ReActTraceHolder.clear();
        }
    }

    /**
     * 构造 ReAct 系统提示词。
     * <p>
     * 包含：
     * - 角色定义（运维排查专家）
     * - 工单上下文（issueType/priority/routedTeam）
     * - 排查指令（逐步推理、调用工具、输出结论）
     * - 输出格式要求（结构化结论，包含根因假设+证据摘要+建议动作）
     */
    private String buildSystemPrompt(TriageResult triageResult, TicketExtractResult extract) {
        return """
                你是一名资深运维排查专家，正在排查一个P0紧急工单。请使用 ReAct（推理+行动）方式逐步排查。

                ## 工单上下文
                - 问题类型：%s
                - 优先级：%s
                - 路由团队：%s
                - 受影响系统：%s
                - 受影响模块：%s

                ## 排查指令
                1. 先推理（Thought）：分析当前已知信息，判断下一步需要什么证据
                2. 再行动（Action）：选择合适的工具调用获取证据
                3. 观察（Observation）：根据工具返回结果更新推理
                4. 重复以上步骤，直到可以得出结论
                5. 最多%d步，超出时基于当前证据给出最佳判断

                ## 可用工具
                - query_logs：查询运维日志，定位错误位置（可传 system/module 参数缩小范围）
                - query_metric：查询系统运行指标（CPU/内存/QPS/延迟），判断资源瓶颈
                - searchSimilarCases：从历史案件库检索相似案例，参考根因与处置经验

                ## 输出格式
                排查完成后，请输出结构化结论：
                - 根因假设：基于证据推断的最可能根因
                - 证据摘要：支持该假设的关键证据
                - 建议动作：具体的处置建议
                """.formatted(
                        triageResult.issueType(),
                        triageResult.priority(),
                        triageResult.routedTeam() != null ? triageResult.routedTeam() : "未分配",
                        extract.affectedSystem() != null ? extract.affectedSystem() : "待确认",
                        extract.affectedModule() != null ? extract.affectedModule() : "待确认",
                        MAX_STEPS
                );
    }

    /**
     * 构造用户消息（工单详情）。
     */
    private String buildUserMessage(String draftContent) {
        return "请排查以下工单：\n\n" + draftContent;
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
