package com.gcll.docagent.human;

import java.time.Instant;

public class PendingAction {
    private final String id;
    private final String runId;
    private final PendingActionType actionType;
    private PendingActionStatus status;
    private final String payload;
    private final String reason;
    private String targetTeam;
    private final Instant createdAt;
    private Instant confirmedAt;
    private String confirmedBy;

    public PendingAction(String id, String runId, PendingActionType actionType, String payload, String reason) {
        this.id = id;
        this.runId = runId;
        this.actionType = actionType;
        this.payload = payload;
        this.reason = reason;
        this.status = PendingActionStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public PendingAction(String id, String runId, PendingActionType actionType,
                         String payload, String reason, String targetTeam) {
        this(id, runId, actionType, payload, reason);
        this.targetTeam = targetTeam;
    }

    public String getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public PendingActionType getActionType() {
        return actionType;
    }

    public PendingActionStatus getStatus() {
        return status;
    }

    public void confirm(String confirmedBy) {
        this.status = PendingActionStatus.CONFIRMED;
        this.confirmedBy = confirmedBy;
        this.confirmedAt = Instant.now();
    }

    public void reject(String confirmedBy) {
        this.status = PendingActionStatus.REJECTED;
        this.confirmedBy = confirmedBy;
        this.confirmedAt = Instant.now();
    }

    public String getPayload() {
        return payload;
    }

    public String getReason() {
        return reason;
    }

    public String getTargetTeam() {
        return targetTeam;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }
}
