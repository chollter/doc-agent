package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceResultQualificationTest {

    @Test
    void onlyQuantifiedImpactCanUpgradeAssessment() {
        EvidenceAssessment base = new EvidenceAssessment("支付平台", "sec-2", "负责支付平台",
                EvidenceLevel.L1_ACTIVITY, List.of("负责支付平台"),
                List.of("可核验的规模、约束、结果或指标（只能补充真实数据）"),
                List.of("结果是什么？"), List.of("补真实口径"), true);

        EvidenceAssessment upgraded = base.withExploredResult("sec-8", "故障率从 2% 降低到 0.5%");
        EvidenceAssessment rejected = base.withExploredResult("sec-8", "参与 2023 年项目开发");

        assertThat(upgraded.evidenceSource()).isEqualTo("REACT");
        assertThat(upgraded.sectionId()).isEqualTo("sec-8");
        assertThat(upgraded.evidenceLevel()).isEqualTo(EvidenceLevel.L3_RESULT);
        assertThat(upgraded.missingFacts()).isEmpty();
        assertThat(rejected).isEqualTo(base);
    }

    @Test
    void doesNotTreatBareNumbersOrDatesAsResults() {
        assertThat(EvidenceResultQualification.isQuantifiedResult("2023 年加入项目")).isFalse();
        assertThat(EvidenceResultQualification.isQuantifiedResult("参与 3 次评审")).isFalse();
        assertThat(EvidenceResultQualification.isQuantifiedResult("故障率从 2% 降低到 0.5%")).isTrue();
    }
}
