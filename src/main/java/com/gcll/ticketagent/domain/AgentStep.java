package com.gcll.ticketagent.domain;

import java.time.Instant;

/**
 * Agent 执行步骤——一个工单一条 Trace，每步一个 Span。
 * <p>
 * v2 新增：
 * <ul>
 *   <li>parentStepId：父步骤 ID，表达嵌套因果关系（如 ReAct 循环中"分析→调工具→再分析"）</li>
 *   <li>spanId：步骤指纹 ID，用于跨系统追踪（非自增主键）</li>
 *   <li>startedAt / finishedAt：精确计算 span 耗时（不依赖 costMs 推算）</li>
 * </ul>
 * 记录原则：存指纹不存原文。inputSnapshot/outputSnapshot 应存结构化摘要，不存工单原文。
 */
public class AgentStep {
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
    private final Instant createdAt;
    private final Instant startedAt;
    private Instant finishedAt;
    private String detail;

    /**
     * v2 完整构造器
     */
    public AgentStep(String id, String runId, String parentStepId, String stepName,
                     String status, String spanId, Instant createdAt, Instant startedAt) {
        this.id = id;
        this.runId = runId;
        this.parentStepId = parentStepId;
        this.stepName = stepName;
        this.status = status;
        this.spanId = spanId;
        this.createdAt = createdAt;
        this.startedAt = startedAt;
    }

    /**
     * v1 兼容构造器（parentStepId=null, spanId=null, startedAt=createdAt）
     */
    public AgentStep(String id, String runId, String stepName, String status, Instant createdAt) {
        this(id, runId, null, stepName, status, null, createdAt, createdAt);
    }

    /**
     * v1 legacy 工厂方法
     */
    public static AgentStep legacy(String id, String runId, String name, String detail, Instant createdAt) {
        AgentStep step = new AgentStep(id, runId, name, "SUCCESS", createdAt);
        step.detail = detail;
        step.outputSnapshot = detail;
        return step;
    }

    // --- getters ---

    public String getId() { return id; }
    public String getRunId() { return runId; }
    public String getParentStepId() { return parentStepId; }
    public String getStepName() { return stepName; }
    public String getName() { return stepName; }
    public String getStatus() { return status; }
    public String getInputSnapshot() { return inputSnapshot; }
    public String getOutputSnapshot() { return outputSnapshot; }
    public boolean isLlmUsed() { return llmUsed; }
    public String getToolUsed() { return toolUsed; }
    public long getCostMs() { return costMs; }
    public String getErrorMessage() { return errorMessage; }
    public String getSpanId() { return spanId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public String getDetail() { return detail; }

    // --- setters ---

    public void setStatus(String status) { this.status = status; }
    public void setInputSnapshot(String inputSnapshot) { this.inputSnapshot = inputSnapshot; }
    public void setOutputSnapshot(String outputSnapshot) { this.outputSnapshot = outputSnapshot; }
    public void setLlmUsed(boolean llmUsed) { this.llmUsed = llmUsed; }
    public void setToolUsed(String toolUsed) { this.toolUsed = toolUsed; }
    public void setCostMs(long costMs) { this.costMs = costMs; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
}
