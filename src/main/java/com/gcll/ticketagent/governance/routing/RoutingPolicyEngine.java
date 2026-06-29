package com.gcll.ticketagent.governance.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 路由策略引擎：按置信度分流，规则为主、LLM 兜底。
 *
 * <h3>智能路由策略（置信度分流）</h3>
 * <pre>
 * 规则路由结果
 *   ├─ 规则置信度 >= RULE_CONFIDENT_THRESHOLD（0.7）
 *   │    → 规则明确命中，直接用规则（快路径，~90% 工单在这解决）
 *   │    → LLM 建议作为 reason 补充（不 override primaryTeam）
 *   └─ 规则置信度 < 0.7（规则未命中/模糊/默认兜底）
 *        → LLM 真正决策（慢路径，~10% 模糊工单）
 *        ├─ LLM 置信度 >= 0.7 → 用 LLM 的 primaryTeam（LLM override 规则）
 *        ├─ LLM 置信度 0.4-0.7 → 用 LLM 的但标记低置信（触发人工确认）
 *        └─ LLM 置信度 < 0.4 → 用规则的兜底（人工兜底）
 * </pre>
 *
 * <h3>设计依据</h3>
 * <p>路由是确定性决策——"支付系统归支付组"该用规则，不该靠 LLM。
 * 但规则覆盖不了模糊描述（如"结不了账"匹配不到"结算"关键词），
 * 此时让 LLM 基于语义判断。两层互补：规则保底已知场景，LLM 处理长尾。
 *
 * <p><b>关键变化（vs 原实现）</b>：原实现 LLM 永远不能 override 规则（只是建议）。
 * 现在规则置信度低时 LLM 能真正决策——这才是"智能路由"。
 */
@Component
public class RoutingPolicyEngine {

    private static final Logger log = LoggerFactory.getLogger(RoutingPolicyEngine.class);

    /** 规则置信度阈值：>= 此值视为规则明确命中，直接用规则。 */
    private static final double RULE_CONFIDENT_THRESHOLD = 0.7;
    /** LLM 决策置信度阈值：>= 此值视为 LLM 有把握，允许 override 规则。 */
    private static final double LLM_CONFIDENT_THRESHOLD = 0.7;
    /** LLM 人工兜底阈值：< 此值视为 LLM 也没把握，走人工兜底（保留规则默认）。 */
    private static final double LLM_FALLBACK_THRESHOLD = 0.4;

    /**
     * 合并规则路由与 LLM 建议，按置信度分流出最终路由结果。
     */
    public RoutingResult merge(RoutingResult ruleResult, RoutingSuggestion suggestion) {
        if (ruleResult == null) {
            throw new IllegalArgumentException("ruleResult must not be null");
        }
        if (suggestion == null || !suggestion.hasSuggestion()) {
            return ruleResult;
        }

        // 分流1：规则明确命中（高置信度）→ 规则为主，LLM 只补充 reason
        if (ruleResult.confidence() >= RULE_CONFIDENT_THRESHOLD) {
            return mergeWithRulePrimary(ruleResult, suggestion);
        }

        // 分流2：规则未命中/模糊（低置信度）→ LLM 可真正决策
        return mergeWithLlmDecision(ruleResult, suggestion);
    }

    /**
     * 规则为主：primaryTeam 用规则的，LLM 的 reason 补充进去。
     * 如果 LLM 也认同同一个团队，置信度可上调（双重确认）。
     */
    private RoutingResult mergeWithRulePrimary(RoutingResult ruleResult, RoutingSuggestion suggestion) {
        List<String> backupTeams = mergeBackupTeams(ruleResult, suggestion);
        String reason = buildReasonForRulePrimary(ruleResult, suggestion);
        double confidence = ruleResult.confidence();

        // LLM 认同规则的主责团队 → 置信度上调（双方一致更可信）
        if (suggestion.suggestedPrimaryTeam().equals(ruleResult.primaryTeam())
                && suggestion.confidence() > ruleResult.confidence()) {
            confidence = Math.min(1.0, (ruleResult.confidence() + suggestion.confidence()) / 2.0);
            log.info("Routing: rule+LLM agree on [{}], confidence {}->{}",
                    ruleResult.primaryTeam(), ruleResult.confidence(), confidence);
        }
        return new RoutingResult(ruleResult.primaryTeam(), backupTeams, reason, confidence);
    }

    /**
     * LLM 决策：规则置信度低时，让 LLM 真正决定 primaryTeam。
     */
    private RoutingResult mergeWithLlmDecision(RoutingResult ruleResult, RoutingSuggestion suggestion) {
        double llmConfidence = suggestion.confidence();

        // LLM 也没把握（置信度低）→ 人工兜底，保留规则默认
        if (llmConfidence < LLM_FALLBACK_THRESHOLD) {
            log.info("Routing: rule low({}) + LLM low({}), fallback to rule default [{}], require human",
                    ruleResult.confidence(), llmConfidence, ruleResult.primaryTeam());
            return new RoutingResult(
                    ruleResult.primaryTeam(),
                    ruleResult.backupTeams(),
                    ruleResult.routingReason() + "（规则与 LLM 均低置信，建议人工确认归属）",
                    Math.max(ruleResult.confidence(), llmConfidence)
            );
        }

        // LLM 有把握 → 用 LLM 的 primaryTeam（override 规则）
        List<String> backupTeams = mergeBackupTeams(ruleResult, suggestion);
        // 若 LLM 主责与规则默认不同，把规则默认团队加入 backupTeams（不丢失规则信息）
        if (!suggestion.suggestedPrimaryTeam().equals(ruleResult.primaryTeam())
                && ruleResult.primaryTeam() != null && !ruleResult.primaryTeam().isBlank()) {
            backupTeams = appendIfAbsent(backupTeams, ruleResult.primaryTeam());
        }

        String decision = llmConfidence >= LLM_CONFIDENT_THRESHOLD ? "LLM 智能决策" : "LLM 低置信决策（建议人工确认）";
        String reason = decision + "：" + (suggestion.reason() == null ? "无" : suggestion.reason())
                + "（规则未明确命中，默认 " + ruleResult.primaryTeam() + "，LLM 裁决为 " + suggestion.suggestedPrimaryTeam() + "）";

        log.info("Routing: rule low({}) → LLM decision [{}] confidence={}, override rule default [{}]",
                ruleResult.confidence(), suggestion.suggestedPrimaryTeam(), llmConfidence, ruleResult.primaryTeam());

        return new RoutingResult(
                suggestion.suggestedPrimaryTeam(),
                backupTeams,
                reason,
                llmConfidence
        );
    }

    private List<String> mergeBackupTeams(RoutingResult ruleResult, RoutingSuggestion suggestion) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (ruleResult.backupTeams() != null) {
            merged.addAll(ruleResult.backupTeams());
        }
        if (suggestion.suggestedBackupTeams() != null) {
            for (String team : suggestion.suggestedBackupTeams()) {
                if (team != null && !team.isBlank() && !team.equals(ruleResult.primaryTeam())) {
                    merged.add(team);
                }
            }
        }
        return List.copyOf(merged);
    }

    private String buildReasonForRulePrimary(RoutingResult ruleResult, RoutingSuggestion suggestion) {
        String baseReason = ruleResult.routingReason();
        if (suggestion.suggestedPrimaryTeam().equals(ruleResult.primaryTeam())) {
            if (suggestion.reason() != null && !suggestion.reason().isBlank()) {
                return baseReason + "；LLM 认同：" + suggestion.reason();
            }
            return baseReason;
        }
        return baseReason + "（LLM 建议 " + suggestion.suggestedPrimaryTeam()
                + "，规则裁决为 " + ruleResult.primaryTeam() + "）";
    }

    private List<String> appendIfAbsent(List<String> teams, String team) {
        if (teams == null || teams.isEmpty()) {
            return List.of(team);
        }
        LinkedHashSet<String> set = new LinkedHashSet<>(teams);
        set.add(team);
        return List.copyOf(set);
    }
}
