package com.gcll.ticketagent.governance.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutingPolicyEngine 单测：验证智能路由的三种置信度分流。
 *
 * <p>三种分流：
 * <ol>
 *   <li>规则高置信（>=0.7）→ 规则为主，LLM 补充</li>
 *   <li>规则低置信 + LLM 高置信 → LLM override 规则</li>
 *   <li>规则低置信 + LLM 低置信 → 人工兜底（保留规则默认）</li>
 * </ol>
 */
class RoutingPolicyEngineTest {

    private final RoutingPolicyEngine engine = new RoutingPolicyEngine();

    /** 场景1：规则明确命中（置信度 0.92），LLM 认同且更确信（0.95）→ 规则为主，置信度上调。 */
    @Test
    void ruleHighConfidenceLlmAgrees_confidenceBoosted() {
        RoutingResult rule = new RoutingResult("支付研发组", List.of("订单研发组"), "规则匹配支付", 0.92);
        RoutingSuggestion llm = new RoutingSuggestion("支付研发组", List.of("DBA"), "LLM认同", 0.95, true);

        RoutingResult result = engine.merge(rule, llm);

        assertThat(result.primaryTeam()).isEqualTo("支付研发组");
        assertThat(result.confidence()).isGreaterThan(0.92);
        assertThat(result.backupTeams()).contains("订单研发组", "DBA");
    }

    /** 场景2：规则明确命中，LLM 建议不同团队 → 规则优先，LLM 建议记入 reason。 */
    @Test
    void ruleHighConfidenceLlmDisagrees_ruleWins() {
        RoutingResult rule = new RoutingResult("支付研发组", List.of(), "规则匹配支付", 0.92);
        RoutingSuggestion llm = new RoutingSuggestion("订单研发组", List.of(), "LLM觉得是订单", 0.6, true);

        RoutingResult result = engine.merge(rule, llm);

        assertThat(result.primaryTeam()).isEqualTo("支付研发组");
        assertThat(result.routingReason()).contains("订单研发组");
    }

    /** 场景3：规则未命中（置信度 0.4），LLM 高置信（0.85）→ LLM override 规则。 */
    @Test
    void ruleLowConfidenceLlmHighConfidence_llmOverrides() {
        RoutingResult rule = new RoutingResult("平台运维组", List.of(), "默认兜底", 0.4);
        RoutingSuggestion llm = new RoutingSuggestion("结算研发组", List.of("DBA"), "描述涉及结算资金", 0.85, true);

        RoutingResult result = engine.merge(rule, llm);

        assertThat(result.primaryTeam()).isEqualTo("结算研发组");
        assertThat(result.confidence()).isEqualTo(0.85);
        assertThat(result.routingReason()).contains("LLM 智能决策");
        assertThat(result.backupTeams()).contains("平台运维组");
    }

    /** 场景4：规则未命中，LLM 中等置信（0.55）→ LLM 决策但标记低置信（触发人工确认）。 */
    @Test
    void ruleLowLlmMedium_llmDecisionButLowConfidence() {
        RoutingResult rule = new RoutingResult("平台运维组", List.of(), "默认兜底", 0.4);
        RoutingSuggestion llm = new RoutingSuggestion("账号平台组", List.of(), "可能账号问题", 0.55, true);

        RoutingResult result = engine.merge(rule, llm);

        assertThat(result.primaryTeam()).isEqualTo("账号平台组");
        assertThat(result.confidence()).isEqualTo(0.55);
        assertThat(result.routingReason()).contains("低置信");
    }

    /** 场景5：规则低置信 + LLM 也低置信（<0.4）→ 人工兜底，保留规则默认。 */
    @Test
    void bothLowConfidence_fallbackToRuleDefault() {
        RoutingResult rule = new RoutingResult("平台运维组", List.of(), "默认兜底", 0.4);
        RoutingSuggestion llm = new RoutingSuggestion("平台运维组", List.of(), "不确定", 0.3, true);

        RoutingResult result = engine.merge(rule, llm);

        assertThat(result.primaryTeam()).isEqualTo("平台运维组");
        assertThat(result.routingReason()).contains("人工确认");
    }

    /** 场景6：LLM 无建议 → 直接用规则结果。 */
    @Test
    void noLlmSuggestion_useRuleDirectly() {
        RoutingResult rule = new RoutingResult("支付研发组", List.of("订单研发组"), "规则匹配", 0.92);

        RoutingResult result = engine.merge(rule, RoutingSuggestion.empty());

        assertThat(result.primaryTeam()).isEqualTo("支付研发组");
        assertThat(result.confidence()).isEqualTo(0.92);
    }
}
