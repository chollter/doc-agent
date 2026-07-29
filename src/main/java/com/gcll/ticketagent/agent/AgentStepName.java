package com.gcll.ticketagent.agent;

public enum AgentStepName {
    SUBMITTED,
    PREPROCESS,
    TICKET_EXTRACT,
    TRIAGE_PIPELINE,
    /** @deprecated v2 追问已改为 FOLLOW_UP_QUESTION_GENERATE + TRIAGE_PIPELINE，不再单独记录此步骤 */
    @Deprecated
    INFO_GAP_ANALYSIS,
    /** @deprecated v2 追问已改为 FOLLOW_UP_QUESTION_GENERATE + TRIAGE_PIPELINE，不再单独记录此步骤 */
    @Deprecated
    COMPLETENESS_DECISION,
    /** @deprecated v2 分诊路由已整合到 TRIAGE_PIPELINE，不再单独记录此步骤 */
    @Deprecated
    TRIAGE_DECISION,
    FOLLOW_UP_QUESTION_GENERATE,
    AGENT_PLAN,
    KNOWLEDGE_SEARCH,
    TOOL_SELECTION,
    EVIDENCE_COLLECTION,
    ROOT_CAUSE_ANALYSIS,
    PRIORITY_EVALUATION,
    TEAM_ROUTING,
    SUGGESTION_GENERATION,
    HUMAN_CONFIRM_DECISION,
    WAIT_HUMAN_CONFIRM,
    FINAL
}
