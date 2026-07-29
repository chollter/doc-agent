package com.gcll.ticketagent.investigation;

import com.gcll.ticketagent.agent.AgentAuditSummaryFormatter;
import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.agent.planner.AgentAction;
import com.gcll.ticketagent.agent.planner.AgentPlan;
import com.gcll.ticketagent.agent.planner.AgentPlanner;
import com.gcll.ticketagent.analysis.RootCauseAnalysisService;
import com.gcll.ticketagent.analysis.RootCauseResult;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.execution.evidence.EvidenceCollectionService;
import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.execution.tool.ToolSelector;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.human.HumanConfirmService;
import com.gcll.ticketagent.governance.human.HumanConfirmTrigger;
import com.gcll.ticketagent.governance.priority.PriorityResult;
import com.gcll.ticketagent.governance.routing.RoutingResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.knowledge.KnowledgeSearchService;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.observability.trace.TraceRecorderFactory;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import com.gcll.ticketagent.suggestion.SuggestionGenerationService;
import com.gcll.ticketagent.suggestion.TicketSuggestion;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.triage.TriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

/**
 * 排查阶段服务——v2 异步排查的核心。
 * <p>
 * 定位：Agent 做辅助不做决策。
 * 产出：证据包 + 参考诊断，人是最终判断者。
 * <p>
 * 与分诊阶段解耦：通过 Kafka TriageCompletedEvent 触发。
 * 分诊已完成优先级评估和路由，排查不再重复。
 */
@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

    private final AgentPlanner agentPlanner;
    private final ToolSelector toolSelector;
    private final KnowledgeSearchService knowledgeSearchService;
    private final EvidenceCollectionService evidenceCollectionService;
    private final RootCauseAnalysisService rootCauseAnalysisService;
    private final SuggestionGenerationService suggestionGenerationService;
    private final HumanConfirmTrigger humanConfirmTrigger;
    private final HumanConfirmService humanConfirmService;
    private final AgentRunRepository agentRunRepository;
    private final AgentMetrics agentMetrics;
    private final ExternalCallGateway externalCallGateway;
    private final TransactionTemplate transactionTemplate;
    private final AgentAuditSummaryFormatter summaryFormatter;
    private final TraceRecorderFactory traceRecorderFactory;

    public InvestigationService(
            AgentPlanner agentPlanner,
            ToolSelector toolSelector,
            KnowledgeSearchService knowledgeSearchService,
            EvidenceCollectionService evidenceCollectionService,
            RootCauseAnalysisService rootCauseAnalysisService,
            SuggestionGenerationService suggestionGenerationService,
            HumanConfirmTrigger humanConfirmTrigger,
            HumanConfirmService humanConfirmService,
            AgentRunRepository agentRunRepository,
            AgentMetrics agentMetrics,
            ExternalCallGateway externalCallGateway,
            TransactionTemplate transactionTemplate,
            AgentAuditSummaryFormatter summaryFormatter,
            TraceRecorderFactory traceRecorderFactory
    ) {
        this.agentPlanner = agentPlanner;
        this.toolSelector = toolSelector;
        this.knowledgeSearchService = knowledgeSearchService;
        this.evidenceCollectionService = evidenceCollectionService;
        this.rootCauseAnalysisService = rootCauseAnalysisService;
        this.suggestionGenerationService = suggestionGenerationService;
        this.humanConfirmTrigger = humanConfirmTrigger;
        this.humanConfirmService = humanConfirmService;
        this.agentRunRepository = agentRunRepository;
        this.agentMetrics = agentMetrics;
        this.externalCallGateway = externalCallGateway;
        this.transactionTemplate = transactionTemplate;
        this.summaryFormatter = summaryFormatter;
        this.traceRecorderFactory = traceRecorderFactory;
    }

    /**
     * 执行排查——同步执行（由 InvestigationConsumer 调用）。
     * <p>
     * 流程：
     * 1. 计划生成（基于分诊结果）
     * 2. 证据收集（RAG + 工具）
     * 3. 根因分析（低置信度参考）
     * 4. 建议生成
     * 5. 人工确认决策
     * 6. 完成
     * <p>
     * 注意：优先级评估和路由已在分诊阶段完成，排查不重复。
     */
    public InvestigationResult investigate(
            AgentRun run, TriageResult triageResult,
            TicketExtractResult extract, String draftContent,
            boolean extractLlmUsed
    ) {
        TraceRecorder tracer = traceRecorderFactory.create(run);
        String parentStepId = tracer.begin("INVESTIGATION");

        try {
            // 1. 计划生成
            String planStepId = tracer.begin(AgentStepName.AGENT_PLAN.name(), parentStepId);
            StepOutcome<AgentPlan> planOutcome = agentPlanner.plan(draftContent, extract);
            AgentPlan plan = planOutcome.value();
            tracer.recordMeta(planStepId, planOutcome.llmUsed(), planOutcome.llmUsed() ? "SpringAI" : null);
            tracer.end(planStepId, plan.auditSummary(), null);

            // 2. 证据收集（RAG + 工具）
            List<KnowledgeHit> hits = searchKnowledgeIfNeeded(run, draftContent, extract, plan, tracer, parentStepId);

            String selectionStepId = tracer.begin(AgentStepName.TOOL_SELECTION.name(), parentStepId);
            StepOutcome<ToolSelection> selectionOutcome = toolSelector.select(draftContent, extract, plan);
            ToolSelection selection = selectionOutcome.value();
            tracer.recordMeta(selectionStepId, selectionOutcome.llmUsed(), selectionOutcome.llmUsed() ? "SpringAI" : null);
            tracer.end(selectionStepId, selection.auditSummary(), null);

            String evidenceStepId = tracer.begin(AgentStepName.EVIDENCE_COLLECTION.name(), parentStepId);
            List<ToolResult> toolResults = collectEvidenceSafely(run, extract, draftContent, selection);
            String evidenceSummary = summaryFormatter.summarizeToolResults(toolResults);
            tracer.end(evidenceStepId, TraceRecorder.fingerprint("evidence", evidenceSummary), null);

            // 3. 根因分析（低置信度参考）
            String rootCauseStepId = tracer.begin(AgentStepName.ROOT_CAUSE_ANALYSIS.name(), parentStepId);
            RootCauseResult rootCause = rootCauseAnalysisService.analyze(extract, hits, toolResults);
            tracer.end(rootCauseStepId, TraceRecorder.fingerprint("hypothesis", rootCause.hypothesis()), null);

            // 4. 建议生成
            String suggestionStepId = tracer.begin(AgentStepName.SUGGESTION_GENERATION.name(), parentStepId);
            StepOutcome<TicketSuggestion> suggestionOutcome = suggestionGenerationService.generate(extract, hits, toolResults, rootCause);
            TicketSuggestion suggestion = suggestionOutcome.value();
            run.setCurrentSummary(suggestion.summary());
            tracer.recordMeta(suggestionStepId, suggestionOutcome.llmUsed(), suggestionOutcome.llmUsed() ? "SpringAI" : null);
            tracer.end(suggestionStepId, suggestion.toString(), null);

            // 5. 人工确认决策（使用分诊结果的优先级和路由）
            boolean aiGenerated = extractLlmUsed || rootCause.llmUsed() || suggestionOutcome.llmUsed();
            PriorityResult priorityResult = toPriorityResult(triageResult);
            RoutingResult routingResult = toRoutingResult(triageResult);
            boolean needConfirm = humanConfirmTrigger.needHumanConfirm(
                    priorityResult, routingResult, extract, draftContent);
            String confirmReason = humanConfirmTrigger.reason(
                    priorityResult, routingResult, extract, draftContent);

            String confirmStepId = tracer.begin(AgentStepName.HUMAN_CONFIRM_DECISION.name(), parentStepId);
            tracer.end(confirmStepId,
                    "needConfirm=" + needConfirm + ",reason=" + (needConfirm ? confirmReason : "not_required"),
                    null);

            // 6. 完成
            completeRun(run, needConfirm, confirmReason, triageResult, tracer, parentStepId);

            tracer.end(parentStepId,
                    "issueType=" + triageResult.issueType()
                            + ",priority=" + triageResult.priority()
                            + ",needConfirm=" + needConfirm,
                    null);

            return InvestigationResult.success(
                    run.getId(),
                    suggestion.summary(),
                    evidenceSummary,
                    rootCause.hypothesis(),
                    String.join("; ", suggestion.actions()),
                    needConfirm,
                    needConfirm ? confirmReason : null
            );

        } catch (Exception ex) {
            log.error("排查执行失败, runId={}", run.getId(), ex);
            tracer.end(parentStepId, "failed", ex.getMessage());
            transactionTemplate.executeWithoutResult(status -> {
                run.setStatus(AgentRunStatus.FAILED);
                agentRunRepository.save(run);
            });
            return InvestigationResult.failed(run.getId(), ex.getMessage());
        }
    }

    // --- 内部方法 ---

    private List<KnowledgeHit> searchKnowledgeIfNeeded(
            AgentRun run, String content, TicketExtractResult extract,
            AgentPlan plan, TraceRecorder tracer, String parentStepId) {
        if (!plan.includes(AgentAction.KNOWLEDGE_SEARCH)) {
            return List.of();
        }
        String stepId = tracer.begin(AgentStepName.KNOWLEDGE_SEARCH.name(), parentStepId);
        CallResult<List<KnowledgeHit>> result = externalCallGateway.execute(
                "vector.knowledge-search",
                () -> knowledgeSearchService.search(content, extract.affectedSystem(), extract.affectedModule(), extract.issueType().name())
        );
        if (!result.success()) {
            log.warn("Knowledge search degraded, runId={}, reason={}", run.getId(),
                    result.circuitOpen() ? "circuit open" : "unknown");
            agentMetrics.recordFallback("vector.knowledge-search");
            tracer.end(stepId, "degraded", null);
            return Collections.emptyList();
        }
        List<KnowledgeHit> hits = result.value();
        if (!hits.isEmpty()) {
            agentMetrics.recordRagHit();
        }
        tracer.end(stepId, "hits=" + hits.size(), null);
        return hits;
    }

    private List<ToolResult> collectEvidenceSafely(
            AgentRun run, TicketExtractResult extract, String content, ToolSelection selection) {
        try {
            return evidenceCollectionService.collect(run.getId(), extract, content, selection);
        } catch (Exception ex) {
            log.warn("Evidence collection failed, runId={}, error={}", run.getId(), ex.getMessage());
            return Collections.emptyList();
        }
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

    /**
     * 将分诊结果的优先级转为 PriorityResult（排查阶段复用分诊评估，不再重复）
     */
    private PriorityResult toPriorityResult(TriageResult triageResult) {
        return new PriorityResult(
                triageResult.priority(),
                List.of(), "",
                false,
                triageResult.needHumanConfirm()
        );
    }

    /**
     * 将分诊结果的路由信息转为 RoutingResult（排查阶段复用分诊路由，不再重复评估）
     */
    private RoutingResult toRoutingResult(TriageResult triageResult) {
        String team = triageResult.routedTeam() != null ? triageResult.routedTeam() : "unassigned";
        return new RoutingResult(team, List.of(), triageResult.issueType().name(), triageResult.confidence());
    }
}
