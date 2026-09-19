package com.gcll.docagent.analysis;

/**
 * LLM判断的覆盖结果：状态+覆盖度+差距描述。
 */
public record CoverageJudgment(
        MustHaveCoverage.Status status,
        double coverage,
        String gap
) {
    public static CoverageJudgment missing(String gap) {
        return new CoverageJudgment(MustHaveCoverage.Status.MISSING, 0.0, gap);
    }

    public static CoverageJudgment partial(double coverage, String gap) {
        return new CoverageJudgment(MustHaveCoverage.Status.PARTIAL, coverage, gap);
    }

    public static CoverageJudgment full(double coverage) {
        return new CoverageJudgment(MustHaveCoverage.Status.MET, coverage, "");
    }
}
