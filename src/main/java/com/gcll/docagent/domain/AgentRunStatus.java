package com.gcll.docagent.domain;

public enum AgentRunStatus {
    QUEUED,
    RUNNING,
    ANALYZING,
    WAIT_HUMAN_CONFIRM,
    COMPLETED,
    FAILED
}
