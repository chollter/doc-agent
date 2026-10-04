package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 匹配度计算器单元测试
 */
class MatchScoreCalculatorTest {

    private final MatchScoreCalculator calculator = new MatchScoreCalculator();

    @Test
    void testCalculate_allRequirementsMet_shouldReturnHighScore() {
        // Given: 所有要求都满足，证据强度高
        List<RequirementVerdict> verdicts = List.of(
                mockVerdict("req1", "要求1", MustHaveCoverage.Status.MET, EvidenceLevel.L3_RESULT),
                mockVerdict("req2", "要求2", MustHaveCoverage.Status.MET, EvidenceLevel.L4_TRADE_OFF),
                mockVerdict("req3", "要求3", MustHaveCoverage.Status.MET, EvidenceLevel.L3_RESULT)
        );

        List<EvidenceAssessment> allEvidence = List.of(
                mockEvidence(EvidenceLevel.L3_RESULT),
                mockEvidence(EvidenceLevel.L4_TRADE_OFF),
                mockEvidence(EvidenceLevel.L3_RESULT)
        );

        List<RedFlag> redFlags = List.of();

        // When: 计算匹配度
        MatchScore score = calculator.calculate(verdicts, allEvidence, redFlags);

        // Then: 应该是高分（覆盖100% × 强度0.75-1.0）
        assertThat(score.overall()).isGreaterThanOrEqualTo(75);
        assertThat(score.breakdown().get("覆盖度")).isEqualTo(100);
    }

    @Test
    void testCalculate_partialCoverage_shouldReturnMediumScore() {
        // Given: 部分满足，部分缺失
        List<RequirementVerdict> verdicts = List.of(
                mockVerdict("req1", "要求1", MustHaveCoverage.Status.MET, EvidenceLevel.L2_METHOD),
                mockVerdict("req2", "要求2", MustHaveCoverage.Status.PARTIAL, EvidenceLevel.L1_ACTIVITY),
                mockVerdict("req3", "要求3", MustHaveCoverage.Status.MISSING, EvidenceLevel.L0_KEYWORD)
        );

        List<EvidenceAssessment> allEvidence = List.of(
                mockEvidence(EvidenceLevel.L2_METHOD),
                mockEvidence(EvidenceLevel.L1_ACTIVITY)
        );

        List<RedFlag> redFlags = List.of();

        // When: 计算匹配度
        MatchScore score = calculator.calculate(verdicts, allEvidence, redFlags);

        // Then: 应该是中等分数（覆盖50% = 1.0 + 0.5 + 0.0 / 3）
        assertThat(score.overall()).isBetween(15, 60);
        assertThat(score.breakdown().get("覆盖度")).isEqualTo(50);
    }

    @Test
    void testCalculate_withRedFlags_shouldApplyPenalty() {
        // Given: 满足所有要求，但有红旗
        List<RequirementVerdict> verdicts = List.of(
                mockVerdict("req1", "要求1", MustHaveCoverage.Status.MET, EvidenceLevel.L3_RESULT)
        );

        List<EvidenceAssessment> allEvidence = List.of(mockEvidence(EvidenceLevel.L3_RESULT));

        List<RedFlag> redFlags = List.of(
                new RedFlag(RedFlag.TIMELINE_GAP, RedFlag.Severity.HIGH, "1年以上空窗期无说明")
        );

        // When: 计算匹配度
        MatchScore score = calculator.calculate(verdicts, allEvidence, redFlags);

        // Then: 应该扣除10分
        assertThat(score.overall()).isLessThanOrEqualTo(65);  // 75 - 10
        assertThat(score.breakdown().get("风险扣分")).isEqualTo(-10);
    }

    @Test
    void testCalculate_lowEvidenceLevel_shouldReduceScore() {
        // Given: 所有要求满足，但证据强度低（只有L0/L1）
        List<RequirementVerdict> verdicts = List.of(
                mockVerdict("req1", "要求1", MustHaveCoverage.Status.MET, EvidenceLevel.L0_KEYWORD),
                mockVerdict("req2", "要求2", MustHaveCoverage.Status.MET, EvidenceLevel.L1_ACTIVITY)
        );

        List<EvidenceAssessment> allEvidence = List.of(
                mockEvidence(EvidenceLevel.L0_KEYWORD),
                mockEvidence(EvidenceLevel.L1_ACTIVITY)
        );

        List<RedFlag> redFlags = List.of();

        // When: 计算匹配度
        MatchScore score = calculator.calculate(verdicts, allEvidence, redFlags);

        // Then: 覆盖度100%，但证据强度系数低（0.125），最终分数应该在10-20之间
        assertThat(score.overall()).isBetween(10, 30);
        assertThat(score.breakdown().get("覆盖度")).isEqualTo(100);
        assertThat(score.breakdown().get("证据强度")).isLessThan(30);
    }

    @Test
    void testCalculate_emptyVerdicts_shouldReturnZeroScore() {
        // Given: 空要求列表
        List<RequirementVerdict> verdicts = List.of();
        List<EvidenceAssessment> allEvidence = List.of();
        List<RedFlag> redFlags = List.of();

        // When: 计算匹配度
        MatchScore score = calculator.calculate(verdicts, allEvidence, redFlags);

        // Then: 应该返回0分
        assertThat(score.overall()).isEqualTo(0);
    }

    // Helper methods

    private RequirementVerdict mockVerdict(String id, String requirement,
                                          MustHaveCoverage.Status status,
                                          EvidenceLevel level) {
        return new RequirementVerdict(
                id,
                requirement,
                "MUST",
                status,
                level,
                "测试声明",
                List.of("原文片段"),
                List.of("S1"),
                List.of(),
                "测试原因",
                null,
                true
        );
    }

    private EvidenceAssessment mockEvidence(EvidenceLevel level) {
        return new EvidenceAssessment(
                "section-1",
                "entity-1",
                "测试声明",
                level,
                List.of("原文片段"),
                List.of(),
                List.of(),
                List.of(),
                true
        );
    }
}
