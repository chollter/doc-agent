package com.gcll.docagent.resilience;

import com.gcll.docagent.api.dto.LlmRunStatsDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRunStatsRecorderTest {

    @Test
    void snapshotsCallsByRunAndCallName() {
        LlmRunStatsRecorder recorder = new LlmRunStatsRecorder();

        recorder.record("run-1", "llm.ticket-extract",
                CallResult.ok(LlmResponse.of("{}", 10, 5, "qwen-turbo"), 1, 120));
        recorder.record("run-1", "llm.root-cause",
                CallResult.fail(new RetryableCallException("timeout"), 2, 800));
        recorder.record("run-2", "llm.ticket-extract",
                CallResult.ok(LlmResponse.of("{}", 3, 2, "qwen-turbo"), 1, 30));

        var snapshot = recorder.snapshot("run-1");

        assertThat(snapshot.totalCalls()).isEqualTo(2);
        assertThat(snapshot.successCalls()).isEqualTo(1);
        assertThat(snapshot.failedCalls()).isEqualTo(1);
        assertThat(snapshot.fallbackSignals()).isEqualTo(1);
        assertThat(snapshot.totalDurationMs()).isEqualTo(920);
        assertThat(snapshot.byCallName()).containsKeys("llm.ticket-extract", "llm.root-cause");
        assertThat(snapshot.byCallName().get("llm.root-cause").failedCalls()).isEqualTo(1);
        assertThat(snapshot.calls()).extracting(LlmRunStatsDto.LlmCallBrief::errorType)
                .contains("RetryableCallException");
    }
}
