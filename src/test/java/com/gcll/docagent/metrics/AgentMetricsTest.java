package com.gcll.docagent.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    @Test
    void recordsMinimalOperationalCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordAgentRun();
        metrics.recordLlmCall("llm.root-cause", true);
        metrics.recordToolCall("query_logs", false);
        metrics.recordRagHit();
        metrics.recordFallback("tool.query_logs");

        assertThat(registry.counter("agent_run_total").count()).isEqualTo(1);
        assertThat(registry.counter("llm_call_total", "callName", "llm.root-cause", "success", "true").count())
                .isEqualTo(1);
        assertThat(registry.counter("tool_call_total", "toolName", "query_logs", "success", "false").count())
                .isEqualTo(1);
        assertThat(registry.counter("rag_hit_total").count()).isEqualTo(1);
        assertThat(registry.counter("fallback_total", "source", "tool.query_logs").count()).isEqualTo(1);
    }
}
