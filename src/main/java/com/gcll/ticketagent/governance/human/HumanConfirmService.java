package com.gcll.ticketagent.governance.human;

import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.api.BusinessException;
import com.gcll.ticketagent.api.ErrorCode;
import com.gcll.ticketagent.audit.AuditLogService;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.governance.notification.NotificationRequest;
import com.gcll.ticketagent.governance.notification.NotificationResult;
import com.gcll.ticketagent.governance.notification.NotificationService;
import com.gcll.ticketagent.human.PendingAction;
import com.gcll.ticketagent.human.PendingActionStatus;
import com.gcll.ticketagent.human.PendingActionType;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.persistence.repository.PendingActionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class HumanConfirmService {

    private final PendingActionRepository pendingActionRepository;
    private final AgentRunRepository agentRunRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    public HumanConfirmService(PendingActionRepository pendingActionRepository,
                               AgentRunRepository agentRunRepository,
                               AuditLogService auditLogService,
                               NotificationService notificationService) {
        this.pendingActionRepository = pendingActionRepository;
        this.agentRunRepository = agentRunRepository;
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
    }

    public PendingAction createDispatchAction(AgentRun run, String payload, String reason, String targetTeam) {
        PendingAction action = new PendingAction(
                UUID.randomUUID().toString(),
                run.getId(),
                PendingActionType.DISPATCH,
                payload,
                reason,
                targetTeam
        );
        return pendingActionRepository.save(action);
    }

    public List<PendingAction> pending() {
        return pendingActionRepository.findPending();
    }

    @Transactional
    public AgentRun confirm(String actionId, String confirmedBy, PendingActionType actionType) {
        PendingAction action = pendingActionRepository.findById(actionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_NOT_FOUND, "Pending action not found: " + actionId));
        if (action.getStatus() != PendingActionStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "Pending action already decided.");
        }
        action.confirm(confirmedBy);
        pendingActionRepository.save(action);

        AgentRun run = agentRunRepository.findById(action.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found"));
        run.setStatus(AgentRunStatus.FINAL);
        agentRunRepository.save(run);

        TraceRecorder tracer = auditLogService.createTracer(run);
        String stepId = tracer.begin(AgentStepName.FINAL.name());

        String auditOutput;
        String errorMessage = null;
        if (action.getActionType() == PendingActionType.DISPATCH) {
            NotificationRequest request = new NotificationRequest(
                    action.getId(), run.getId(), action.getTargetTeam(), run.getPriority(),
                    confirmedBy, run.getTraceId(), action.getPayload());
            NotificationResult result;
            try {
                result = notificationService.send(request);
            } catch (Throwable t) {
                result = NotificationResult.fail(t.getClass().getSimpleName());
            }
            if (result.success()) {
                auditOutput = "dispatched to " + action.getTargetTeam() + " via " + notificationService.channel()
                        + ", confirmed by " + confirmedBy;
            } else {
                auditOutput = "dispatch confirmed by " + confirmedBy + ", notification failed: " + result.errorType();
                errorMessage = "notification failed: " + result.errorType();
            }
        } else {
            auditOutput = "confirmed by " + confirmedBy;
        }
        tracer.end(stepId, auditOutput, errorMessage);
        return run;
    }

    @Transactional
    public AgentRun reject(String actionId, String confirmedBy) {
        PendingAction action = pendingActionRepository.findById(actionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_NOT_FOUND, "Pending action not found: " + actionId));
        if (action.getStatus() != PendingActionStatus.PENDING) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "Pending action already decided.");
        }
        action.reject(confirmedBy);
        pendingActionRepository.save(action);

        AgentRun run = agentRunRepository.findById(action.getRunId())
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found"));
        run.setStatus(AgentRunStatus.ESCALATED);
        agentRunRepository.save(run);

        TraceRecorder tracer = auditLogService.createTracer(run);
        String stepId = tracer.begin(AgentStepName.FINAL.name());
        tracer.end(stepId, "rejected by " + confirmedBy + ", escalated", null);
        return run;
    }
}
