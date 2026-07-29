package com.gcll.ticketagent.agent;

import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.extract.TicketExtractService;
import com.gcll.ticketagent.investigation.TriageCompletedEvent;
import com.gcll.ticketagent.investigation.TriageCompletedEventPublisher;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.resilience.LlmRunContext;
import com.gcll.ticketagent.ticket.TicketDraft;
import com.gcll.ticketagent.triage.TriagePipeline;
import com.gcll.ticketagent.triage.TriageResult;
import com.gcll.ticketagent.understanding.completeness.CompletenessDecision;
import com.gcll.ticketagent.understanding.completeness.CompletenessDecisionService;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final TicketExtractService ticketExtractService;
    private final TriagePipeline triagePipeline;
    private final InfoGapAnalysisService infoGapAnalysisService;
    private final CompletenessDecisionService completenessDecisionService;
    private final TriageCompletedEventPublisher triageEventPublisher;
    private final AuditLogService auditLogService;
    private final AgentRunRepository agentRunRepository;
    private final TransactionTemplate transactionTemplate;
    private final AgentRunContextPersister contextPersister;
    private final AgentResponseAssembler responseAssembler;
    private final String investigationTopic;

    public AgentRuntime(
            TicketExtractService ticketExtractService,
            TriagePipeline triagePipeline,
            InfoGapAnalysisService infoGapAnalysisService,
            CompletenessDecisionService completenessDecisionService,
            TriageCompletedEventPublisher triageEventPublisher,
            AuditLogService auditLogService,
            AgentRunRepository agentRunRepository,
            TransactionTemplate transactionTemplate,
            AgentRunContextPersister contextPersister,
            AgentResponseAssembler responseAssembler,
            @Value("${opsmind.investigation.topic:opsmind.investigation.execute}") String investigationTopic
    ) {
        this.ticketExtractService = ticketExtractService;
        this.triagePipeline = triagePipeline;
        this.infoGapAnalysisService = infoGapAnalysisService;
        this.completenessDecisionService = completenessDecisionService;
        this.triageEventPublisher = triageEventPublisher;
        this.auditLogService = auditLogService;
        this.agentRunRepository = agentRunRepository;
        this.transactionTemplate = transactionTemplate;
        this.contextPersister = contextPersister;
        this.responseAssembler = responseAssembler;
        this.investigationTopic = investigationTopic;
    }

    public AgentRunResponse execute(AgentRun run, TicketDraft draft) {
        LlmRunContext.bind(run.getId());
        TraceRecorder tracer = auditLogService.createTracer(run);
        try {
            return doExecute(run, draft, tracer);
        } catch (Exception ex) {
            log.warn("Agent run failed, runId={}, issueType={}, step={}, exception={}, message={}",
                    run.getId(), run.getIssueType(), lastStepHint(run),
                    ex.getClass().getName(),
                    ex.getMessage() == null ? "(no message)" : ex.getMessage(),
                    ex);
            return failRun(run, draft, ex, tracer);
        } finally {
            LlmRunContext.clear();
        }
    }

    private String lastStepHint(AgentRun run) {
        if (run.getSteps() == null || run.getSteps().isEmpty()) {
            return "unknown";
        }
        return run.getSteps().getLast().getStepName();
    }

    private AgentRunResponse doExecute(AgentRun run, TicketDraft draft, TraceRecorder tracer) {
        // === 阶段1：分诊（同步，秒级出结果） ===

        // Step 1: 结构化抽取
        String extractStepId = tracer.begin(AgentStepName.TICKET_EXTRACT.name());
        StepOutcome<TicketExtractResult> extractOutcome = ticketExtractService.extract(draft.fullContent());
        TicketExtractResult extract = extractOutcome.value();
        run.setIssueType(extract.issueType().name());
        tracer.recordInput(extractStepId, TraceRecorder.fingerprint("content", draft.fullContent()));
        tracer.recordMeta(extractStepId, extractOutcome.llmUsed(), extractOutcome.llmUsed() ? "SpringAI" : null);
        tracer.end(extractStepId,
                TraceRecorder.fingerprint("issueType", extract.issueType().name())
                        + ", " + TraceRecorder.fingerprint("system", extract.affectedSystem()),
                null);

        // Step 2: 分诊 Pipeline
        int followUpRound = getFollowUpRound(run);
        String triageStepId = tracer.begin(AgentStepName.TRIAGE_PIPELINE.name(), extractStepId);
        TriageResult triageResult = triagePipeline.execute(draft.fullContent(), extract, run.getId(), followUpRound);
        tracer.end(triageStepId,
                "issueType=" + triageResult.issueType()
                        + ",priority=" + triageResult.priority()
                        + ",confidence=" + triageResult.confidence()
                        + ",source=" + triageResult.source()
                        + ",routedTeam=" + triageResult.routedTeam(),
                null);

        run.setIssueType(triageResult.issueType().name());

        // Step 3: 追问决策（四层兜底）
        TriagePipeline.FollowUpDecision followUpDecision = triagePipeline.decideFollowUp(triageResult, extract);
        if (followUpDecision.needFollowUp()) {
            StepOutcome<InfoGapAnalysis> gapOutcome = infoGapAnalysisService.analyze(draft.fullContent(), extract);
            CompletenessDecision decision = completenessDecisionService.decide(draft.fullContent(), extract, gapOutcome.value(), run.getId());
            List<String> questions = decision.followUpQuestions();
            if (questions.isEmpty()) {
                questions = List.of("请补充系统、环境、接口、错误信息和影响范围，方便准确分派。");
            }

            String followUpStepId = tracer.begin(AgentStepName.FOLLOW_UP_QUESTION_GENERATE.name(), triageStepId);
            tracer.end(followUpStepId, questions.toString(), null);

            AgentRunResponse response = responseAssembler.needMoreInfo(
                    run.getId(), questions, extractOutcome.llmUsed());
            transactionTemplate.executeWithoutResult(status -> {
                contextPersister.persist(run, gapOutcome.value(), null, null);
                run.setStatus(AgentRunStatus.WAIT_USER_INPUT);
                agentRunRepository.save(run);
            });
            return response;
        }

        // === 阶段2：排查（异步，通过 Kafka 事件触发） ===
        // 分诊已完成优先级评估和路由，同步返回分诊结果。
        // 排查阶段异步执行，产出证据包+参考诊断，附带给接手团队。
        String traceId = run.getTraceId();
        TriageCompletedEvent event = TriageCompletedEvent.of(
                run.getId(), traceId, triageResult, extract,
                draft.fullContent(), extractOutcome.llmUsed()
        );
        triageEventPublisher.publish(investigationTopic, event);

        transactionTemplate.executeWithoutResult(status -> {
            run.setStatus(AgentRunStatus.INVESTIGATING);
            agentRunRepository.save(run);
        });

        return responseAssembler.triageResult(
                run.getId(), triageResult, extractOutcome.llmUsed());
    }

    private int getFollowUpRound(AgentRun run) {
        if (run.getSteps() == null) return 0;
        return (int) run.getSteps().stream()
                .filter(s -> "FOLLOW_UP_QUESTION_GENERATE".equals(s.getStepName()))
                .count();
    }

    private AgentRunResponse failRun(AgentRun run, TicketDraft draft, Exception ex, TraceRecorder tracer) {
        String errorDetail = ex.getClass().getSimpleName()
                + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        String failStepId = tracer.begin(AgentStepName.FINAL.name());
        tracer.end(failStepId, "failed", errorDetail);
        transactionTemplate.executeWithoutResult(status -> {
            run.setStatus(AgentRunStatus.FAILED);
            agentRunRepository.save(run);
        });
        return responseAssembler.failed(run.getId());
    }
}
