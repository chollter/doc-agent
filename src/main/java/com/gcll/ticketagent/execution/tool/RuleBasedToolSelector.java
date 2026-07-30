package com.gcll.ticketagent.execution.tool;

import com.gcll.ticketagent.agent.planner.AgentAction;
import com.gcll.ticketagent.agent.planner.AgentPlan;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.StepOutcome;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@Service
public class RuleBasedToolSelector {

    public StepOutcome<ToolSelection> select(String userContent, TicketExtractResult extract, AgentPlan plan) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (AgentAction action : plan.actions()) {
            String toolName = mapActionToTool(action);
            if (toolName != null) {
                selected.add(toolName);
            }
        }
        return StepOutcome.ruleBased(new ToolSelection(
                List.copyOf(selected),
                Map.of(),
                "基于 AgentPlan 映射工具白名单",
                false
        ));
    }

    private String mapActionToTool(AgentAction action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case SIMILAR_CASE_SEARCH -> "searchSimilarCases";
            case QUERY_LOGS -> "query_logs";
            case NOTIFY_ONCALL -> "notifyOncall";
            case EXECUTE_REMEDIATION -> "executeRemediation";
            case KNOWLEDGE_SEARCH -> null;
        };
    }
}
