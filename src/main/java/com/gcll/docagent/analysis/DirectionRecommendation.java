package com.gcll.docagent.analysis;

import java.util.List;

/**
 * 无 JD 时的方向建议——锚定简历自身证据,不依赖任何外部岗位基准。
 * <p>方向名由 LLM 从简历语义推断,但 tier(稳妥/跳一跳)由代码按被引证据的
 * {@link EvidenceLevel} 判定,evidence 必须回锚简历原文,否则整条丢弃。
 * LLM 只负责"提名",可靠性判断全在代码侧。
 */
public record DirectionRecommendation(
        String direction,
        Tier tier,
        List<String> evidence,
        String sectionId
) {
    /** BEST_FIT=现有证据已支撑;STRETCH=差一块真实证据即可投。 */
    public enum Tier { BEST_FIT, STRETCH }

    public DirectionRecommendation {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** LLM 提议的原始方向(未经 grounding 校验与 tier 判定)。 */
    public record Proposal(String direction, List<String> evidence, String sectionId) {
        public Proposal {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }
}
