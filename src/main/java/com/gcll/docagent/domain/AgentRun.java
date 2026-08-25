package com.gcll.docagent.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent 运行聚合根——一次文档分析的完整生命周期。
 */
public class AgentRun {
    private final String id;
    private final String traceId;
    private final String sessionId;
    private final String userId;
    private AgentRunStatus status;
    private String originalContent;
    private String currentSummary;
    private String lastError;
    private String fileName;
    private String fileType;
    private String instruction;
    private Integer sectionCount;
    private String executionMode;
    private String resultJson;
    private Instant startedAt;
    private Instant finishedAt;
    private String idempotencyKey;
    private String requestId;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;
    private final List<AgentStep> steps = new ArrayList<>();

    public AgentRun(String id, String traceId, String sessionId, String userId, String originalContent) {
        this.id = id;
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.userId = userId;
        this.originalContent = originalContent;
        this.status = AgentRunStatus.RUNNING;
        this.startedAt = Instant.now();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public String getId() { return id; }
    public String getTraceId() { return traceId; }
    public String getSessionId() { return sessionId; }
    public String getUserId() { return userId; }

    public AgentRunStatus getStatus() { return status; }
    public void setStatus(AgentRunStatus status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public String getOriginalContent() { return originalContent; }
    public void setOriginalContent(String originalContent) {
        this.originalContent = originalContent;
        this.updatedAt = Instant.now();
    }

    public String getCurrentSummary() { return currentSummary; }
    public void setCurrentSummary(String currentSummary) {
        this.currentSummary = currentSummary;
        this.updatedAt = Instant.now();
    }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) {
        this.lastError = lastError;
        this.updatedAt = Instant.now();
    }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }

    public Integer getSectionCount() { return sectionCount; }
    public void setSectionCount(Integer sectionCount) { this.sectionCount = sectionCount; }

    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String executionMode) { this.executionMode = executionMode; }

    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
        this.updatedAt = Instant.now();
    }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) {
        this.requestId = requestId;
        this.updatedAt = Instant.now();
    }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public List<AgentStep> getSteps() { return steps; }
}
