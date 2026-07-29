package com.gcll.ticketagent.investigation.strategy;

import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.extract.TicketExtractResult;
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
import com.gcll.ticketagent.triage.TriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;

/**
 * Consult 排查策略——P2/P3 低优先级工单。
 * <p>
 * 仅 RAG 检索 + 建议生成，不调工具，省成本、省时间。
 * 适用于：非紧急、影响范围小、无需深度排查的工单。
 * <p>
 * 与 Linear 的核心区别：跳过工具选择/执行和根因分析，
 * 直接基于 RAG 结果生成建议。人工确认概率低（P2/P3 通常直接自动流转）。
 */
@Component
public class ConsultInvestigationStrategy implements InvestigationStrategy {

    private static final Logger log = LoggerFactory.getLogger(ConsultInvestigationStrategy.class);

    private final KnowledgeSearchService knowledgeSearchService;
    private final SuggestionGenerationService suggestionGenerationService;
    private final HumanConfirmTrigger humanConfirmTrigger;
    private final AgentRunRepository agentRunRepository;
    private final AgentMetrics agentMetrics;
    private final ExternalCallGateway externalCallGateway;
    private final TransactionTemplate transactionTemplate;

    public ConsultInvestigationStrategy(
            KnowledgeSearchService knowledgeSearchService,
            SuggestionGenerationService suggestionGenerationService,
            HumanConfirmTrigger humanConfirmTrigger,
            AgentRunRepository agentRunRepository,
            AgentMetrics agentMetrics,
            ExternalCallGateway externalCallGateway,
            TransactionTemplate transactionTemplate
    ) {
        this.knowledgeSearchService = knowledgeSearchService;
        this.suggestionGenerationService = suggestionGenerationService;
        this.humanConfirmTrigger = humanConfirmTrigger;
        this.agentRunRepository = agentRunRepository;
        this.agentMetrics = agentMetrics;
        this.externalCallGateway = externalCallGateway;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public InvestigationResult execute(
            AgentRun run, TriageResult triageResult,
            TicketExtractResult extract, String draftContent,
            boolean extractLlmUsed, TraceRecorder tracer
    ) {
        String parentStepId = tracer.begin("CONSULT_INVESTIGATION");

        try {
            // 1. RAG 检索（唯一的信息源）
            String searchStepId = tracer.begin(AgentStepName.KNOWLEDGE_SEARCH.name(), parentStepId);
            List<KnowledgeHit> hits = searchKnowledge(run, draftContent, extract);
            tracer.end(searchStepId, "hits=" + hits.size(), null);

            // 2. 建议生成（基于 RAG 结果，无工具结果，无根因分析）
            String suggestionStepId = tracer.begin(AgentStepName.SUGGESTION_GENERATION.name(), parentStepId);
            StepOutcome<TicketSuggestion> suggestionOutcome = suggestionGenerationService.generate(
                    extract, hits, List.of(), null);
            TicketSuggestion suggestion = suggestionOutcome.value();
            run.setCurrentSummary(suggestion.summary());
            tracer.recordMeta(suggestionStepId, suggestionOutcome.llmUsed(), suggestionOutcome.llmUsed() ? "SpringAI" : null);
            tracer.end(suggestionStepId, suggestion.toString(), null);

            // 3. 人工确认决策（P2/P3 通常不需要）
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

            // 4. 完成
            if (needConfirm) {
                transactionTemplate.executeWithoutResult(status -> {
                    run.setStatus(AgentRunStatus.WAIT_HUMAN_CONFIRM);
                    agentRunRepository.save(run);
                });
            } else {
                transactionTemplate.executeWithoutResult(status -> {
                    String stepId = tracer.begin(AgentStepName.FINAL.name(), parentStepId);
                    tracer.end(stepId, "completed", null);
                    run.setStatus(AgentRunStatus.FINAL);
                    agentRunRepository.save(run);
                });
            }

            tracer.end(parentStepId,
                    "strategy=consult,priority=" + triageResult.priority() + ",needConfirm=" + needConfirm,
                    null);

            String evidenceSummary = hits.isEmpty() ? "no_rag_hits" : hits.size() + " RAG hits";
            return InvestigationResult.success(
                    run.getId(), suggestion.summary(), evidenceSummary,
                    "低优先级工单，仅基于知识库参考（无深度排查）",
                    String.join("; ", suggestion.actions()),
                    needConfirm, needConfirm ? confirmReason : null
            );

        } catch (Exception ex) {
            log.error("Consult排查失败, runId={}", run.getId(), ex);
            tracer.end(parentStepId, "failed", ex.getMessage());
            transactionTemplate.executeWithoutResult(status -> {
                run.setStatus(AgentRunStatus.FAILED);
                agentRunRepository.save(run);
            });
            return InvestigationResult.failed(run.getId(), ex.getMessage());
        }
    }

    @Override
    public String name() {
        return "CONSULT";
    }

    private List<KnowledgeHit> searchKnowledge(AgentRun run, String content, TicketExtractResult extract) {
        CallResult<List<KnowledgeHit>> result = externalCallGateway.execute(
                "vector.knowledge-search",
                () -> knowledgeSearchService.search(content, extract.affectedSystem(), extract.affectedModule(), extract.issueType().name())
        );
        if (!result.success()) {
            log.warn("Knowledge search degraded (consult), runId={}", run.getId());
            agentMetrics.recordFallback("vector.knowledge-search");
            return Collections.emptyList();
        }
        List<KnowledgeHit> hits = result.value();
        if (!hits.isEmpty()) {
            agentMetrics.recordRagHit();
        }
        return hits;
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
