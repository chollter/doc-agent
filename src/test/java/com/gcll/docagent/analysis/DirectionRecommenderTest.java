package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方向建议闸门:grounding 反编造 + tier 由证据等级代码判定。
 */
class DirectionRecommenderTest {

    private final DirectionRecommender recommender = new DirectionRecommender();

    private static final String FULL_TEXT =
            "主导检索增强知识库问答系统，日均调用12万次。参与订单中心开发。";

    private static EvidenceAssessment assessment(String quote, EvidenceLevel level, List<String> missing) {
        return new EvidenceAssessment("claim", "sec-x", quote, level,
                List.of(quote), missing, List.of(), List.of(), true);
    }

    @Test
    void dropsDirectionWhoseEvidenceIsNotGrounded() {
        var proposals = List.of(new DirectionRecommendation.Proposal(
                "数据平台", List.of("熟悉 Flink 实时数仓建设"), "sec-9"));
        List<DirectionRecommendation> out = recommender.recommend(proposals, FULL_TEXT, List.of());
        assertThat(out).isEmpty();
    }

    @Test
    void bestFitWhenCitedEvidenceReachesResultLevel() {
        var proposals = List.of(new DirectionRecommendation.Proposal(
                "AI应用开发", List.of("主导检索增强知识库问答系统"), "sec-2"));
        var assessments = List.of(assessment("主导检索增强知识库问答系统", EvidenceLevel.L3_RESULT, List.of()));

        List<DirectionRecommendation> out = recommender.recommend(proposals, FULL_TEXT, assessments);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).tier()).isEqualTo(DirectionRecommendation.Tier.BEST_FIT);
        assertThat(out.get(0).direction()).isEqualTo("AI应用开发");
    }

    @Test
    void stretchWhenEvidenceBelowResultLevel() {
        var proposals = List.of(new DirectionRecommendation.Proposal(
                "后端开发", List.of("参与订单中心开发"), "sec-5"));
        var assessments = List.of(assessment("参与订单中心开发", EvidenceLevel.L2_METHOD,
                List.of("可核验的规模、结果或指标")));

        List<DirectionRecommendation> out = recommender.recommend(proposals, FULL_TEXT, assessments);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).tier()).isEqualTo(DirectionRecommendation.Tier.STRETCH);
    }

    @Test
    void groundedButUnmatchedAssessmentFallsBackToStretch() {
        var proposals = List.of(new DirectionRecommendation.Proposal(
                "后端开发", List.of("参与订单中心开发"), "sec-5"));

        List<DirectionRecommendation> out = recommender.recommend(proposals, FULL_TEXT, List.of());

        assertThat(out).hasSize(1);
        assertThat(out.get(0).tier()).isEqualTo(DirectionRecommendation.Tier.STRETCH);
    }

    @Test
    void dedupsSameDirectionKeepingBestFitAndSortsBestFitFirst() {
        var proposals = List.of(
                new DirectionRecommendation.Proposal("AI应用开发", List.of("参与订单中心开发"), "sec-5"),
                new DirectionRecommendation.Proposal("AI应用开发", List.of("主导检索增强知识库问答系统"), "sec-2"),
                new DirectionRecommendation.Proposal("后端开发", List.of("参与订单中心开发"), "sec-5"));
        var assessments = List.of(
                assessment("主导检索增强知识库问答系统", EvidenceLevel.L3_RESULT, List.of()),
                assessment("参与订单中心开发", EvidenceLevel.L2_METHOD, List.of("结果指标")));

        List<DirectionRecommendation> out = recommender.recommend(proposals, FULL_TEXT, assessments);

        assertThat(out).hasSize(2);
        // BEST_FIT 排在最前
        assertThat(out.get(0).direction()).isEqualTo("AI应用开发");
        assertThat(out.get(0).tier()).isEqualTo(DirectionRecommendation.Tier.BEST_FIT);
        assertThat(out.get(1).tier()).isEqualTo(DirectionRecommendation.Tier.STRETCH);
    }
}
