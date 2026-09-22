package com.gcll.docagent.eval;

import com.gcll.docagent.domain.AgentStep;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrajectoryMetricsTest {

    @Test
    void recordsEvidenceExplorationOutcomeSeparatelyFromReactRounds() {
        AgentStep step = new AgentStep("s1", "run-1", null, "EVIDENCE_EXPLORE",
                "COMPLETED", "span-1", Instant.now(), Instant.now());
        step.setOutputSnapshot("evidence=2");
        step.setCostMs(37);

        TrajectoryMetrics metrics = TrajectoryMetrics.of(List.of(step), List.of(), 100);

        assertThat(metrics.evidenceExploreTriggered()).isTrue();
        assertThat(metrics.evidenceExploreSucceeded()).isTrue();
        assertThat(metrics.evidenceExploreCostMs()).isEqualTo(37);
        assertThat(metrics.llmRounds()).isZero();
    }

    @Test
    void distinguishesExplorationWithNoEvidence() {
        AgentStep step = new AgentStep("s1", "run-1", null, "EVIDENCE_EXPLORE",
                "COMPLETED", "span-1", Instant.now(), Instant.now());
        step.setOutputSnapshot("evidence=0");

        TrajectoryMetrics metrics = TrajectoryMetrics.of(List.of(step), List.of(), 100);

        assertThat(metrics.evidenceExploreTriggered()).isTrue();
        assertThat(metrics.evidenceExploreSucceeded()).isFalse();
    }
}
