package com.gcll.ticketagent.observability.trace;

import java.time.Instant;
import java.util.UUID;

/**
 * 每步一个 Span，有 parentId 表达嵌套因果关系。
 * <p>
 * 记录原则：存指纹不存原文。
 * <ul>
 *   <li>inputSnapshot/outputSnapshot：存结构化摘要（字段级指纹），不存工单原文</li>
 *   <li>errorMessage：存异常类名+关键信息，不存完整堆栈</li>
 *   <li>spanId：确定性指纹 ID，格式：{traceId}:{sequence}</li>
 * </ul>
 */
public class Span {
    private final String id;
    private final String runId;
    private final String parentStepId;
    private final String stepName;
    private String status;
    private String inputSnapshot;
    private String outputSnapshot;
    private boolean llmUsed;
    private String toolUsed;
    private long costMs;
    private String errorMessage;
    private final String spanId;
    private final Instant startedAt;
    private Instant finishedAt;

    private Span(Builder builder) {
        this.id = builder.id;
        this.runId = builder.runId;
        this.parentStepId = builder.parentStepId;
        this.stepName = builder.stepName;
        this.status = builder.status;
        this.inputSnapshot = builder.inputSnapshot;
        this.outputSnapshot = builder.outputSnapshot;
        this.llmUsed = builder.llmUsed;
        this.toolUsed = builder.toolUsed;
        this.costMs = builder.costMs;
        this.errorMessage = builder.errorMessage;
        this.spanId = builder.spanId;
        this.startedAt = builder.startedAt;
        this.finishedAt = builder.finishedAt;
    }

    /**
     * 生成 spanId：{traceId}:{序号}
     */
    public static String generateSpanId(String traceId, int sequence) {
        return traceId + ":" + sequence;
    }

    // --- getters ---

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getParentStepId() { return parentStepId; }
    public String getStepName() { return stepName; }
    public String getStatus() { return status; }
    public String getInputSnapshot() { return inputSnapshot; }
    public String getOutputSnapshot() { return outputSnapshot; }
    public boolean isLlmUsed() { return llmUsed; }
    public String getToolUsed() { return toolUsed; }
    public long getCostMs() { return costMs; }
    public String getErrorMessage() { return errorMessage; }
    public String getSpanId() { return spanId; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }

    // --- setters for mutable fields ---

    public void setStatus(String status) { this.status = status; }
    public void setOutputSnapshot(String outputSnapshot) { this.outputSnapshot = outputSnapshot; }
    public void setCostMs(long costMs) { this.costMs = costMs; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    // --- builder ---

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private String runId;
        private String parentStepId;
        private String stepName;
        private String status = "RUNNING";
        private String inputSnapshot;
        private String outputSnapshot;
        private boolean llmUsed;
        private String toolUsed;
        private long costMs;
        private String errorMessage;
        private String spanId;
        private Instant startedAt;
        private Instant finishedAt;

        public Builder id(String id) { this.id = id; return this; }
        public Builder runId(String runId) { this.runId = runId; return this; }
        public Builder parentStepId(String parentStepId) { this.parentStepId = parentStepId; return this; }
        public Builder stepName(String stepName) { this.stepName = stepName; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder inputSnapshot(String inputSnapshot) { this.inputSnapshot = inputSnapshot; return this; }
        public Builder outputSnapshot(String outputSnapshot) { this.outputSnapshot = outputSnapshot; return this; }
        public Builder llmUsed(boolean llmUsed) { this.llmUsed = llmUsed; return this; }
        public Builder toolUsed(String toolUsed) { this.toolUsed = toolUsed; return this; }
        public Builder costMs(long costMs) { this.costMs = costMs; return this; }
        public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public Builder spanId(String spanId) { this.spanId = spanId; return this; }
        public Builder startedAt(Instant startedAt) { this.startedAt = startedAt; return this; }
        public Builder finishedAt(Instant finishedAt) { this.finishedAt = finishedAt; return this; }

        public Span build() {
            if (id == null) id = UUID.randomUUID().toString();
            if (startedAt == null) startedAt = Instant.now();
            return new Span(this);
        }
    }
}
