package com.gcll.docagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 循环检查点：每轮覆盖写入，崩溃恢复的物理基础。 */
@TableName("agent_checkpoint")
public class LoopCheckpointEntity {
    @TableId(type = IdType.INPUT)
    private String runId;
    private Integer round;
    private String stateJson;
    private LocalDateTime updatedAt;

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Integer getRound() { return round; }
    public void setRound(Integer round) { this.round = round; }
    public String getStateJson() { return stateJson; }
    public void setStateJson(String stateJson) { this.stateJson = stateJson; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
