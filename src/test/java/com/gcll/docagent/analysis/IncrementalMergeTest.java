package com.gcll.docagent.analysis;

import com.gcll.docagent.analysis.FunnelVerdict.Evaluation;
import com.gcll.docagent.analysis.FunnelVerdict.Evaluation.DimensionComment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S3 增量合并单测——"只重评变化区域"的代码侧收口：
 * LLM 输出=重写（含新维度按 NEW 接受）；基线有而输出没有的维度沿用原文并记入 carriedDimensions。
 */
class IncrementalMergeTest {

    private static DimensionComment dim(String name, String level, String comment) {
        return new DimensionComment(name, level, comment, List.of(), "NONE");
    }

    @Test
    void rewritesReplaceBaseDimsAndOmittedDimsAreCarried() {
        Evaluation base = new Evaluation("基线总评",
                List.of(dim("RESULT_IMPACT", "MEDIUM", "缺结果指标"),
                        dim("TECHNICAL_DEPTH", "WEAK", "机制未展开")),
                List.of("基线强项"), List.of());
        Evaluation inc = new Evaluation("增量总评",
                List.of(dim("RESULT_IMPACT", "MEDIUM", "增量：结果指标已落地")),
                List.of(), List.of());

        Evaluation merged = ResultAssembler.mergeIncremental(base, inc);

        assertThat(merged.overall()).isEqualTo("增量总评");
        // 维度序保持基线序，被重写的原位替换——报告读起来不会因增量而乱序
        assertThat(merged.dimensions()).hasSize(2);
        assertThat(merged.dimensions().get(0).comment()).isEqualTo("增量：结果指标已落地");
        assertThat(merged.dimensions().get(1).comment()).isEqualTo("机制未展开");
        assertThat(merged.carriedDimensions()).containsExactly("TECHNICAL_DEPTH");
        // inc 强弱项为空数组=无变化依据 → 沿用基线
        assertThat(merged.strengths()).containsExactly("基线强项");
    }

    @Test
    void extraDimensionsFromLlmAreAcceptedNotDropped() {
        Evaluation base = new Evaluation("总评", List.of(dim("A", "MEDIUM", "旧A")), List.of(), List.of());
        Evaluation inc = new Evaluation("新总评",
                List.of(dim("A", "STRONG", "新A"), dim("C", "WEAK", "新增维度")), List.of(), List.of());

        Evaluation merged = ResultAssembler.mergeIncremental(base, inc);

        assertThat(merged.dimensions()).hasSize(2);
        assertThat(merged.dimensions().get(1).dimension()).isEqualTo("C");
        assertThat(merged.carriedDimensions()).isEmpty();
    }

    @Test
    void blankOverallFallsBackToBase() {
        Evaluation base = new Evaluation("基线总评", List.of(dim("A", "MEDIUM", "旧A")), List.of(), List.of());
        Evaluation inc = new Evaluation("  ", List.of(), List.of(), List.of());

        Evaluation merged = ResultAssembler.mergeIncremental(base, inc);

        assertThat(merged.overall()).isEqualTo("基线总评");
        assertThat(merged.carriedDimensions()).containsExactly("A");
    }

    @Test
    void nullIncrementalYieldsNullMergeSoCallerCanFallBackToFull() {
        Evaluation base = new Evaluation("总评", List.of(dim("A", "MEDIUM", "旧A")), List.of(), List.of());

        assertThat(ResultAssembler.mergeIncremental(base, null)).isNull();
    }
}
