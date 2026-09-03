package com.gcll.docagent.resilience;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 外部调用统一埋点（Micrometer）。所有指标以 {@code callName} 作为 tag，便于按调用维度聚合。
 * <ul>
 *   <li>{@code external_call_duration} —— Timer，按 callName + success 维度记录耗时</li>
 *   <li>{@code external_call_attempts} —— DistributionSummary，单次调用的尝试次数</li>
 *   <li>{@code external_call_success_total / failure_total} —— Counter</li>
 *   <li>{@code external_call_circuit_open_total} —— 熔断打开快速失败计数</li>
 *   <li>{@code ai_token_usage} —— LLM token 用量（prompt / completion）</li>
 * </ul>
 */
@Component
public class CallMetrics {

    private final MeterRegistry registry;

    public CallMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSuccess(String callName, long durationMs, int attempts) {
        Timer.builder("external_call_duration")
                .tag("callName", callName).tag("success", "true")
                .register(registry).record(Duration.ofMillis(durationMs));
        DistributionSummary.builder("external_call_attempts")
                .tag("callName", callName)
                .register(registry).record(attempts);
        registry.counter("external_call_success_total", "callName", callName).increment();
    }

    public void recordFailure(String callName, long durationMs, int attempts, String errorType) {
        Timer.builder("external_call_duration")
                .tag("callName", callName).tag("success", "false")
                .register(registry).record(Duration.ofMillis(durationMs));
        registry.counter("external_call_failure_total",
                "callName", callName, "errorType", errorType).increment();
    }

    public void recordCircuitOpen(String callName, long durationMs) {
        registry.counter("external_call_circuit_open_total", "callName", callName).increment();
    }

    public void recordTokenUsage(String callName, int promptTokens, int completionTokens) {
        recordTokenUsage(callName, "unknown", promptTokens, completionTokens);
    }

    /**
     * 阶段4：token 用量按 callName + model 分维记录。
     * 多模型路由后，需能定位"哪个 callName 费 token、用的哪个模型"，支撑成本分析。
     *
     * @param callName 调用点标识
     * @param model    实际使用的模型名（qwen-turbo/plus 等）
     */
    public void recordTokenUsage(String callName, String model, int promptTokens, int completionTokens) {
        String safeModel = model == null ? "unknown" : model;
        DistributionSummary.builder("ai_token_usage")
                .tag("callName", callName).tag("type", "prompt").tag("model", safeModel)
                .register(registry).record(promptTokens);
        DistributionSummary.builder("ai_token_usage")
                .tag("callName", callName).tag("type", "completion").tag("model", safeModel)
                .register(registry).record(completionTokens);
    }
}
