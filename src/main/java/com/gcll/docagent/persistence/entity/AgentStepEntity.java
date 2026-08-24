package com.gcll.docagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("agent_step")
public class AgentStepEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    private String parentStepId;
    private String stepName;
    private String status;
    private String inputSnapshot;
    private String outputSnapshot;
    private Boolean llmUsed;
    private String toolUsed;
    private Long costMs;
    private String errorMessage;
    private String spanId;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getParentStepId() { return parentStepId; }
    public void setParentStepId(String parentStepId) { this.parentStepId = parentStepId; }

    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getInputSnapshot() { return inputSnapshot; }
    public void setInputSnapshot(String inputSnapshot) { this.inputSnapshot = inputSnapshot; }

    public String getOutputSnapshot() { return outputSnapshot; }
    public void setOutputSnapshot(String outputSnapshot) { this.outputSnapshot = outputSnapshot; }

    public Boolean getLlmUsed() { return llmUsed; }
    public void setLlmUsed(Boolean llmUsed) { this.llmUsed = llmUsed; }

    public String getToolUsed() { return toolUsed; }
    public void setToolUsed(String toolUsed) { this.toolUsed = toolUsed; }

    public Long getCostMs() { return costMs; }
    public void setCostMs(Long costMs) { this.costMs = costMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getSpanId() { return spanId; }
    public void setSpanId(String spanId) { this.spanId = spanId; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
