package com.gcll.ticketagent.agent.planner;

public enum AgentAction {
    KNOWLEDGE_SEARCH,
    SIMILAR_CASE_SEARCH,
    QUERY_LOGS,
    NOTIFY_ONCALL,
    EXECUTE_REMEDIATION;

    public static AgentAction fromName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return AgentAction.valueOf(name.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
