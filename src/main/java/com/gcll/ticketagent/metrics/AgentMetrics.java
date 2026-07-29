package com.gcll.ticketagent.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Agent 最小业务观测指标。
 *
 * <p>面试展示重点是少量稳定信号：run 量、LLM 调用量、工具调用量、RAG 命中和 fallback。
 * LLM 详细耗时 / token 仍在 {@link com.gcll.ticketagent.resilience.CallMetrics}
 * （external_call_duration / ai_token_usage）中。
 */
@Component
public class AgentMetrics {

    private final MeterRegistry meterRegistry;
    private final Counter agentRunCounter;
    private final Counter ragHitCounter;

    public AgentMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.agentRunCounter = Counter.builder("agent_run_total")
                .description("Total submitted agent runs")
                .register(meterRegistry);
        this.ragHitCounter = Counter.builder("rag_hit_total")
                .description("Total RAG searches with at least one hit")
                .register(meterRegistry);
        Counter.builder("agent.rag.hit.count").register(meterRegistry);
    }

    public void recordAgentRun() {
        agentRunCounter.increment();
    }

    public void recordLlmCall(String callName, boolean success) {
        meterRegistry.counter("llm_call_total",
                "callName", safeTag(callName),
                "success", String.valueOf(success)).increment();
    }

    public void recordToolCall(String toolName, boolean success) {
        meterRegistry.counter("tool_call_total",
                "toolName", safeTag(toolName),
                "success", String.valueOf(success)).increment();
    }

    public void recordRagHit() {
        ragHitCounter.increment();
        meterRegistry.counter("agent.rag.hit.count").increment();
    }

    public void recordFallback(String source) {
        meterRegistry.counter("fallback_total", "source", safeTag(source)).increment();
    }

    private String safeTag(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
