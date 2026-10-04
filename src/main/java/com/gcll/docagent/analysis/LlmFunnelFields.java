package com.gcll.docagent.analysis;

import java.util.List;

/**
 * P12 LLM 五角度输出——只进 FunnelVerdict，不进 AnalysisResult。评价（v7）走专调，不在此列。
 * <p>由 {@link FunnelFieldsMapper} 从 LLM 原始 JSON 映射得到，供结果装配阶段消费。
 */
public record LlmFunnelFields(
        Presentation presentation,
        List<ExperienceStrength> experienceStrength,
        List<LeverageCard> leverageCards,
        List<MustHaveCoverage> mustHaveCoverage,
        PositioningCheck positioning,
        List<DirectionRecommendation.Proposal> directionProposals
) {
    public static LlmFunnelFields empty() {
        return new LlmFunnelFields(null, List.of(), List.of(), List.of(), null, List.of());
    }
}
