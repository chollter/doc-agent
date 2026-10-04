package com.gcll.docagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 稳定性度量数学钉死——合成观测零 LLM 跑：一致率/众数/极差/标签规则全部在此锁定，
 * 真实跑批只喂数据进这套纯函数，测量本身不需要"再信 LLM 一次"。
 */
class StabilityMeasurerTest {

    private static StabilityMeasurer.RunObservation obs(String band, Integer score,
                                                        Map<String, String> dims,
                                                        List<String> flags, String anchor) {
        return new StabilityMeasurer.RunObservation("r", "LLM", false, band, score, dims, flags, anchor);
    }

    private static StabilityMeasurer.FieldConsistency field(StabilityMeasurer.StabilityReport report,
                                                             String name) {
        return report.fields().stream()
                .filter(f -> f.field().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少字段: " + name));
    }

    @Test
    void identicalRunsShouldAllBeStable() {
        var a = obs("STRONG", 70, Map.of("communication", "STRONG", "depth", "MIXED"),
                List.of("gap::high::两年空窗"), "AI应用开发");
        var b = obs("STRONG", 70, Map.of("communication", "STRONG", "depth", "MIXED"),
                List.of("gap::high::两年空窗"), "AI应用开发");
        var c = obs("STRONG", 70, Map.of("communication", "STRONG", "depth", "MIXED"),
                List.of("gap::high::两年空窗"), "AI应用开发");

        StabilityMeasurer.StabilityReport report =
                StabilityMeasurer.measure("demo", "samples/a.md", List.of(a, b, c), 1.0);

        assertThat(report.fields()).extracting(StabilityMeasurer.FieldConsistency::status)
                .containsOnly("STABLE");
        assertThat(report.fields()).extracting(StabilityMeasurer.FieldConsistency::agreeRate)
                .containsOnly(1.0);
        assertThat(report.runs()).isEqualTo(3);
    }

    @Test
    void fieldLayersShouldSplitDeterministicAndLlm() {
        var a = obs("STRONG", 70, Map.of(), List.of(), "锚");

        StabilityMeasurer.StabilityReport report =
                StabilityMeasurer.measure("demo", "samples/a.md", List.of(a, a), 1.0);

        assertThat(field(report, "redFlags").layer()).isEqualTo(StabilityMeasurer.LAYER_DETERMINISTIC);
        for (String f : List.of("strengthBand", "presentationScore", "positioningAnchor")) {
            assertThat(field(report, f).layer()).isEqualTo(StabilityMeasurer.LAYER_LLM);
        }
    }

    @Test
    void partialDriftShouldKeepPerRunDetailAndLabelDrift() {
        StabilityMeasurer.StabilityReport report = StabilityMeasurer.measure("demo", "samples/a.md", List.of(
                obs("STRONG", 70, Map.of(), List.of(), "锚"),
                obs("STRONG", 70, Map.of(), List.of(), "锚"),
                obs("MIXED", 70, Map.of(), List.of(), "锚")), 1.0);

        StabilityMeasurer.FieldConsistency band = field(report, "strengthBand");
        assertThat(band.perRun()).containsExactly("STRONG", "STRONG", "MIXED");
        assertThat(band.majority()).isEqualTo("STRONG");
        assertThat(band.majorityCount()).isEqualTo(2);
        assertThat(band.agreeRate()).isEqualTo(0.6667);
        assertThat(band.status()).isEqualTo("DRIFT");
    }

    @Test
    void thresholdBelowRateShouldFlipLabelWithoutTouchingNumbers() {
        List<StabilityMeasurer.RunObservation> obs = List.of(
                obs("STRONG", 70, Map.of(), List.of(), "锚"),
                obs("STRONG", 70, Map.of(), List.of(), "锚"),
                obs("MIXED", 70, Map.of(), List.of(), "锚"));

        StabilityMeasurer.FieldConsistency atSix = field(StabilityMeasurer.measure("d", "f", obs, 0.6), "strengthBand");
        assertThat(atSix.status()).isEqualTo("STABLE");
        assertThat(atSix.agreeRate()).isEqualTo(0.6667); // 原始数字不因标签而变
    }

    @Test
    void presentationScoreShouldReportRangeWithMajority() {
        StabilityMeasurer.StabilityReport report = StabilityMeasurer.measure("demo", "samples/a.md", List.of(
                obs("MIXED", 70, Map.of(), List.of(), "锚"),
                obs("MIXED", 75, Map.of(), List.of(), "锚"),
                obs("MIXED", 70, Map.of(), List.of(), "锚")), 1.0);

        StabilityMeasurer.FieldConsistency score = field(report, "presentationScore");
        assertThat(score.perRun()).containsExactly("70", "75", "70");
        assertThat(score.majority()).isEqualTo("70");
        assertThat(score.agreeRate()).isEqualTo(0.6667);
        assertThat(score.scoreRange()).isEqualTo(5);
    }

    @Test
    void presentationScoreRangeNeedsTwoPresentSamples() {
        StabilityMeasurer.StabilityReport report = StabilityMeasurer.measure("demo", "samples/a.md", List.of(
                obs("MIXED", 70, Map.of(), List.of(), "锚"),
                obs("MIXED", null, Map.of(), List.of(), "锚"),
                obs("MIXED", null, Map.of(), List.of(), "锚")), 1.0);

        StabilityMeasurer.FieldConsistency score = field(report, "presentationScore");
        assertThat(score.perRun()).containsExactly("70", "NULL", "NULL");
        assertThat(score.scoreRange()).isNull();
        assertThat(score.majority()).isEqualTo("NULL");
        assertThat(score.agreeRate()).isEqualTo(0.6667);
    }

    @Test
    void missingDimensionShouldCountAsAbsent() {
        StabilityMeasurer.StabilityReport report = StabilityMeasurer.measure("demo", "samples/a.md", List.of(
                obs("MIXED", 70, Map.of("depth", "STRONG"), List.of(), "锚"),
                obs("MIXED", 70, Map.of(), List.of(), "锚")), 1.0);

        StabilityMeasurer.FieldConsistency depth = field(report, "dimension.depth");
        assertThat(depth.perRun()).containsExactly("STRONG", "ABSENT");
        assertThat(depth.agreeRate()).isEqualTo(0.5);
        assertThat(depth.status()).isEqualTo("DRIFT");
    }

    @Test
    void redFlagsShouldUseSortedSetKeysJoinedWithPipe() {
        StabilityMeasurer.StabilityReport report = StabilityMeasurer.measure("demo", "samples/a.md", List.of(
                obs("MIXED", 70, Map.of(), List.of("b::low::m2", "a::high::m1"), "锚"),
                obs("MIXED", 70, Map.of(), List.of("b::low::m2", "a::high::m1"), "锚")), 1.0);

        StabilityMeasurer.FieldConsistency flags = field(report, "redFlags");
        assertThat(flags.perRun()).containsExactly("a::high::m1 | b::low::m2", "a::high::m1 | b::low::m2");
        assertThat(flags.status()).isEqualTo("STABLE");
    }

    @Test
    void singleRunShouldNotClaimStability() {
        StabilityMeasurer.StabilityReport report = StabilityMeasurer.measure("demo", "samples/a.md",
                List.of(obs("STRONG", 70, Map.of(), List.of(), "锚")), 1.0);

        assertThat(report.fields()).allSatisfy(f -> {
            assertThat(f.status()).isNull();
            assertThat(f.agreeRate()).isEqualTo(1.0);
        });
    }

    @Test
    void emptyObservationsShouldFailLoud() {
        assertThatThrownBy(() -> StabilityMeasurer.measure("demo", "samples/a.md", List.of(), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullResultShouldYieldAllNullDegradedObservation() {
        StabilityMeasurer.RunObservation o = StabilityMeasurer.observe("r-fail", "FAILED", null);

        assertThat(o.degraded()).isTrue();
        assertThat(o.strengthBand()).isNull();
        assertThat(o.presentationScore()).isNull();
        assertThat(o.dimensionLevels()).isEmpty();
        assertThat(o.redFlagKeys()).isEmpty();
        assertThat(o.positioningAnchor()).isNull();
    }

    @Test
    void observeShouldExtractAndSortFromResultJson() throws Exception {
        String json = """
                {
                  "summary": "s",
                  "funnelVerdict": {
                    "matchMode": "TARGET_DIRECTION",
                    "analysisDegraded": false,
                    "strength": {"band": "MIXED"},
                    "presentation": {"score": 62},
                    "positioning": {"currentAnchor": "后端转AI"},
                    "redFlags": [
                      {"type": "gap", "severity": "high", "message": "两年空窗"},
                      {"type": "float", "severity": "low", "message": "数字无来源"}
                    ],
                    "evaluation": {"dimensions": [
                      {"dimension": "depth", "level": "STRONG"},
                      {"dimension": "communication", "level": null}
                    ]}
                  }
                }
                """;
        EvalRunner.ResultJson result = new ObjectMapper().readValue(json, EvalRunner.ResultJson.class);

        StabilityMeasurer.RunObservation o = StabilityMeasurer.observe("r1", "LLM", result);

        assertThat(o.degraded()).isFalse();
        assertThat(o.strengthBand()).isEqualTo("MIXED");
        assertThat(o.presentationScore()).isEqualTo(62);
        assertThat(o.positioningAnchor()).isEqualTo("后端转AI");
        // 键序稳定：type::severity::message 且按字典序，跨跑可直接字符串比对
        assertThat(o.redFlagKeys()).containsExactly("float::low::数字无来源", "gap::high::两年空窗");
        // level 缺失记 ABSENT，不算 NULL 混入众数
        assertThat(o.dimensionLevels()).containsEntry("communication", "ABSENT")
                .containsEntry("depth", "STRONG");
    }

    @Test
    void missingFunnelVerdictShouldAlsoDegradate() throws Exception {
        EvalRunner.ResultJson result = new ObjectMapper()
                .readValue("{\"summary\": \"只有顶层字段\"}", EvalRunner.ResultJson.class);

        assertThat(StabilityMeasurer.observe("r1", "FALLBACK", result).degraded()).isTrue();
    }
}
