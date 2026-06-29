package com.gcll.ticketagent.agent.workflow;

import com.gcll.ticketagent.agent.AgentAuditSummaryFormatter;
import com.gcll.ticketagent.agent.AgentResponseAssembler;
import com.gcll.ticketagent.agent.AgentRunContextPersister;
import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.agent.planner.AgentAction;
import com.gcll.ticketagent.agent.planner.AgentPlan;
import com.gcll.ticketagent.agent.planner.AgentPlanner;
import com.gcll.ticketagent.agent.planner.ExecutionPlan;
import com.gcll.ticketagent.agent.planner.PlanExecutePlanner;
import com.gcll.ticketagent.analysis.RootCauseAnalysisService;
import com.gcll.ticketagent.analysis.RootCauseResult;
import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.api.dto.TicketAnalysisDto;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.eval.EvalFaultInjection;
import com.gcll.ticketagent.execution.evidence.EvidenceCollectionService;
import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.execution.tool.ToolSelector;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.human.HumanConfirmService;
import com.gcll.ticketagent.governance.human.HumanConfirmTrigger;
import com.gcll.ticketagent.governance.priority.PriorityEvaluationService;
import com.gcll.ticketagent.governance.priority.PriorityResult;
import com.gcll.ticketagent.governance.routing.RoutingPolicyEngine;
import com.gcll.ticketagent.governance.routing.RoutingResult;
import com.gcll.ticketagent.governance.routing.RoutingSuggestion;
import com.gcll.ticketagent.governance.routing.RoutingSuggestionService;
import com.gcll.ticketagent.governance.routing.TeamRoutingService;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.knowledge.KnowledgeSearchService;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import com.gcll.ticketagent.suggestion.SuggestionGenerationService;
import com.gcll.ticketagent.suggestion.TicketSuggestion;
import com.gcll.ticketagent.ticket.TicketDraft;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

@Service
public class AnalysisWorkflowService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisWorkflowService.class);

    private final AgentPlanner agentPlanner;
    private final ToolSelector toolSelector;
    private final KnowledgeSearchService knowledgeSearchService;
    private final EvidenceCollectionService evidenceCollectionService;
    private final RootCauseAnalysisService rootCauseAnalysisService;
    private final PriorityEvaluationService priorityEvaluationService;
    private final TeamRoutingService teamRoutingService;
    private final RoutingSuggestionService routingSuggestionService;
    private final RoutingPolicyEngine routingPolicyEngine;
    private final SuggestionGenerationService suggestionGenerationService;
    private final HumanConfirmTrigger humanConfirmTrigger;
    private final HumanConfirmService humanConfirmService;
    private final AuditLogService auditLogService;
    private final AgentRunRepository agentRunRepository;
    private final AgentMetrics agentMetrics;
    private final ExternalCallGateway externalCallGateway;
    private final TransactionTemplate transactionTemplate;
    private final AgentRunContextPersister contextPersister;
    private final AgentResponseAssembler responseAssembler;
    private final AgentAuditSummaryFormatter summaryFormatter;
    /** 阶段2：可选的根因策略 bean。react 模式开启时存在（ReactRootCauseStrategy），否则 empty 走线性。 */
    private final java.util.Optional<RootCauseStrategy> rootCauseStrategy;
    /** 阶段3：可选的 Plan-Execute 规划器。plan-execute 模式开启时存在，否则 empty 走原 SpringAiAgentPlanner。 */
    private final java.util.Optional<PlanExecutePlanner> planExecutePlanner;

    public AnalysisWorkflowService(
            AgentPlanner agentPlanner,
            ToolSelector toolSelector,
            KnowledgeSearchService knowledgeSearchService,
            EvidenceCollectionService evidenceCollectionService,
            RootCauseAnalysisService rootCauseAnalysisService,
            PriorityEvaluationService priorityEvaluationService,
            TeamRoutingService teamRoutingService,
            RoutingSuggestionService routingSuggestionService,
            RoutingPolicyEngine routingPolicyEngine,
            SuggestionGenerationService suggestionGenerationService,
            HumanConfirmTrigger humanConfirmTrigger,
            HumanConfirmService humanConfirmService,
            AuditLogService auditLogService,
            AgentRunRepository agentRunRepository,
            AgentMetrics agentMetrics,
            ExternalCallGateway externalCallGateway,
            TransactionTemplate transactionTemplate,
            AgentRunContextPersister contextPersister,
            AgentResponseAssembler responseAssembler,
            AgentAuditSummaryFormatter summaryFormatter,
            org.springframework.beans.factory.ObjectProvider<RootCauseStrategy> rootCauseStrategyProvider,
            org.springframework.beans.factory.ObjectProvider<PlanExecutePlanner> planExecutePlannerProvider
    ) {
        this.agentPlanner = agentPlanner;
        this.toolSelector = toolSelector;
        this.knowledgeSearchService = knowledgeSearchService;
        this.evidenceCollectionService = evidenceCollectionService;
        this.rootCauseAnalysisService = rootCauseAnalysisService;
        this.priorityEvaluationService = priorityEvaluationService;
        this.teamRoutingService = teamRoutingService;
        this.routingSuggestionService = routingSuggestionService;
        this.routingPolicyEngine = routingPolicyEngine;
        this.suggestionGenerationService = suggestionGenerationService;
        this.humanConfirmTrigger = humanConfirmTrigger;
        this.humanConfirmService = humanConfirmService;
        this.auditLogService = auditLogService;
        this.agentRunRepository = agentRunRepository;
        this.agentMetrics = agentMetrics;
        this.externalCallGateway = externalCallGateway;
        this.transactionTemplate = transactionTemplate;
        this.contextPersister = contextPersister;
        this.responseAssembler = responseAssembler;
        this.summaryFormatter = summaryFormatter;
        // 阶段2：ObjectProvider 延迟解析，避免无策略 bean（linear 模式）时启动失败
        this.rootCauseStrategy = java.util.Optional.ofNullable(rootCauseStrategyProvider.getIfAvailable());
        // 阶段3：同上，plan-execute 模式未开启时为 empty
        this.planExecutePlanner = java.util.Optional.ofNullable(planExecutePlannerProvider.getIfAvailable());
    }

    public AgentRunResponse execute(
            AgentRun run,
            TicketDraft draft,
            TicketExtractResult extract,
            InfoGapAnalysis gap,
            boolean extractLlmUsed
    ) {
        long start = System.currentTimeMillis();

        // 计划驱动 vs 固定顺序：plan-execute 模式时，证据收集由计划驱动
        AgentPlan plan = resolvePlan(run, draft, extract);

        // 尝试加载持久化的 ExecutionPlan（plan-execute 模式才会生成）
        ExecutionPlan execPlan = contextPersister.loadExecutionPlan(run);

        // 证据收集（两条路径分流）
        StepExecutionContext ctx;
        if (planExecutePlanner.isPresent() && execPlan != null && !execPlan.getSteps().isEmpty()) {
            // ★ 计划驱动：按 ExecutionPlan 的 action 顺序逐个执行，标记状态，断点续跑
            ctx = executeEvidenceByPlan(run, draft, extract, execPlan);
        } else {
            // 固定顺序（原逻辑，或 plan-execute 回退后）
            ctx = executeEvidenceFixedOrder(run, draft, extract, plan);
        }

        // ===== 以下后处理保持原样（rootcause/priority/routing/suggestion）=====
        RootCauseResult rootCause = resolveRootCause(run, draft, extract, ctx.hits(), ctx.evidenceSummary(), ctx.toolResults());

        start = System.currentTimeMillis();
        PriorityResult priority = priorityEvaluationService.evaluate(extract);
        run.setPriority(priority.priority().name());
        auditLogService.recordStep(run, AgentStepName.PRIORITY_EVALUATION, summaryFormatter.summarizeExtract(extract),
                priority.toString(), false, null, System.currentTimeMillis() - start, null);

        start = System.currentTimeMillis();
        RoutingResult ruleRouting = teamRoutingService.route(extract, ctx.hits());
        StepOutcome<RoutingSuggestion> routingSuggestionOutcome = routingSuggestionService.suggest(
                draft.fullContent(), extract, ctx.hits(), ruleRouting);
        RoutingResult routing = routingPolicyEngine.merge(ruleRouting, routingSuggestionOutcome.value());
        long routingCostMs = routingSuggestionOutcome.costMs() > 0
                ? routingSuggestionOutcome.costMs()
                : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.TEAM_ROUTING,
                "rule=" + ruleRouting + ",suggestion=" + routingSuggestionOutcome.value(),
                routing.toString(),
                routingSuggestionOutcome.llmUsed(),
                routingSuggestionOutcome.llmUsed() ? "SpringAI" : null,
                routingCostMs, null);

        start = System.currentTimeMillis();
        StepOutcome<TicketSuggestion> suggestionOutcome = suggestionGenerationService.generate(extract, ctx.hits(), ctx.toolResults(), rootCause);
        TicketSuggestion suggestion = suggestionOutcome.value();
        run.setCurrentSummary(suggestion.summary());
        long suggestionCostMs = suggestionOutcome.costMs() > 0
                ? suggestionOutcome.costMs()
                : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.SUGGESTION_GENERATION, ctx.hits().toString(), suggestion.toString(),
                suggestionOutcome.llmUsed(), suggestionOutcome.llmUsed() ? "SpringAI" : null, suggestionCostMs, null);

        boolean aiGenerated = extractLlmUsed || rootCause.llmUsed() || routingSuggestionOutcome.llmUsed()
                || suggestionOutcome.llmUsed();
        boolean needConfirm = humanConfirmTrigger.needHumanConfirm(priority, routing, extract);
        String confirmReason = humanConfirmTrigger.reason(priority, routing, extract);
        TicketAnalysisDto analysis = responseAssembler.buildAnalysis(
                extract, priority, routing, rootCause, suggestion, needConfirm, confirmReason);

        AgentRunResponse response = responseAssembler.analysisResult(run.getId(), analysis, needConfirm, aiGenerated);
        completeRun(run, gap, plan, new ToolSelection(List.of(), java.util.Map.of(), "plan-driven", false), analysis, needConfirm, confirmReason, routing);
        return response;
    }

    /**
     * 固定顺序执行证据收集（原逻辑，提取出来）：knowledge → toolSelect → evidence。
     */
    private StepExecutionContext executeEvidenceFixedOrder(
            AgentRun run, TicketDraft draft, TicketExtractResult extract, AgentPlan plan) {
        StepExecutionContext ctx = new StepExecutionContext();

        List<KnowledgeHit> hits = searchKnowledgeIfNeeded(run, draft, extract, plan);
        ctx.addHits(hits);

        long start = System.currentTimeMillis();
        StepOutcome<ToolSelection> selectionOutcome = toolSelector.select(draft.fullContent(), extract, plan);
        ToolSelection selection = selectionOutcome.value();
        long selectionCostMs = selectionOutcome.costMs() > 0 ? selectionOutcome.costMs() : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.TOOL_SELECTION, plan.auditSummary(), selection.auditSummary(),
                selectionOutcome.llmUsed(), selectionOutcome.llmUsed() ? "SpringAI" : null, selectionCostMs, null);

        start = System.currentTimeMillis();
        List<ToolResult> toolResults = collectEvidenceSafely(run, extract, draft, selection);
        ctx.addToolResults(toolResults);
        ctx.setEvidenceSummary(summaryFormatter.summarizeToolResults(toolResults));
        auditLogService.recordStep(run, AgentStepName.EVIDENCE_COLLECTION, summaryFormatter.summarizeExtract(extract),
                ctx.evidenceSummary(), false, null, System.currentTimeMillis() - start, null);
        return ctx;
    }

    /**
     * ★ 计划驱动执行证据收集（Plan-Execute 重构核心）。
     *
     * <p>遍历 {@link ExecutionPlan} 的有序步骤，按 {@link AgentAction} 类型分派执行，
     * 每步执行后标记 DONE 并持久化（断点续跑用）。证据写入 {@link StepExecutionContext}。
     *
     * <p>这让计划真正驱动执行——不再是无脑固定顺序，而是按 LLM 规划的顺序逐个执行证据收集，
     * 支持断点续跑（从第一个 PENDING 继续，跳过已 DONE 的）。
     *
     * @param execPlan 持久化的执行计划（可能含已完成的步骤，断点续跑场景）
     */
    private StepExecutionContext executeEvidenceByPlan(
            AgentRun run, TicketDraft draft, TicketExtractResult extract, ExecutionPlan execPlan) {
        StepExecutionContext ctx = new StepExecutionContext();
        if (execPlan == null || execPlan.getSteps() == null || execPlan.getSteps().isEmpty()) {
            log.warn("executeEvidenceByPlan got empty plan, runId={}, fallback to fixed order", run.getId());
            return executeEvidenceFixedOrder(run, draft, extract, execPlan == null ? null : execPlan.toAgentPlan());
        }

        log.info("Plan-driven execution, runId={}, totalSteps={}, resuming={}",
                run.getId(), execPlan.getSteps().size(),
                execPlan.getSteps().stream().anyMatch(s -> s.status() == ExecutionPlan.StepStatus.DONE));

        for (ExecutionPlan.PlanStep step : execPlan.getSteps()) {
            // 断点续跑：跳过已完成的步骤
            if (step.status() == ExecutionPlan.StepStatus.DONE
                    || step.status() == ExecutionPlan.StepStatus.SKIPPED) {
                log.info("Step [{}] already {}, skip (resume), runId={}", step.stepId(), step.status(), run.getId());
                continue;
            }
            long start = System.currentTimeMillis();
            try {
                executePlanStep(run, draft, extract, step, ctx);
                execPlan.markStep(step.stepId(), ExecutionPlan.StepStatus.DONE);
            } catch (Exception ex) {
                log.warn("Plan step [{}] failed, mark FAILED and continue, runId={}: {}",
                        step.stepId(), run.getId(), ex.getMessage());
                execPlan.markStep(step.stepId(), ExecutionPlan.StepStatus.FAILED);
            }
            // 每步执行后持久化计划状态（断点续跑用）
            contextPersister.persistExecutionPlan(run, execPlan);
        }

        ctx.setEvidenceSummary(summaryFormatter.summarizeToolResults(ctx.toolResults()));
        return ctx;
    }

    /**
     * 按 action 类型分派执行单个计划步骤，结果写入 ctx。
     */
    private void executePlanStep(AgentRun run, TicketDraft draft, TicketExtractResult extract,
                                 ExecutionPlan.PlanStep step, StepExecutionContext ctx) {
        long start = System.currentTimeMillis();
        switch (step.action()) {
            case KNOWLEDGE_SEARCH, SIMILAR_CASE_SEARCH -> {
                // 知识检索（KNOWLEDGE_SEARCH 和 SIMILAR_CASE_SEARCH 走同一个知识检索链路）
                KnowledgeSearchOutcome outcome = searchKnowledgeSafely(run, draft, extract);
                ctx.addHits(outcome.hits());
                if (!outcome.hits().isEmpty()) {
                    agentMetrics.recordRagHit();
                }
                auditLogService.recordStep(run, AgentStepName.KNOWLEDGE_SEARCH,
                        "plan-step:" + step.stepId() + " goal=" + step.goal(),
                        outcome.auditOutput(), false, null,
                        System.currentTimeMillis() - start, outcome.errorMessage());
            }
            case QUERY_LOGS -> {
                // 查日志工具
                List<ToolResult> logs = collectSingleTool(run, extract, draft, "query_logs");
                ctx.addToolResults(logs);
                auditLogService.recordStep(run, AgentStepName.EVIDENCE_COLLECTION,
                        "plan-step:" + step.stepId() + " goal=" + step.goal(),
                        summaryFormatter.summarizeToolResults(logs), false, null,
                        System.currentTimeMillis() - start, null);
            }
            case QUERY_METRIC -> {
                // 查指标工具
                List<ToolResult> metrics = collectSingleTool(run, extract, draft, "query_metric");
                ctx.addToolResults(metrics);
                auditLogService.recordStep(run, AgentStepName.EVIDENCE_COLLECTION,
                        "plan-step:" + step.stepId() + " goal=" + step.goal(),
                        summaryFormatter.summarizeToolResults(metrics), false, null,
                        System.currentTimeMillis() - start, null);
            }
        }
    }

    /**
     * 按单个工具名收集证据（计划驱动用：QUERY_LOGS/QUERY_METRIC 各调一次）。
     */
    private List<ToolResult> collectSingleTool(AgentRun run, TicketExtractResult extract,
                                               TicketDraft draft, String toolName) {
        ToolSelection singleSelection = new ToolSelection(List.of(toolName), java.util.Map.of(),
                "plan-driven:" + toolName, false);
        try {
            return evidenceCollectionService.collect(run.getId(), extract, draft.fullContent(), singleSelection);
        } catch (Exception ex) {
            log.warn("Single tool [{}] collection failed, runId={}: {}", toolName, run.getId(), ex.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 阶段2：根因解析，支持 ReAct 策略（LLM 自主调工具）与线性（原单次推理）两种。
     *
     * <p>react 模式（配置开启时 RootCauseStrategy bean 存在）：调策略拿根因，ReAct 循环内
     * LLM 自主调工具迭代推理；失败回退线性根因（兜底）。
     * <p>linear 模式（默认，无策略 bean）：原逻辑 rootCauseAnalysisService.analyze。
     *
     * <p>无论哪条路径，都返回 {@link RootCauseResult}（统一类型），后半段 priority/routing/suggestion
     * 完全无感知根因来源——这是最小侵入式接入。
     */
    /**
     * 阶段3：规划解析，支持 Plan-Execute（有序步骤）与原 planner（无序动作集合）。
     *
     * <p>plan-execute 模式（配置开启时 PlanExecutePlanner bean 存在）：调规划器出有序
     * ExecutionPlan，序列化持久化（断点续跑），再转成 AgentPlan 喂后续（knowledge/tool 仍用
     * AgentPlan.includes 判断，最小侵入）。失败回退原 planner。
     * <p>default 模式：原 SpringAiAgentPlanner，行为不变。
     */
    private AgentPlan resolvePlan(AgentRun run, TicketDraft draft, TicketExtractResult extract) {
        long start = System.currentTimeMillis();
        if (planExecutePlanner.isEmpty()) {
            // 默认：原 planner
            StepOutcome<AgentPlan> outcome = agentPlanner.plan(draft.fullContent(), extract);
            AgentPlan plan = outcome.value();
            long costMs = outcome.costMs() > 0 ? outcome.costMs() : System.currentTimeMillis() - start;
            auditLogService.recordStep(run, AgentStepName.AGENT_PLAN, summaryFormatter.summarizeExtract(extract),
                    plan.auditSummary(), outcome.llmUsed(), outcome.llmUsed() ? "SpringAI" : null, costMs, null);
            return plan;
        }
        // Plan-Execute 路径
        PlanExecutePlanner planner = planExecutePlanner.get();
        try {
            ExecutionPlan execPlan = planner.plan(run.getId(), draft.fullContent(), extract);
            // 持久化有序计划（断点续跑用）
            contextPersister.persistExecutionPlan(run, execPlan);
            AgentPlan plan = execPlan.toAgentPlan();
            String auditOut = "plan-execute steps=" + execPlan.getSteps().size()
                    + ", rationale=" + execPlan.getRationale();
            auditLogService.recordStep(run, AgentStepName.AGENT_PLAN, summaryFormatter.summarizeExtract(extract),
                    auditOut, execPlan.isLlmPlanned(), execPlan.isLlmPlanned() ? "PlanExecute" : null,
                    System.currentTimeMillis() - start, null);
            return plan;
        } catch (Exception ex) {
            // 回退原 planner
            log.warn("Plan-Execute failed, fallback to SpringAiAgentPlanner, runId={}: {}",
                    run.getId(), ex.getMessage());
            StepOutcome<AgentPlan> outcome = agentPlanner.plan(draft.fullContent(), extract);
            AgentPlan plan = outcome.value();
            auditLogService.recordStep(run, AgentStepName.AGENT_PLAN,
                    summaryFormatter.summarizeExtract(extract) + "(fallback-from-plan-execute)",
                    plan.auditSummary(), outcome.llmUsed(), outcome.llmUsed() ? "SpringAI" : null,
                    System.currentTimeMillis() - start, null);
            return plan;
        }
    }

    private RootCauseResult resolveRootCause(
            AgentRun run, TicketDraft draft, TicketExtractResult extract,
            List<KnowledgeHit> hits, String evidenceSummary, List<ToolResult> toolResults) {
        if (rootCauseStrategy.isEmpty()) {
            // 默认线性路径
            long start = System.currentTimeMillis();
            RootCauseResult rc = rootCauseAnalysisService.analyze(extract, hits, toolResults);
            auditLogService.recordStep(run, AgentStepName.ROOT_CAUSE_ANALYSIS, evidenceSummary,
                    rc.hypothesis(), rc.llmUsed(), rc.llmUsed() ? "SpringAI" : null,
                    System.currentTimeMillis() - start, null);
            return rc;
        }
        // ReAct 路径：调策略，失败回退线性
        RootCauseStrategy strategy = rootCauseStrategy.get();
        try {
            long start = System.currentTimeMillis();
            RootCauseStrategy.RootCauseOutcome outcome = strategy.analyze(
                    run.getId(), extract, draft.fullContent());
            auditLogService.recordStep(run, AgentStepName.ROOT_CAUSE_ANALYSIS,
                    strategy.strategyName() + "-strategy", outcome.hypothesis(),
                    outcome.llmUsed(), outcome.llmUsed() ? strategy.strategyName() : null,
                    outcome.durationMs(), null);
            return new RootCauseResult(outcome.hypothesis(), outcome.evidence(),
                    outcome.unknowns(), outcome.confidence(), outcome.llmUsed());
        } catch (Exception ex) {
            log.warn("Root cause strategy [{}] failed, fallback to linear, runId={}: {}",
                    strategy.strategyName(), run.getId(), ex.getMessage());
            long start = System.currentTimeMillis();
            RootCauseResult rc = rootCauseAnalysisService.analyze(extract, hits, toolResults);
            auditLogService.recordStep(run, AgentStepName.ROOT_CAUSE_ANALYSIS,
                    evidenceSummary + "(fallback-from-" + strategy.strategyName() + ")",
                    rc.hypothesis(), rc.llmUsed(), rc.llmUsed() ? "SpringAI" : null,
                    System.currentTimeMillis() - start, null);
            return rc;
        }
    }

    private List<KnowledgeHit> searchKnowledgeIfNeeded(
            AgentRun run,
            TicketDraft draft,
            TicketExtractResult extract,
            AgentPlan plan
    ) {
        if (!plan.includes(AgentAction.KNOWLEDGE_SEARCH)) {
            auditLogService.recordStep(run, AgentStepName.KNOWLEDGE_SEARCH, draft.fullContent(),
                    "skipped_by_plan:" + plan.skipped(), false, null, 0, null);
            return List.of();
        }

        long start = System.currentTimeMillis();
        KnowledgeSearchOutcome searchOutcome = searchKnowledgeSafely(run, draft, extract);
        List<KnowledgeHit> hits = searchOutcome.hits();
        if (!hits.isEmpty()) {
            agentMetrics.recordRagHit();
        }
        auditLogService.recordStep(run, AgentStepName.KNOWLEDGE_SEARCH, draft.fullContent(), searchOutcome.auditOutput(),
                false, null, System.currentTimeMillis() - start, searchOutcome.errorMessage());
        return hits;
    }

    /**
     * Knowledge search is governed by {@link ExternalCallGateway}; failures degrade to an empty hit list.
     */
    private KnowledgeSearchOutcome searchKnowledgeSafely(AgentRun run, TicketDraft draft, TicketExtractResult extract) {
        if (EvalFaultInjection.shouldFailKnowledgeSearch(draft.fullContent())) {
            return new KnowledgeSearchOutcome(
                    Collections.emptyList(),
                    "degraded: injected knowledge search failure",
                    "Knowledge search failed: injected failure for eval"
            );
        }
        CallResult<List<KnowledgeHit>> result = externalCallGateway.execute(
                "vector.knowledge-search",
                () -> knowledgeSearchService.search(
                        EvalFaultInjection.sanitize(draft.fullContent()),
                        extract.affectedSystem(),
                        extract.affectedModule(),
                        extract.issueType().name()
                )
        );
        if (!result.success()) {
            String reason = result.circuitOpen() ? "circuit open"
                    : (result.error() != null ? result.error().getMessage() : "unknown");
            log.warn("Knowledge search degraded, runId={}, circuitOpen={}, reason={}",
                    run.getId(), result.circuitOpen(), reason);
            return new KnowledgeSearchOutcome(
                    Collections.emptyList(),
                    "degraded: " + reason,
                    "Knowledge search failed: " + reason
            );
        }
        List<KnowledgeHit> hits = result.value();
        return new KnowledgeSearchOutcome(hits, hits.toString(), null);
    }

    private List<ToolResult> collectEvidenceSafely(
            AgentRun run,
            TicketExtractResult extract,
            TicketDraft draft,
            ToolSelection selection
    ) {
        try {
            return evidenceCollectionService.collect(run.getId(), extract, draft.fullContent(), selection);
        } catch (Exception ex) {
            log.warn("Evidence collection failed, runId={}, error={}", run.getId(), ex.getMessage());
            return Collections.emptyList();
        }
    }

    private void completeRun(
            AgentRun run,
            InfoGapAnalysis gap,
            AgentPlan plan,
            ToolSelection selection,
            TicketAnalysisDto analysis,
            boolean needConfirm,
            String confirmReason,
            RoutingResult routing
    ) {
        if (needConfirm) {
            transactionTemplate.executeWithoutResult(status -> {
                auditLogService.recordStep(run, AgentStepName.WAIT_HUMAN_CONFIRM, analysis.toString(), confirmReason,
                        false, null, 0, null);
                humanConfirmService.createDispatchAction(run, analysis.toString(), confirmReason, routing.primaryTeam());
                contextPersister.persist(run, gap, plan, selection);
                run.setStatus(AgentRunStatus.WAIT_HUMAN_CONFIRM);
                agentRunRepository.save(run);
            });
            return;
        }

        transactionTemplate.executeWithoutResult(status -> {
            auditLogService.recordStep(run, AgentStepName.FINAL, analysis.toString(), "completed", false, null, 0, null);
            contextPersister.persist(run, gap, plan, selection);
            run.setStatus(AgentRunStatus.FINAL);
            agentRunRepository.save(run);
        });
    }

    private record KnowledgeSearchOutcome(List<KnowledgeHit> hits, String auditOutput, String errorMessage) {
    }
}
