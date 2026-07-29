package com.gcll.ticketagent.investigation.strategy;

import com.gcll.ticketagent.agent.AgentAuditSummaryFormatter;
import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.agent.planner.AgentAction;
import com.gcll.ticketagent.agent.planner.AgentPlan;
import com.gcll.ticketagent.agent.planner.AgentPlanner;
import com.gcll.ticketagent.a2a.A2ACoordinator;
import com.gcll.ticketagent.a2a.A2ACoordinator.A2aCoordinationResult;
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
import com.gcll.ticketagent.investigation.InvestigationResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.knowledge.KnowledgeSearchService;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import com.gcll.ticketagent.suggestion.SuggestionGenerationService;
import com.gcll.ticketagent.suggestion.TicketSuggestion;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.triage.TriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

/**
 * Linear 排查策略——P1 优先级工单。
 * <p>
 * 固定顺序：计划生成 → RAG检索 → 工具选择/执行 → 根因分析 → 建议生成 → 人工确认
 * 全流程执行，不跳步，确保排查深度。
 */
@Component
public class LinearInvestigationStrategy implements InvestigationStrategy {

    private static final Logger log = LoggerFactory.getLogger(LinearInvestigationStrategy.class);

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
    private final A2ACoordinator a2aCoordinator;

    public LinearInvestigationStrategy(
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
            A2ACoordinator a2aCoordinator
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
        this.a2aCoordinator = a2aCoordinator;
    }

    @Override
    public InvestigationResult execute(
            AgentRun run, TriageResult triageResult,
            TicketExtractResult extract, String draftContent,
            boolean extractLlmUsed, TraceRecorder tracer
    ) {
        String parentStepId = tracer.begin("LINEAR_INVESTIGATION");

        try {
            // 1. 计划生成
            String planStepId = tracer.begin(AgentStepName.AGENT_PLAN.name(), parentStepId);
            StepOutcome<AgentPlan> planOutcome = agentPlanner.plan(draftContent, extract);
            AgentPlan plan = planOutcome.value();
            tracer.recordMeta(planStepId, planOutcome.llmUsed(), planOutcome.llmUsed() ? "SpringAI" : null);
            tracer.end(planStepId, plan.auditSummary(), null);

            // 2. RAG 检索
            List<KnowledgeHit> hits = searchKnowledge(run, draftContent, extract, plan, tracer, parentStepId);

            // 3. 工具选择 + 执行
            String selectionStepId = tracer.begin(AgentStepName.TOOL_SELECTION.name(), parentStepId);
            StepOutcome<ToolSelection> selectionOutcome = toolSelector.select(draftContent, extract, plan);
            ToolSelection selection = selectionOutcome.value();
            tracer.recordMeta(selectionStepId, selectionOutcome.llmUsed(), selectionOutcome.llmUsed() ? "SpringAI" : null);
            tracer.end(selectionStepId, selection.auditSummary(), null);

            String evidenceStepId = tracer.begin(AgentStepName.EVIDENCE_COLLECTION.name(), parentStepId);
            List<ToolResult> toolResults = collectEvidence(run, extract, draftContent, selection);
            String evidenceSummary = summaryFormatter.summarizeToolResults(toolResults);
            tracer.end(evidenceStepId, TraceRecorder.fingerprint("evidence", evidenceSummary), null);

            // 3.5 A2A 团队 Agent 协助排查（路由到特定团队时请求额外证据）
            String a2aEvidence = requestTeamAgentEvidence(run, triageResult, extract, tracer, parentStepId);
            if (a2aEvidence != null && !a2aEvidence.isBlank()) {
                evidenceSummary = evidenceSummary + "\n[团队Agent补充证据]\n" + a2aEvidence;
            }

            // 4. 根因分析
            String rootCauseStepId = tracer.begin(AgentStepName.ROOT_CAUSE_ANALYSIS.name(), parentStepId);
            RootCauseResult rootCause = rootCauseAnalysisService.analyze(extract, hits, toolResults);
            tracer.end(rootCauseStepId, TraceRecorder.fingerprint("hypothesis", rootCause.hypothesis()), null);

            // 5. 建议生成
            String suggestionStepId = tracer.begin(AgentStepName.SUGGESTION_GENERATION.name(), parentStepId);
            StepOutcome<TicketSuggestion> suggestionOutcome = suggestionGenerationService.generate(extract, hits, toolResults, rootCause);
            TicketSuggestion suggestion = suggestionOutcome.value();
            run.setCurrentSummary(suggestion.summary());
            tracer.recordMeta(suggestionStepId, suggestionOutcome.llmUsed(), suggestionOutcome.llmUsed() ? "SpringAI" : null);
            tracer.end(suggestionStepId, suggestion.toString(), null);

            // 6. 人工确认决策
            boolean aiGenerated = extractLlmUsed || rootCause.llmUsed() || suggestionOutcome.llmUsed();
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

            // 7. 完成
            completeRun(run, needConfirm, confirmReason, triageResult, tracer, parentStepId);

            tracer.end(parentStepId,
                    "strategy=linear,priority=" + triageResult.priority() + ",needConfirm=" + needConfirm,
                    null);

            return InvestigationResult.success(
                    run.getId(), suggestion.summary(), evidenceSummary,
                    rootCause.hypothesis(),
                    String.join("; ", suggestion.actions()),
                    needConfirm, needConfirm ? confirmReason : null
            );

        } catch (Exception ex) {
            log.error("Linear排查失败, runId={}", run.getId(), ex);
            tracer.end(parentStepId, "failed", ex.getMessage());
            failRun(run);
            return InvestigationResult.failed(run.getId(), ex.getMessage());
        }
    }

    @Override
    public String name() {
        return "LINEAR";
    }

    // --- 内部方法 ---

    private List<KnowledgeHit> searchKnowledge(
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
            log.warn("Knowledge search degraded, runId={}", run.getId());
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

    private List<ToolResult> collectEvidence(
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

    /**
     * A2A 团队 Agent 协助排查——请求路由目标团队提供额外证据。
     *
     * <p>当分诊结果中有明确的路由团队时，通过 A2A 请求团队 Agent 协助排查，
     * 获取该团队特有的排查证据（如数据库团队的慢查询、连接池信息）。
     *
     * <p>A2A 调用不阻塞主流程——团队 Agent 不可用时静默跳过，
     * 不影响主排查流程的正常执行。
     *
     * @return 团队 Agent 返回的证据摘要；null 表示未调用
     */
    private String requestTeamAgentEvidence(
            AgentRun run, TriageResult triageResult, TicketExtractResult extract,
            TraceRecorder tracer, String parentStepId) {

        String routedTeam = triageResult.routedTeam();
        if (routedTeam == null || routedTeam.isBlank() || "unassigned".equals(routedTeam)) {
            return null;
        }

        // 将路由团队名映射为 Agent ID（约定：团队名 → {team}-agent）
        String teamAgentId = mapTeamToAgentId(routedTeam);
        if (!a2aCoordinator.isAgentAvailable(teamAgentId)) {
            log.debug("A2A团队Agent不可用, teamAgentId={}, runId={}, 跳过A2A调用", teamAgentId, run.getId());
            return null;
        }

        String a2aStepId = tracer.begin("A2A_TEAM_AGENT", parentStepId);
        try {
            A2aCoordinationResult result = a2aCoordinator.requestInvestigation(
                    teamAgentId, run.getId(), triageResult, extract);

            if (result.success()) {
                tracer.end(a2aStepId,
                        "agent=" + result.agentId() + ",status=success", null);
                log.info("A2A团队Agent返回证据, agent={}, runId={}", result.agentId(), run.getId());
                return result.evidenceSummary();
            } else {
                tracer.end(a2aStepId, "status=failed,error=" + result.error(), null);
                log.warn("A2A团队Agent排查失败, agent={}, runId={}, error={}",
                        teamAgentId, run.getId(), result.error());
                return null;
            }
        } catch (Exception ex) {
            tracer.end(a2aStepId, "status=error", ex.getMessage());
            log.warn("A2A团队Agent调用异常, agent={}, runId={}", teamAgentId, run.getId(), ex);
            return null;
        }
    }

    /**
     * 将路由团队名映射为 A2A Agent ID。
     * 约定：团队名中含 "db"/"database" → db-team-agent，其余暂不支持。
     */
    private String mapTeamToAgentId(String routedTeam) {
        String lower = routedTeam.toLowerCase();
        if (lower.contains("db") || lower.contains("database") || lower.contains("数据")) {
            return "db-team-agent";
        }
        // 未来可扩展：infra-team-agent, middleware-team-agent 等
        return routedTeam + "-agent";
    }
}
