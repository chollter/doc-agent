package com.gcll.ticketagent.agent.planner;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.StepOutcome;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class RuleBasedAgentPlanner {

    public StepOutcome<AgentPlan> plan(String userContent, TicketExtractResult extract) {
        if (extract.issueType() == IssueType.CONSULT) {
            return StepOutcome.ruleBased(new AgentPlan(
                    List.of(AgentAction.KNOWLEDGE_SEARCH),
                    List.of(
                            AgentAction.SIMILAR_CASE_SEARCH,
                            AgentAction.QUERY_LOGS,
                            AgentAction.NOTIFY_ONCALL
                    ),
                    "咨询类工单简化调查路径，仅检索知识库",
                    false
            ));
        }
        if (hasOomSignal(userContent, extract)) {
            return StepOutcome.ruleBased(new AgentPlan(
                    List.of(AgentAction.KNOWLEDGE_SEARCH, AgentAction.QUERY_LOGS),
                    List.of(AgentAction.SIMILAR_CASE_SEARCH),
                    "OOM/内存类故障优先查 logs",
                    false
            ));
        }
        if (hasConnectionPoolSignal(userContent, extract)) {
            return StepOutcome.ruleBased(new AgentPlan(
                    List.of(AgentAction.KNOWLEDGE_SEARCH, AgentAction.QUERY_LOGS),
                    List.of(AgentAction.SIMILAR_CASE_SEARCH),
                    "连接池/超时类故障优先查 logs",
                    false
            ));
        }
        return StepOutcome.ruleBased(new AgentPlan(
                List.of(
                        AgentAction.KNOWLEDGE_SEARCH,
                        AgentAction.SIMILAR_CASE_SEARCH,
                        AgentAction.QUERY_LOGS
                ),
                List.of(),
                "默认全量调查步骤",
                false
        ));
    }

    private boolean hasOomSignal(String userContent, TicketExtractResult extract) {
        String lower = userContent == null ? "" : userContent.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "oom", "oomkilled", "heap space", "内存", "outofmemory")) {
            return true;
        }
        if (extract.severitySignals() != null && extract.severitySignals().contains("RESOURCE_EXHAUSTED")) {
            return true;
        }
        String error = extract.errorMessage();
        return error != null && error.toLowerCase(Locale.ROOT).contains("oom");
    }

    private boolean hasConnectionPoolSignal(String userContent, TicketExtractResult extract) {
        String lower = userContent == null ? "" : userContent.toLowerCase(Locale.ROOT);
        if (containsAny(lower, "hikari", "connection pool", "连接池", "connection timeout")) {
            return true;
        }
        if (extract.severitySignals() != null && extract.severitySignals().contains("DEPENDENCY_TIMEOUT")) {
            return true;
        }
        return false;
    }

    private boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
