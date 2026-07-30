package com.gcll.ticketagent.agent.planner;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuleBasedAgentPlannerTest {

    private final RuleBasedAgentPlanner planner = new RuleBasedAgentPlanner();

    @Test
    void oomScenarioSkipsSimilarCaseSearch() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统",
                "支付服务",
                "支付下单接口",
                null,
                "OOMKilled/内存超限",
                "生产",
                "多个用户",
                "上午10点",
                null,
                List.of("RESOURCE_EXHAUSTED"),
                0.85
        );
        String content = "Pod OOMKilled，Java heap space，内存从 200Mi 到 512Mi";

        AgentPlan plan = planner.plan(content, extract).value();

        assertThat(plan.actions()).contains(AgentAction.QUERY_LOGS);
        assertThat(plan.skipped()).contains(AgentAction.SIMILAR_CASE_SEARCH);
    }

    @Test
    void defaultPlanIncludesAllSteps() {
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统",
                "支付回调",
                "/pay/callback",
                "500",
                "HTTP 500",
                "生产",
                "多个用户",
                "上午10点",
                null,
                List.of("PRODUCTION"),
                0.9
        );

        AgentPlan plan = planner.plan("生产支付回调500", extract).value();

        assertThat(plan.actions()).contains(
                AgentAction.KNOWLEDGE_SEARCH,
                AgentAction.SIMILAR_CASE_SEARCH,
                AgentAction.QUERY_LOGS
        );
        assertThat(plan.skipped()).isEmpty();
    }
}
