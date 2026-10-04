package com.gcll.docagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 分析结论缓存——同简历 + 同分析输入（技能/指令/JD/方向/人群/prompt 版本）的历史结论。
 * 命中时新 run 直接复制 result_json 完成，跳过整条 LLM 流水线。
 */
@TableName("analysis_cache")
public class AnalysisCacheEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    /** 组合哈希键：内容哈希 + 分析输入，缓存命中判定键 */
    private String cacheKey;
    private String resumeId;
    /** 以下输入列仅作审计展示，命中判定只看 cache_key */
    private String skill;
    private String instruction;
    private String jobDescription;
    private String targetDirection;
    private String persona;
    private String promptVersion;
    private String resultJson;
    private Integer scoreOverall;
    private String scoreDimensions;
    /** 产出该结论的原始 run，可回溯完整链路 */
    private String sourceRunId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCacheKey() { return cacheKey; }
    public void setCacheKey(String cacheKey) { this.cacheKey = cacheKey; }

    public String getResumeId() { return resumeId; }
    public void setResumeId(String resumeId) { this.resumeId = resumeId; }

    public String getSkill() { return skill; }
    public void setSkill(String skill) { this.skill = skill; }

    public String getInstruction() { return instruction; }
    public void setInstruction(String instruction) { this.instruction = instruction; }

    public String getJobDescription() { return jobDescription; }
    public void setJobDescription(String jobDescription) { this.jobDescription = jobDescription; }

    public String getTargetDirection() { return targetDirection; }
    public void setTargetDirection(String targetDirection) { this.targetDirection = targetDirection; }

    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }

    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }

    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }

    public Integer getScoreOverall() { return scoreOverall; }
    public void setScoreOverall(Integer scoreOverall) { this.scoreOverall = scoreOverall; }

    public String getScoreDimensions() { return scoreDimensions; }
    public void setScoreDimensions(String scoreDimensions) { this.scoreDimensions = scoreDimensions; }

    public String getSourceRunId() { return sourceRunId; }
    public void setSourceRunId(String sourceRunId) { this.sourceRunId = sourceRunId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
