package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.gcll.docagent.analysis.ExperienceStrength.Attribution.LEAD;
import static com.gcll.docagent.analysis.ExperienceStrength.Attribution.OWNER;
import static com.gcll.docagent.analysis.ExperienceStrength.Attribution.PARTICIPANT;
import static com.gcll.docagent.analysis.ExperienceStrength.ResultQuality.BUSINESS;
import static com.gcll.docagent.analysis.ExperienceStrength.ResultQuality.NONE;
import static com.gcll.docagent.analysis.ExperienceStrength.ResultQuality.PROJECT;
import static com.gcll.docagent.analysis.ExperienceStrength.ResultQuality.TASK;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容强度分档测试——档位规则是 P7 缺陷注入评测的断言目标，必须确定性可复现。
 */
class StrengthStatsTest {

    private static ExperienceStrength entry(boolean result, ExperienceStrength.ResultQuality quality,
                                            ExperienceStrength.Attribution attribution) {
        return new ExperienceStrength("sec-1", "entry",
                Map.of("situation", true, "task", true, "action", true, "result", result),
                quality, attribution, null);
    }

    @Test
    void strongResumeShouldGetStrongBand() {
        var stats = StrengthStats.from(List.of(
                entry(true, BUSINESS, LEAD),
                entry(true, PROJECT, OWNER),
                entry(true, PROJECT, OWNER),
                entry(true, TASK, OWNER)));

        assertThat(stats.band()).isEqualTo(StrengthStats.StrengthBand.STRONG);
        assertThat(stats.resultRate()).isEqualTo(1.0);
        assertThat(stats.ownerRate()).isEqualTo(1.0);
    }

    @Test
    void strippingResultsShouldDropBandToWeak() {
        // P7 缺陷注入场景：删掉 Result（result=false + quality=NONE）
        var stats = StrengthStats.from(List.of(
                entry(false, NONE, LEAD),
                entry(false, NONE, OWNER),
                entry(true, TASK, PARTICIPANT)));

        assertThat(stats.band()).isEqualTo(StrengthStats.StrengthBand.WEAK);
        assertThat(stats.resultRate()).isLessThan(0.5);
    }

    @Test
    void participantOnlyResumeShouldBeWeak() {
        // "参与了很多但没主导过"——归因不足
        var stats = StrengthStats.from(List.of(
                entry(true, TASK, PARTICIPANT),
                entry(true, TASK, PARTICIPANT),
                entry(true, PROJECT, PARTICIPANT)));

        assertThat(stats.band()).isEqualTo(StrengthStats.StrengthBand.WEAK);
        assertThat(stats.ownerRate()).isZero();
    }

    @Test
    void mixedResumeShouldGetMixedBand() {
        var stats = StrengthStats.from(List.of(
                entry(true, PROJECT, LEAD),
                entry(false, NONE, PARTICIPANT),
                entry(true, TASK, OWNER),
                entry(false, NONE, OWNER)));

        assertThat(stats.band()).isEqualTo(StrengthStats.StrengthBand.MIXED);
        assertThat(stats.resultRate()).isEqualTo(0.5);
    }

    @Test
    void emptyEntriesShouldBeWeak() {
        assertThat(StrengthStats.from(List.of()).band())
                .isEqualTo(StrengthStats.StrengthBand.WEAK);
    }
}
