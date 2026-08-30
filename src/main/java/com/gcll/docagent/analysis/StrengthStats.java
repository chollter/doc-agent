package com.gcll.docagent.analysis;

import java.util.List;

/**
 * 内容强度聚合——LLM 逐条判断之上的纯代码统计与分档。
 * <p>档位规则显式且可评测（P7 缺陷注入：删掉 Result 必须导致档位下降）：
 * STRONG 需同时满足高结果率、过半主导归因、四成以上项目/业务级结果；
 * WEAK 为结果率低于 30% 或主导归因低于 20%；其余为 MIXED。
 */
public record StrengthStats(
        int entryCount,
        double resultRate,
        double strongResultRate,
        double ownerRate,
        double starCompleteRate,
        StrengthBand band
) {

    public enum StrengthBand { WEAK, MIXED, STRONG }

    private static final double STRONG_RESULT_RATE = 0.7;
    private static final double STRONG_OWNER_RATE = 0.5;
    private static final double STRONG_STRONG_RESULT_SHARE = 0.4;
    /** 过半条目没有可验证结果即视为 WEAK——偏严，但这是初筛工具，宁可错杀。 */
    private static final double WEAK_RESULT_RATE = 0.5;
    private static final double WEAK_OWNER_RATE = 0.2;

    public static StrengthStats from(List<ExperienceStrength> entries) {
        if (entries == null || entries.isEmpty()) {
            return new StrengthStats(0, 0, 0, 0, 0, StrengthBand.WEAK);
        }
        int n = entries.size();
        long withResult = entries.stream().filter(ExperienceStrength::hasResult).count();
        long strongResult = entries.stream().filter(ExperienceStrength::isStrongResult).count();
        long ownerLevel = entries.stream().filter(ExperienceStrength::isOwnerLevel).count();
        long starComplete = entries.stream()
                .filter(e -> e.star() != null && e.star().values().stream().allMatch(Boolean::booleanValue))
                .count();

        double resultRate = (double) withResult / n;
        double strongRate = (double) strongResult / n;
        double ownerRate = (double) ownerLevel / n;

        return new StrengthStats(n, resultRate, strongRate, ownerRate,
                (double) starComplete / n, band(resultRate, ownerRate, strongRate));
    }

    private static StrengthBand band(double resultRate, double ownerRate, double strongRate) {
        if (resultRate >= STRONG_RESULT_RATE && ownerRate >= STRONG_OWNER_RATE
                && strongRate >= STRONG_STRONG_RESULT_SHARE) {
            return StrengthBand.STRONG;
        }
        if (resultRate < WEAK_RESULT_RATE || ownerRate < WEAK_OWNER_RATE) {
            return StrengthBand.WEAK;
        }
        return StrengthBand.MIXED;
    }
}
