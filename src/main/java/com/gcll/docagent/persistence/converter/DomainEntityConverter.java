package com.gcll.docagent.persistence.converter;

import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;
import com.gcll.docagent.domain.AgentStep;
import com.gcll.docagent.persistence.entity.AgentRunEntity;
import com.gcll.docagent.persistence.entity.AgentStepEntity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

public final class DomainEntityConverter {

    private DomainEntityConverter() {
    }

    public static AgentRunEntity toEntity(AgentRun run) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setId(run.getId());
        entity.setTraceId(run.getTraceId());
        entity.setSessionId(run.getSessionId());
        entity.setUserId(run.getUserId());
        entity.setStatus(run.getStatus().name());
        entity.setOriginalContent(run.getOriginalContent());
        entity.setCurrentSummary(run.getCurrentSummary());
        entity.setIdempotencyKey(run.getIdempotencyKey());
        entity.setRequestId(run.getRequestId());
        entity.setVersion(run.getVersion());
        entity.setCreatedAt(toLocalDateTime(run.getCreatedAt()));
        entity.setUpdatedAt(toLocalDateTime(run.getUpdatedAt()));
        return entity;
    }

    public static AgentRun toDomain(AgentRunEntity entity) {
        AgentRun run = new AgentRun(
                entity.getId(),
                entity.getTraceId(),
                entity.getSessionId(),
                entity.getUserId(),
                entity.getOriginalContent()
        );
        run.setStatus(AgentRunStatus.valueOf(entity.getStatus()));
        run.setCurrentSummary(entity.getCurrentSummary());
        run.setIdempotencyKey(entity.getIdempotencyKey());
        run.setRequestId(entity.getRequestId());
        if (entity.getVersion() != null) {
            run.setVersion(entity.getVersion());
        }
        return run;
    }

    public static AgentStepEntity toEntity(AgentStep step) {
        AgentStepEntity entity = new AgentStepEntity();
        entity.setId(step.getId());
        entity.setRunId(step.getRunId());
        entity.setStepName(step.getStepName());
        entity.setStatus(step.getStatus());
        entity.setInputSnapshot(step.getInputSnapshot());
        entity.setOutputSnapshot(step.getOutputSnapshot());
        entity.setLlmUsed(step.isLlmUsed());
        entity.setToolUsed(step.getToolUsed());
        entity.setCostMs(step.getCostMs());
        entity.setErrorMessage(step.getErrorMessage());
        entity.setCreatedAt(toLocalDateTime(step.getCreatedAt()));
        return entity;
    }

    public static AgentStep toDomain(AgentStepEntity entity) {
        AgentStep step = new AgentStep(
                entity.getId(),
                entity.getRunId(),
                entity.getStepName(),
                entity.getStatus(),
                toInstant(entity.getCreatedAt())
        );
        step.setInputSnapshot(entity.getInputSnapshot());
        step.setOutputSnapshot(entity.getOutputSnapshot());
        step.setLlmUsed(Boolean.TRUE.equals(entity.getLlmUsed()));
        step.setToolUsed(entity.getToolUsed());
        step.setCostMs(entity.getCostMs() == null ? 0 : entity.getCostMs());
        step.setErrorMessage(entity.getErrorMessage());
        return step;
    }

    private static LocalDateTime toLocalDateTime(Instant instant) {
        if (instant == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private static Instant toInstant(LocalDateTime dateTime) {
        if (dateTime == null) {
            return Instant.now();
        }
        return dateTime.atZone(ZoneId.systemDefault()).toInstant();
    }
}
