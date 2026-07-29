package com.gcll.ticketagent.ticket;

import com.baomidou.mybatisplus.core.exceptions.MybatisPlusException;
import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.agent.TicketAgentOrchestrator;
import com.gcll.ticketagent.api.BusinessException;
import com.gcll.ticketagent.api.ErrorCode;
import com.gcll.ticketagent.api.dto.AgentRunResponse;
import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.api.dto.SubmitAgentRunRequest;
import com.gcll.ticketagent.api.dto.SupplementMessageRequest;
import com.gcll.ticketagent.async.AgentRunEventPublisher;
import com.gcll.ticketagent.async.AgentRunExecutionEvent;
import com.gcll.ticketagent.async.AsyncAgentRunProperties;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.infra.RunConcurrencyService;
import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

@Service
public class TicketApplicationService {

    private static final Logger log = LoggerFactory.getLogger(TicketApplicationService.class);

    private final AgentRunRepository agentRunRepository;
    private final TicketAgentOrchestrator orchestrator;
    private final TransactionTemplate transactionTemplate;
    private final RunConcurrencyService runConcurrencyService;
    private final AsyncAgentRunProperties asyncProperties;
    private final Optional<AgentRunEventPublisher> eventPublisher;
    private final TicketInputProcessor inputProcessor;
    private final AuditLogService auditLogService;
    private final AgentMetrics agentMetrics;

    public TicketApplicationService(
            AgentRunRepository agentRunRepository,
            TicketAgentOrchestrator orchestrator,
            TransactionTemplate transactionTemplate,
            RunConcurrencyService runConcurrencyService,
            AsyncAgentRunProperties asyncProperties,
            Optional<AgentRunEventPublisher> eventPublisher,
            TicketInputProcessor inputProcessor,
            AuditLogService auditLogService,
            AgentMetrics agentMetrics
    ) {
        this.agentRunRepository = agentRunRepository;
        this.orchestrator = orchestrator;
        this.transactionTemplate = transactionTemplate;
        this.runConcurrencyService = runConcurrencyService;
        this.asyncProperties = asyncProperties;
        this.eventPublisher = eventPublisher;
        this.inputProcessor = inputProcessor;
        this.auditLogService = auditLogService;
        this.agentMetrics = agentMetrics;
    }

    public AgentRunResponse submit(SubmitAgentRunRequest request) {
        ProcessedInput processed = inputProcessor.process(request.title(), request.content(), request.metadata());
        String idempotencyKey = resolveSubmitIdempotencyKey(request, processed.content());

        Optional<AgentRunResponse> idempotent = findIdempotentResponse(idempotencyKey);
        if (idempotent.isPresent()) {
            return idempotent.get();
        }

        AgentRun run = new AgentRun(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                request.sessionId(),
                request.userId(),
                processed.content()
        );
        run.setIdempotencyKey(idempotencyKey);
        if (request.requestId() != null && !request.requestId().isBlank()) {
            run.setRequestId(request.requestId().trim());
        }

        try {
            transactionTemplate.executeWithoutResult(status -> agentRunRepository.save(run));
        } catch (DuplicateKeyException ex) {
            log.info("Concurrent submit hit DB idempotency, key={}", idempotencyKey);
            return findIdempotentResponse(idempotencyKey)
                    .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE, "Idempotent run conflict."));
        }

        runConcurrencyService.rememberRunId(idempotencyKey, run.getId());
        agentMetrics.recordAgentRun();
        recordL0Audit(run, processed);

        if (asyncProperties.isEnabled()) {
            eventPublisher.ifPresent(publisher ->
                    publisher.publish(new AgentRunExecutionEvent(run.getId(), run.getTraceId(), "submit"))
            );
            return accepted(run, "工单已提交，Agent 将通过 Kafka 异步执行。");
        }

        TicketDraft draft = new TicketDraft(processed.content());
        return executeRun(run, draft);
    }

    public AgentRunResponse supplement(String runId, SupplementMessageRequest request) {
        ProcessedInput processed = inputProcessor.process(null, request.content(), request.metadata());
        String idempotencyKey = runConcurrencyService.supplementKey(runId, processed.content());

        if (runConcurrencyService.getRunIdByIdempotencyKey(idempotencyKey).isPresent()) {
            AgentRun run = getRun(runId);
            return idempotentResponse(run, "重复补充已命中幂等保护，返回已有运行记录。");
        }

        AgentRun run;
        try {
            run = transactionTemplate.execute(status -> {
                AgentRun existing = agentRunRepository.findById(runId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND,
                                "Agent run not found: " + runId));
                if (existing.getStatus() != AgentRunStatus.WAIT_USER_INPUT) {
                    throw new BusinessException(ErrorCode.RUN_NOT_WAITING_INPUT,
                            "Agent run is not waiting for user input.");
                }

                TicketDraft draft = new TicketDraft(existing.getOriginalContent());
                draft.append(processed.content());
                existing.setOriginalContent(draft.fullContent());
                existing.setStatus(AgentRunStatus.RUNNING);
                agentRunRepository.save(existing);
                return existing;
            });
        } catch (MybatisPlusException ex) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "Concurrent supplement conflict, please retry.");
        }

        recordL0Audit(run, processed);

        TicketDraft draft = new TicketDraft(run.getOriginalContent());
        if (asyncProperties.isEnabled()) {
            eventPublisher.ifPresent(publisher ->
                    publisher.publish(new AgentRunExecutionEvent(
                            run.getId(), run.getTraceId(), "supplement", idempotencyKey))
            );
            return accepted(run, "补充信息已提交，Agent 将通过 Kafka 异步继续执行。");
        }

        AgentRunResponse response = executeRun(run, draft);
        rememberSupplementIdempotency(idempotencyKey, run.getId());
        return response;
    }

    public void executeExistingRun(AgentRunExecutionEvent event) {
        AgentRun run = getRun(event.runId());
        TicketDraft draft = new TicketDraft(run.getOriginalContent());
        executeRun(run, draft);
        if (event.idempotencyKey() != null && !event.idempotencyKey().isBlank()) {
            rememberSupplementIdempotency(event.idempotencyKey(), event.runId());
        }
    }

    public AgentRun getRun(String runId) {
        return agentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND,
                        "Agent run not found: " + runId));
    }

    private AgentRunResponse executeRun(AgentRun run, TicketDraft draft) {
        return runConcurrencyService.withRunLock(run.getId(), () -> orchestrator.execute(run, draft))
                .orElseGet(() -> concurrentResponse(run, "工单正在处理中，请稍后查看结果。"));
    }

    private void rememberSupplementIdempotency(String idempotencyKey, String runId) {
        Optional<String> existing = runConcurrencyService.rememberRunIdIfAbsent(idempotencyKey, runId);
        if (existing.isPresent() && !existing.get().equals(runId)) {
            log.warn("Supplement idempotency key collision, key={}, existingRunId={}, runId={}",
                    idempotencyKey, existing.get(), runId);
        }
    }

    private String resolveSubmitIdempotencyKey(SubmitAgentRunRequest request, String content) {
        if (request.requestId() != null && !request.requestId().isBlank()) {
            return runConcurrencyService.clientRequestKey(request.requestId());
        }
        return runConcurrencyService.submitKey(request.sessionId(), request.userId(), content);
    }

    private Optional<AgentRunResponse> findIdempotentResponse(String idempotencyKey) {
        Optional<String> cachedRunId = runConcurrencyService.getRunIdByIdempotencyKey(idempotencyKey);
        return cachedRunId.map(s -> idempotentResponse(getRun(s),
                "重复提交已命中幂等保护，返回已有运行记录。")).or(() -> agentRunRepository.findByIdempotencyKey(idempotencyKey)
                .map(run -> {
                    runConcurrencyService.rememberRunId(idempotencyKey, run.getId());
                    return idempotentResponse(run, "重复提交已命中幂等保护，返回已有运行记录。");
                }));
    }

    private void recordL0Audit(AgentRun run, ProcessedInput processed) {
        auditLogService.recordStep(run, AgentStepName.SUBMITTED, null, processed.content(),
                false, null, 0, null);
        auditLogService.recordStep(run, AgentStepName.PREPROCESS, processed.content(),
                processed.preprocessSummary(), false, null, 0, null);
    }

    private AgentRunResponse idempotentResponse(AgentRun run, String message) {
        return new AgentRunResponse(
                run.getId(),
                run.getStatus(),
                ReplyTypeForStatus.replyType(run.getStatus()),
                message,
                null,
                null,
                false
        );
    }

    private AgentRunResponse accepted(AgentRun run, String message) {
        return new AgentRunResponse(
                run.getId(),
                run.getStatus(),
                ReplyType.TICKET_ANALYSIS_RESULT,
                message,
                null,
                null,
                false
        );
    }

    private AgentRunResponse concurrentResponse(AgentRun run, String message) {
        return new AgentRunResponse(
                run.getId(),
                AgentRunStatus.RUNNING,
                ReplyType.TICKET_ANALYSIS_RESULT,
                message,
                null,
                null,
                false
        );
    }

    private static final class ReplyTypeForStatus {
        private ReplyTypeForStatus() {
        }

        private static ReplyType replyType(AgentRunStatus status) {
            if (status == AgentRunStatus.WAIT_USER_INPUT) {
                return ReplyType.NEED_MORE_INFO;
            }
            return ReplyType.TICKET_ANALYSIS_RESULT;
        }
    }
}
