package com.gcll.ticketagent.agent;

import com.gcll.ticketagent.agent.workflow.AnalysisWorkflowService;
import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.extract.TicketExtractService;
import com.gcll.ticketagent.llm.StepOutcome;
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
    private final AnalysisWorkflowService analysisWorkflowService;
    private final AuditLogService auditLogService;
    private final AgentRunRepository agentRunRepository;
    private final TransactionTemplate transactionTemplate;
    private final AgentRunContextPersister contextPersister;
    private final AgentResponseAssembler responseAssembler;
    private final AgentAuditSummaryFormatter summaryFormatter;

    public AgentRuntime(
            TicketExtractService ticketExtractService,
            TriagePipeline triagePipeline,
            InfoGapAnalysisService infoGapAnalysisService,
            CompletenessDecisionService completenessDecisionService,
            AnalysisWorkflowService analysisWorkflowService,
            AuditLogService auditLogService,
            AgentRunRepository agentRunRepository,
            TransactionTemplate transactionTemplate,
            AgentRunContextPersister contextPersister,
            AgentResponseAssembler responseAssembler,
            AgentAuditSummaryFormatter summaryFormatter
    ) {
        this.ticketExtractService = ticketExtractService;
        this.triagePipeline = triagePipeline;
        this.infoGapAnalysisService = infoGapAnalysisService;
        this.completenessDecisionService = completenessDecisionService;
        this.analysisWorkflowService = analysisWorkflowService;
        this.auditLogService = auditLogService;
        this.agentRunRepository = agentRunRepository;
        this.transactionTemplate = transactionTemplate;
        this.contextPersister = contextPersister;
        this.responseAssembler = responseAssembler;
        this.summaryFormatter = summaryFormatter;
    }

    public AgentRunResponse execute(AgentRun run, TicketDraft draft) {
        LlmRunContext.bind(run.getId());
        try {
            return doExecute(run, draft);
        } catch (Exception ex) {
            // 记录完整异常类名 + 堆栈。很多异常（如 NPE）getMessage() 为 null，
            // 只记 message 会丢失根因（表现为 error=null）。issueType={}/step 帮助定位失败环节。
            log.warn("Agent run failed, runId={}, issueType={}, step={}, exception={}, message={}",
                    run.getId(), run.getIssueType(), lastStepHint(run),
                    ex.getClass().getName(),
                    ex.getMessage() == null ? "(no message)" : ex.getMessage(),
                    ex);
            return failRun(run, draft, ex);
        } finally {
            LlmRunContext.clear();
        }
    }

    /** 失败时给出当前已执行到的步骤提示，辅助定位。 */
    private String lastStepHint(AgentRun run) {
        if (run.getSteps() == null || run.getSteps().isEmpty()) {
            return "unknown";
        }
        return run.getSteps().getLast().getStepName();
    }

    private AgentRunResponse doExecute(AgentRun run, TicketDraft draft) {
        long start = System.currentTimeMillis();

        // === 阶段1：分诊（同步，秒级出结果） ===
        // Step 1: 结构化抽取（保留旧路径，TriagePipeline 内部会使用 extract 结果）
        StepOutcome<TicketExtractResult> extractOutcome = ticketExtractService.extract(draft.fullContent());
        TicketExtractResult extract = extractOutcome.value();
        run.setIssueType(extract.issueType().name());
        long extractCostMs = extractOutcome.costMs() > 0 ? extractOutcome.costMs() : System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.TICKET_EXTRACT, draft.fullContent(),
                summaryFormatter.summarizeExtract(extract), extractOutcome.llmUsed(),
                extractOutcome.llmUsed() ? "SpringAI" : null, extractCostMs, null);

        // Step 2: 分诊 Pipeline（规则前置 → LLM分类+粗抽 → 定向精抽 → 规则校验 → 优先级 → 路由）
        start = System.currentTimeMillis();
        int followUpRound = getFollowUpRound(run);
        TriageResult triageResult = triagePipeline.execute(draft.fullContent(), extract, run.getId(), followUpRound);
        long triageCostMs = System.currentTimeMillis() - start;
        auditLogService.recordStep(run, AgentStepName.TRIAGE_PIPELINE,
                "extract issueType=" + extract.issueType(),
                "issueType=" + triageResult.issueType()
                        + ",priority=" + triageResult.priority()
                        + ",confidence=" + triageResult.confidence()
                        + ",source=" + triageResult.source()
                        + ",routedTeam=" + triageResult.routedTeam()
                        + ",needFollowUp=" + triageResult.needFollowUp()
                        + ",round=" + followUpRound,
                false, null, triageCostMs, null);

        // 更新 AgentRun 的 issueType 和 priority
        run.setIssueType(triageResult.issueType().name());

        // Step 3: 追问决策（四层兜底）
        TriagePipeline.FollowUpDecision followUpDecision = triagePipeline.decideFollowUp(triageResult, extract);
        if (followUpDecision.needFollowUp()) {
            // 保留旧追问路径的 gap+completeness 分析（兼容现有 FollowUpQuestionService）
            StepOutcome<InfoGapAnalysis> gapOutcome = infoGapAnalysisService.analyze(draft.fullContent(), extract);
            CompletenessDecision decision = completenessDecisionService.decide(draft.fullContent(), extract, gapOutcome.value(), run.getId());
            List<String> questions = decision.followUpQuestions();
            if (questions.isEmpty()) {
                questions = List.of("请补充系统、环境、接口、错误信息和影响范围，方便准确分派。");
            }

            auditLogService.recordStep(run, AgentStepName.FOLLOW_UP_QUESTION_GENERATE,
                    "round=" + followUpDecision.nextRound(), questions.toString(),
                    false, null, System.currentTimeMillis() - start, null);

            AgentRunResponse response = responseAssembler.needMoreInfo(
                    run.getId(), questions, extractOutcome.llmUsed());
            transactionTemplate.executeWithoutResult(status -> {
                contextPersister.persist(run, gapOutcome.value(), null, null);
                run.setStatus(AgentRunStatus.WAIT_USER_INPUT);
                agentRunRepository.save(run);
            });
            return response;
        }

        // === 阶段2：排查（异步，工单已路由后后台跑） ===
        // 当前仍走同步 analysisWorkflowService，后续 Day9-12 拆分为异步
        return analysisWorkflowService.execute(run, draft, extract, null, extractOutcome.llmUsed());
    }

    /** 从 AgentRun 的步骤历史推断追问轮次。 */
    private int getFollowUpRound(AgentRun run) {
        if (run.getSteps() == null) return 0;
        return (int) run.getSteps().stream()
                .filter(s -> "FOLLOW_UP_QUESTION_GENERATE".equals(s.getStepName()))
                .count();
    }

    private AgentRunResponse failRun(AgentRun run, TicketDraft draft, Exception ex) {
        // 记录异常类名 + message，避免 NPE 等 message=null 时审计里只剩 "null"
        String errorDetail = ex.getClass().getSimpleName()
                + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        transactionTemplate.executeWithoutResult(status -> {
            auditLogService.recordStep(run, AgentStepName.FINAL, draft.fullContent(), "failed",
                    false, null, 0, errorDetail);
            run.setStatus(AgentRunStatus.FAILED);
            agentRunRepository.save(run);
        });
        return responseAssembler.failed(run.getId());
    }
}
