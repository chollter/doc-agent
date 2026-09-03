package com.gcll.docagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * LLM 交互日志——记录每次 LLM 调用的完整输入输出。
 * 用于构建优化证据链：对比不同 prompt 版本 / 模型的实际效果。
 */
@TableName("llm_interaction")
public class LlmInteractionEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String runId;
    /** 调用点：ENTITY_EXTRACT / REACT_ROUND_N / DIRECT_LLM / FOLLOW_UP */
    private String callSite;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    /** 发送给 LLM 的完整内容 */
    private String fullPrompt;
    /** LLM 返回的完整内容 */
    private String fullResponse;
    private Long durationMs;
    private Boolean success;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }

    public String getCallSite() { return callSite; }
    public void setCallSite(String callSite) { this.callSite = callSite; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public Integer getPromptTokens() { return promptTokens; }
    public void setPromptTokens(Integer promptTokens) { this.promptTokens = promptTokens; }

    public Integer getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(Integer completionTokens) { this.completionTokens = completionTokens; }

    public String getFullPrompt() { return fullPrompt; }
    public void setFullPrompt(String fullPrompt) { this.fullPrompt = fullPrompt; }

    public String getFullResponse() { return fullResponse; }
    public void setFullResponse(String fullResponse) { this.fullResponse = fullResponse; }

    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }

    public Boolean getSuccess() { return success; }
    public void setSuccess(Boolean success) { this.success = success; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
