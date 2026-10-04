package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 漏斗式结论——P12 的主结果模型，替代 P11 的 QualityScore 加权总分。
 * <p>为什么没有 overall：加权总分制造假精确（LLM 评分波动 ±10 时总分无意义），
 * 且把"一票否决的红旗"和"可改进的表达"压成一个数，丢失优先级。
 * 五个角度各自出档位，红旗层独立于评分；legacyOverall 仅用于 DB 列表排序兼容。
 * <p>analysisDegraded=true（实体抽取降级）：红旗层缺失故不出总分（legacyOverall=0），
 * 但 LLM 五角度输出基于直读原文，保留供参考；前端必须显式提示降级状态。
 */
public record FunnelVerdict(
        List<RedFlag> redFlags,
        String matchMode,
        String archetypeId,
        List<RequirementVerdict> requirementVerdicts,
        PositioningCheck positioning,
        List<ExperienceStrength> experienceStrength,
        StrengthStats strength,
        Presentation presentation,
        List<LeverageCard> leverageCards,
        boolean analysisDegraded,
        List<GroundingValidator.Finding> groundingFindings,
        Evaluation evaluation,
        List<EvidenceAssessment> evidenceAssessments,
        List<DirectionRecommendation> recommendedDirections
) {

    /** 匹配模式：JD 对照 / 方向画像（广撒网）/ 未指定。 */
    public static final String MODE_JD = "JD";
    public static final String MODE_DIRECTION = "DIRECTION";
    public static final String MODE_NONE = "NONE";

    public enum MatchBand { NONE, WEAK, PARTIAL, STRONG }

    public boolean hasHighRedFlag() {
        return redFlags != null && redFlags.stream()
                .anyMatch(f -> f.severity() == RedFlag.Severity.HIGH);
    }

    /**
     * 旧前端和历史评测使用的兼容字段。它不是第二份判断，只是 RequirementVerdict 的投影。
     */
    @JsonProperty(value = "mustHaveCoverage", access = JsonProperty.Access.READ_ONLY)
    public List<MustHaveCoverage> mustHaveCoverage() {
        return requirementVerdicts == null ? List.of()
                : requirementVerdicts.stream().map(RequirementVerdict::toCoverage).toList();
    }

    /** 匹配档位：共性要求 MET 率 ≥75% STRONG / ≥50% PARTIAL / >0 WEAK / 无数据 NONE。 */
    public MatchBand matchBand() {
        if (requirementVerdicts == null || requirementVerdicts.isEmpty()) {
            return MatchBand.NONE;
        }
        double metRate = (double) requirementVerdicts.stream()
                .filter(c -> c.status() == MustHaveCoverage.Status.MET).count()
                / requirementVerdicts.size();
        if (metRate >= 0.75) return MatchBand.STRONG;
        if (metRate >= 0.5) return MatchBand.PARTIAL;
        if (metRate > 0) return MatchBand.WEAK;
        return MatchBand.NONE;
    }

    /**
     * 兼容映射：档位 → 0-100 分，仅供 DB score_overall 列表排序与旧前端。
     * 强度 40/65/85 × 0.4 + 表达分 × 0.3 + 匹配档位 30/50/70/80 × 0.3，
     * 红旗 HIGH 每条 -10、MEDIUM 每条 -3，下限 0。抽取降级时返回 0。
     */
    public int legacyOverall() {
        if (analysisDegraded) {
            return 0;
        }
        int strengthVal = switch (strength != null ? strength.band() : StrengthStats.StrengthBand.WEAK) {
            case STRONG -> 85;
            case MIXED -> 65;
            case WEAK -> 40;
        };
        int presentationVal = presentation != null ? presentation.score() : 50;
        int matchVal = switch (matchBand()) {
            case STRONG -> 80;
            case PARTIAL -> 70;
            case WEAK -> 50;
            case NONE -> 30;
        };
        int score = (int) (strengthVal * 0.4 + presentationVal * 0.3 + matchVal * 0.3);
        if (redFlags != null) {
            for (RedFlag f : redFlags) {
                score -= f.severity() == RedFlag.Severity.HIGH ? 10 : 3;
            }
        }
        return Math.max(0, score);
    }

    public static FunnelVerdict degraded(List<RedFlag> redFlags) {
        return new FunnelVerdict(redFlags != null ? redFlags : List.of(),
                MODE_NONE, null, List.of(), null,
                List.of(), StrengthStats.from(List.of()), null, List.of(), true, List.of(), null, List.of(),
                List.of());
    }

    /**
     * 定性评价（v6）——补"只有描述没有判断"的缺口。
     * <p>overall/strengths/weaknesses 是裁决之后的综合表达；岗位匹配必须服从
     * requirementVerdicts，dimensions 只给定性评语。各维度档位仍由代码侧计算
     *（内容强度/表达分/匹配率），避免 LLM 重评与代码结论打架。
     * 历史 run（v5 及以前）无此字段，前端需容忍 null。
     * <p>S3 增量评估血缘：carriedDimensions 记录"本次未重写、沿自基线版"的维度名，
     * 使每条评语可审计"是谁写的"。全量评估与历史 run 该字段为空。
     */
    public record Evaluation(
            String overall,
            List<DimensionComment> dimensions,
            List<String> strengths,
            List<String> weaknesses,
            List<String> carriedDimensions
    ) {
        public Evaluation {
            carriedDimensions = carriedDimensions == null ? List.of() : List.copyOf(carriedDimensions);
        }

        /** 全量评估构造便捷口：无沿用血缘。 */
        public Evaluation(String overall, List<DimensionComment> dimensions,
                          List<String> strengths, List<String> weaknesses) {
            this(overall, dimensions, strengths, weaknesses, List.of());
        }

        /**
         * 单一质量维度评估。comment 必须解释判断，evidence 必须回指简历原文；
         * issueType 用于区分能力缺失、证据不足、表达问题和与目标无关，避免把“没写”判成“不会”。
         */
        public record DimensionComment(
                String dimension,
                String level,
                String comment,
                List<String> evidence,
                String issueType
        ) {
            public DimensionComment {
                evidence = evidence == null ? List.of() : List.copyOf(evidence);
            }
        }
    }
}
