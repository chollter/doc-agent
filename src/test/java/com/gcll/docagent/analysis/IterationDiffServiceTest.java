package com.gcll.docagent.analysis;

import com.gcll.docagent.analysis.FunnelVerdict.Evaluation;
import com.gcll.docagent.analysis.FunnelVerdict.Evaluation.DimensionComment;
import com.gcll.docagent.analysis.IterationReport.LandingStatus;
import com.gcll.docagent.analysis.IterationReport.SuggestionLanding;
import com.gcll.docagent.analysis.StrengthStats.StrengthBand;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IterationDiffService 单测——确定性迭代 diff 的正确性 + 两条诚实红线：
 * 缺基线不编造（unavailable）、同版不误报变化。全部纯对象构造，不碰 JSON/Spring。
 */
class IterationDiffServiceTest {

    private final IterationDiffService service = new IterationDiffService();

    // --- builders ---

    private static FunnelVerdict fv(List<RedFlag> redFlags, PositioningCheck pos,
                                    StrengthStats strength, Presentation pres, Evaluation eval) {
        return new FunnelVerdict(redFlags, FunnelVerdict.MODE_DIRECTION, null, List.of(), pos,
                List.of(), strength, pres, List.of(), false, List.of(), eval, List.of(), List.of());
    }

    private static AnalysisResult ar(FunnelVerdict fv, List<ActionableSuggestion> sugg) {
        return new AnalysisResult("", List.of(), List.of(), List.of(), null, sugg, fv);
    }

    private static StrengthStats ss(StrengthBand band) {
        return new StrengthStats(3, 0.5, 0.3, 0.5, 0.5, band);
    }

    private static Presentation pres(int score) {
        return new Presentation(score, null, List.of());
    }

    private static Evaluation eval(DimensionComment... dims) {
        return new Evaluation("总评", List.of(dims), List.of(), List.of());
    }

    private static DimensionComment dim(String name, String level) {
        return new DimensionComment(name, level, "评语", List.of(), "NONE");
    }

    // --- tests ---

    @Test
    void reportsAddedAndRemovedRedFlags() {
        RedFlag gap = new RedFlag(RedFlag.TIMELINE_GAP, RedFlag.Severity.HIGH, "3 个月空窗");
        RedFlag hop = new RedFlag(RedFlag.JOB_HOPPING, RedFlag.Severity.MEDIUM, "频繁跳槽");
        AnalysisResult base = ar(fv(List.of(gap, hop), null, ss(StrengthBand.MIXED), pres(70), null), List.of());
        AnalysisResult next = ar(fv(List.of(gap), null, ss(StrengthBand.MIXED), pres(70), null), List.of());

        IterationReport r = service.diff("b", "n", base, next, "全文");

        assertThat(r.redFlagsRemoved()).containsExactly(hop);
        assertThat(r.redFlagsAdded()).isEmpty();
    }

    @Test
    void flagsSameUnderlyingGapWithDifferentMessageAsRemovedPlusAdded() {
        // 不做模糊归并：同 type 但 message 变（日期/计数改了）→ 视作旧旗移除 + 新旗新增，如实反映
        RedFlag old = new RedFlag(RedFlag.TIMELINE_GAP, RedFlag.Severity.MEDIUM, "2020-11 至 2021-01 3 个月");
        RedFlag neu = new RedFlag(RedFlag.TIMELINE_GAP, RedFlag.Severity.MEDIUM, "2020-11 至 2021-02 4 个月");
        AnalysisResult base = ar(fv(List.of(old), null, ss(StrengthBand.MIXED), pres(70), null), List.of());
        AnalysisResult next = ar(fv(List.of(neu), null, ss(StrengthBand.MIXED), pres(70), null), List.of());

        IterationReport r = service.diff("b", "n", base, next, "全文");

        assertThat(r.redFlagsAdded()).containsExactly(neu);
        assertThat(r.redFlagsRemoved()).containsExactly(old);
    }

    @Test
    void capturesStrengthBandAndPresentationScoreChange() {
        AnalysisResult base = ar(fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), null), List.of());
        AnalysisResult next = ar(fv(List.of(), null, ss(StrengthBand.STRONG), pres(82), null), List.of());

        IterationReport r = service.diff("b", "n", base, next, "全文");

        assertThat(r.strengthBandBefore()).isEqualTo("MIXED");
        assertThat(r.strengthBandAfter()).isEqualTo("STRONG");
        assertThat(r.presentationScoreBefore()).isEqualTo(70);
        assertThat(r.presentationScoreAfter()).isEqualTo(82);
    }

    @Test
    void detectsPositioningAnchorChange() {
        PositioningCheck before = new PositioningCheck(true, "Java 后端", null, null);
        PositioningCheck after = new PositioningCheck(true, "AI 应用开发", null, null);
        AnalysisResult base = ar(fv(List.of(), before, ss(StrengthBand.MIXED), pres(70), null), List.of());
        AnalysisResult next = ar(fv(List.of(), after, ss(StrengthBand.MIXED), pres(70), null), List.of());

        IterationReport r = service.diff("b", "n", base, next, "全文");

        assertThat(r.positioningChanged()).isTrue();
        assertThat(r.positioningBefore()).isEqualTo("Java 后端");
        assertThat(r.positioningAfter()).isEqualTo("AI 应用开发");
    }

    @Test
    void reportsOnlyChangedDimensions() {
        Evaluation before = eval(dim("真实性", "STRONG"), dim("含金量", "MEDIUM"));
        Evaluation after = eval(dim("真实性", "STRONG"), dim("含金量", "STRONG"));
        AnalysisResult base = ar(fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), before), List.of());
        AnalysisResult next = ar(fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), after), List.of());

        IterationReport r = service.diff("b", "n", base, next, "全文");

        assertThat(r.dimensionChanges()).hasSize(1);
        IterationReport.DimensionChange dc = r.dimensionChanges().get(0);
        assertThat(dc.dimension()).isEqualTo("含金量");
        assertThat(dc.levelBefore()).isEqualTo("MEDIUM");
        assertThat(dc.levelAfter()).isEqualTo("STRONG");
    }

    @Test
    void landedWhenSuggestionAfterAppearsDespiteWhitespaceAndPunctuation() {
        ActionableSuggestion s = new ActionableSuggestion("HIGH", "调度平台", "sec-8",
                "参与开发", "负责模型调度与长任务恢复模块，异常场景均有兜底", "改法");
        // 新版全文里落地了改法（排版/标点不同也应判 LANDED）
        AnalysisResult base = ar(fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), null), List.of(s));
        AnalysisResult next = ar(fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), null), List.of());
        String newFullText = "## 项目\n负责模型调度与长任务恢复模块：\n- 异常场景均有兜底。";

        IterationReport r = service.diff("b", "n", base, next, newFullText);

        assertThat(r.suggestionLandings()).hasSize(1);
        assertThat(r.suggestionLandings().get(0).status()).isEqualTo(LandingStatus.LANDED);
        assertThat(r.suggestionLandings().get(0).target()).isEqualTo("调度平台");
    }

    @Test
    void indeterminateWhenSuggestionIsAllPlaceholders() {
        // 改法几乎全是让候选人自填的数字占位符：去掉【…】后残留"迁移成功率"(5字)<6，无法据文本判定落地
        ActionableSuggestion s = new ActionableSuggestion("HIGH", "调度平台", "sec-8",
                "参与开发", "迁移成功率【填：百分比】", "改法");
        AnalysisResult base = ar(fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), null), List.of(s));
        AnalysisResult next = ar(fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), null), List.of());

        IterationReport r = service.diff("b", "n", base, next, "任意新版全文内容");

        // 残留太短 → 判不出落地，如实标 INDETERMINATE，不假装 NOT_LANDED
        assertThat(r.suggestionLandings().get(0).status()).isEqualTo(LandingStatus.INDETERMINATE);
    }

    @Test
    void notLandedWhenRewriteAbsentFromNewText() {
        ActionableSuggestion s = new ActionableSuggestion("HIGH", "调度平台", "sec-8",
                "参与开发", "负责模型调度与长任务恢复模块，异常场景均有兜底", "改法");
        AnalysisResult base = ar(fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), null), List.of(s));
        AnalysisResult next = ar(fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), null), List.of());

        IterationReport r = service.diff("b", "n", base, next, "这版根本没照改法写");

        assertThat(r.suggestionLandings().get(0).status()).isEqualTo(LandingStatus.NOT_LANDED);
    }

    @Test
    void returnsUnavailableWhenBaselineMissingFunnelVerdict() {
        AnalysisResult base = new AnalysisResult("", List.of(), List.of(), List.of(), null, List.of(), null);
        AnalysisResult next = ar(fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), null), List.of());

        IterationReport r = service.diff("b", "n", base, next, "全文");

        assertThat(r.degraded()).isTrue();
        assertThat(r.redFlagsAdded()).isEmpty();
    }

    @Test
    void identicalResultsProduceNoChanges() {
        FunnelVerdict f = fv(List.of(new RedFlag(RedFlag.OVERLAP, RedFlag.Severity.LOW, "重叠")),
                new PositioningCheck(true, "AI 应用开发", null, null),
                ss(StrengthBand.MIXED), pres(75), eval(dim("真实性", "STRONG")));
        AnalysisResult base = ar(f, List.of());
        AnalysisResult next = ar(f, List.of());

        IterationReport r = service.diff("b", "n", base, next, "全文");

        assertThat(r.degraded()).isFalse();
        assertThat(r.redFlagsAdded()).isEmpty();
        assertThat(r.redFlagsRemoved()).isEmpty();
        assertThat(r.positioningChanged()).isFalse();
        assertThat(r.dimensionChanges()).isEmpty();
        assertThat(r.strengthBandBefore()).isEqualTo(r.strengthBandAfter());
    }

    // --- S3 评估前变化事实（增量评估驱动输入）---

    @Test
    void preEvalFactsNoChangeWhenFactsIdentical() {
        FunnelVerdict base = fv(List.of(new RedFlag(RedFlag.OVERLAP, RedFlag.Severity.LOW, "重叠")),
                new PositioningCheck(true, "AI 应用开发", null, null), ss(StrengthBand.MIXED), pres(75), null);

        var facts = service.preEvaluationFacts(base, base.redFlags(), base.strength(),
                base.presentation(), base.positioning(), List.of(), "全文");

        assertThat(facts.hasChange()).isFalse();
        // 无变化时如实说"无结构性变化"，不制造莫须有的改写依据
        assertThat(facts.digest()).contains("无结构性变化");
    }

    @Test
    void preEvalFactsDigestListsFlagAndBandChanges() {
        FunnelVerdict base = fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), null);
        RedFlag newFlag = new RedFlag(RedFlag.TIMELINE_GAP, RedFlag.Severity.MEDIUM, "4 个月空窗");

        var facts = service.preEvaluationFacts(base, List.of(newFlag), ss(StrengthBand.STRONG),
                pres(70), null, List.of(), "全文");

        assertThat(facts.hasChange()).isTrue();
        assertThat(facts.digest())
                .contains("新增红旗 [MEDIUM] 4 个月空窗")
                .contains("强度档位：MIXED → STRONG");
    }

    @Test
    void preEvalFactsLandedSuggestionCountsAsChange() {
        FunnelVerdict base = fv(List.of(), null, ss(StrengthBand.MIXED), pres(70), null);
        ActionableSuggestion s = new ActionableSuggestion("HIGH", "调度平台", "sec-8",
                "参与开发", "负责模型调度与长任务恢复模块，异常场景均有兜底", "改法");

        var facts = service.preEvaluationFacts(base, List.of(), base.strength(),
                base.presentation(), base.positioning(), List.of(s),
                "负责模型调度与长任务恢复模块，异常场景均有兜底");

        assertThat(facts.hasChange()).isTrue();
        assertThat(facts.digest()).contains("基线改法已落地：调度平台");
    }
}
