package com.gcll.docagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 追问轮次的问答对（turn>0 的 user/assistant 消息）。 */
@TableName("agent_message")
public class AgentMessageEntity {
    @TableId(type = IdType.ASSIGN_UUID)
    private String id;
    private String runId;
    private Integer turn;
    private String role;
    private String content;
    private LocalDateTime createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public Integer getTurn() { return turn; }
    public void setTurn(Integer turn) { this.turn = turn; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
