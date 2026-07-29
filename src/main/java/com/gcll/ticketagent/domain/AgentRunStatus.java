package com.gcll.ticketagent.domain;

public enum AgentRunStatus {
    RUNNING,
    INVESTIGATING,
    WAIT_USER_INPUT,
    WAIT_HUMAN_CONFIRM,
    FINAL,
    FAILED,
    ESCALATED
}
