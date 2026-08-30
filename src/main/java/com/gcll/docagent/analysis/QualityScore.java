package com.gcll.docagent.analysis;

import java.util.Map;

/**
 * 简历质量评分——5 维混合评分。
 * <p>quantification 和 completeness 由代码确定性计算；
 * clarity / credibility / professionalism 由 LLM 评估输出。
 * overall 为加权综合分。
 *
 * @param overall    综合分 0-100
 * @param dimensions 各维度分数，key 为维度名，value 为 0-100 分
 */
public record QualityScore(
        int overall,
        Map<String, Integer> dimensions
) {

    /** 维度常量——与 dimensions map 的 key 对应。 */
    public static final String DIM_QUANTIFICATION = "quantification";
    public static final String DIM_COMPLETENESS = "completeness";
    public static final String DIM_CLARITY = "clarity";
    public static final String DIM_CREDIBILITY = "credibility";
    public static final String DIM_PROFESSIONALISM = "professionalism";

    /**
     * 从 5 维分数计算加权综合分。
     * 权重：quantification 25%, completeness 25%, clarity 20%, credibility 15%, professionalism 15%
     */
    public static int computeOverall(Map<String, Integer> dims) {
        int q = dims.getOrDefault(DIM_QUANTIFICATION, 0);
        int c = dims.getOrDefault(DIM_COMPLETENESS, 0);
        int cl = dims.getOrDefault(DIM_CLARITY, 0);
        int cr = dims.getOrDefault(DIM_CREDIBILITY, 0);
        int p = dims.getOrDefault(DIM_PROFESSIONALISM, 0);
        return (int) (q * 0.25 + c * 0.25 + cl * 0.20 + cr * 0.15 + p * 0.15);
    }
}
