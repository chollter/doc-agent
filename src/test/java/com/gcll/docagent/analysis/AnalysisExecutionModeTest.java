package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisExecutionModeTest {

    @Test
    void parsesConfiguredModeCaseInsensitively() {
        assertThat(AnalysisExecutionMode.parse("real", AnalysisExecutionMode.MOCK))
                .isEqualTo(AnalysisExecutionMode.REAL);
        assertThat(AnalysisExecutionMode.parse(" EVAL ", AnalysisExecutionMode.MOCK))
                .isEqualTo(AnalysisExecutionMode.EVAL);
    }

    @Test
    void invalidOrBlankModeUsesSafeFallback() {
        assertThat(AnalysisExecutionMode.parse("unknown", AnalysisExecutionMode.MOCK))
                .isEqualTo(AnalysisExecutionMode.MOCK);
        assertThat(AnalysisExecutionMode.parse("", AnalysisExecutionMode.REAL))
                .isEqualTo(AnalysisExecutionMode.REAL);
    }
}
