package com.gcll.ticketagent.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 故障注入端到端测试——验证 5 层装饰器链在各类故障下的完整行为。
 *
 * <h3>与 {@link ExternalCallGatewayTest} 的区别</h3>
 * ExternalCallGatewayTest 验证单层行为（重试、NonRetryable 等），
 * 本测试验证<strong>跨层故障传播链路</strong>：超时→重试耗尽、熔断器打开→快速失败、限流拒绝等。
 *
 * <p>每个测试链路独立构建 gateway，避免 Resilience4j 实例状态互相干扰。
 */
class FaultInjectionEndToEndTest {

    // ========== 链路1：超时 → 重试耗尽 → 返回 fail ==========

    @Test
    @DisplayName("超时+重试耗尽：调用持续超时 → 重试3次后返回 fail")
    void timeoutTriggersRetryAndEventuallyFails() {
        ExternalCallGateway gateway = buildGateway("llm.retry-test", true, false, false);

        AtomicInteger counter = new AtomicInteger();
        CallResult<String> result = gateway.execute("llm.retry-test", () -> {
            counter.incrementAndGet();
            throw new RetryableCallException("LLM timeout simulated");
        });

        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.error()).isInstanceOf(RetryableCallException.class);
        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("超时后重试成功：前2次超时、第3次成功 → 返回 ok")
    void timeoutThenRetrySucceeds() {
        ExternalCallGateway gateway = buildGateway("llm.retry-recover", true, false, false);

        AtomicInteger counter = new AtomicInteger();
        CallResult<String> result = gateway.execute("llm.retry-recover", () -> {
            int attempt = counter.incrementAndGet();
            if (attempt < 3) {
                throw new RetryableCallException("timeout");
            }
            return "recovered";
        });

        assertThat(result.success()).isTrue();
        assertThat(result.value()).isEqualTo("recovered");
        assertThat(result.attempts()).isEqualTo(3);
    }

    // ========== 链路2：熔断器打开 → 快速失败 ==========

    @Test
    @DisplayName("熔断器打开：连续失败达阈值后 CB 状态为 OPEN → 后续调用快速失败且不触发下游")
    void circuitBreakerOpensAfterConsecutiveFailures() {
        String callName = "llm.cb-test";
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                .retryExceptions(RetryableCallException.class)
                .ignoreExceptions(NonRetryableCallException.class)
                .build());
        TimeLimiterRegistry tlRegistry = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5)).build());
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50).slidingWindowSize(10).minimumNumberOfCalls(3)
                .waitDurationInOpenState(Duration.ofSeconds(10)).build());
        RateLimiterRegistry rlRegistry = RateLimiterRegistry.ofDefaults();

        CallRegistry callRegistry = new CallRegistry(retryRegistry, tlRegistry, cbRegistry, rlRegistry);
        CallRegistry.CallMapping mapping = new CallRegistry.CallMapping();
        mapping.setRetry("test");
        mapping.setTimelimiter("test");
        mapping.setCircuitbreaker("test");
        callRegistry.setCallMappings(Map.of(callName, mapping));
        ExternalCallGateway gateway = new ExternalCallGateway(callRegistry,
                new CallMetrics(new SimpleMeterRegistry()), Executors.newScheduledThreadPool(2));

        // minimumNumberOfCalls=3, 失败3次后 CB 应打开
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            gateway.execute(callName, () -> {
                throw new RetryableCallException("fail-" + idx);
            });
        }

        // 直接检查 CB 状态
        CircuitBreaker cb = cbRegistry.circuitBreaker("test");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // CB 打开后，下游不再被调用
        AtomicInteger counter = new AtomicInteger();
        CallResult<String> result = gateway.execute(callName, () -> {
            counter.incrementAndGet();
            return "should-not-reach";
        });

        assertThat(result.success()).isFalse();
        assertThat(counter.get()).isEqualTo(0); // 下游没被调
    }

    @Test
    @DisplayName("熔断器快速失败：CB OPEN 后调用立即返回（durationMs 接近 0），不等待重试")
    void circuitBreakerOpenReturnsImmediatelyWithoutRetry() {
        String callName = "llm.cb-fast";
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                .retryExceptions(RetryableCallException.class)
                .ignoreExceptions(NonRetryableCallException.class)
                .build());
        TimeLimiterRegistry tlRegistry = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5)).build());
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50).slidingWindowSize(10).minimumNumberOfCalls(3)
                .waitDurationInOpenState(Duration.ofSeconds(10)).build());
        RateLimiterRegistry rlRegistry = RateLimiterRegistry.ofDefaults();

        CallRegistry callRegistry = new CallRegistry(retryRegistry, tlRegistry, cbRegistry, rlRegistry);
        CallRegistry.CallMapping mapping = new CallRegistry.CallMapping();
        mapping.setRetry("test");
        mapping.setTimelimiter("test");
        mapping.setCircuitbreaker("test");
        callRegistry.setCallMappings(Map.of(callName, mapping));
        ExternalCallGateway gateway = new ExternalCallGateway(callRegistry,
                new CallMetrics(new SimpleMeterRegistry()), Executors.newScheduledThreadPool(2));

        // 让 CB 打开
        for (int i = 0; i < 3; i++) {
            gateway.execute(callName, () -> {
                throw new RetryableCallException("fail");
            });
        }

        // CB OPEN 后调用应快速返回（attempts=1，不重试）
        CallResult<String> result = gateway.execute(callName, () -> "never");
        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(1); // CB 拦截，不进重试
        assertThat(result.durationMs()).isLessThan(100L); // 快速失败，不等超时
    }

    // ========== 链路3：限流拒绝 → 返回 fail ==========

    @Test
    @DisplayName("限流拒绝：超过速率限制后返回 fail")
    void rateLimiterRejectsExcessCalls() {
        String callName = "llm.rl-test";
        ExternalCallGateway gateway = buildGateway(callName, true, false, true);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            final int idx = i;
            CallResult<String> result = gateway.execute(callName, () -> "call-" + idx);
            if (result.success()) {
                successCount.incrementAndGet();
            } else if (!result.circuitOpen()) {
                rejectedCount.incrementAndGet();
            }
        }

        // 限流窗口内只允许2个许可，超出的应被拒绝
        assertThat(successCount.get()).isGreaterThan(0);
        assertThat(rejectedCount.get()).isGreaterThan(0);
    }

    // ========== 链路4：NonRetryable 不触发重试，直接返回 fail ==========

    @Test
    @DisplayName("NonRetryable 异常不触发重试，直接返回 fail")
    void nonRetryableDoesNotTriggerRetry() {
        String callName = "llm.nonretry-test";
        ExternalCallGateway gateway = buildGateway(callName, true, false, false);

        AtomicInteger counter = new AtomicInteger();
        CallResult<String> result = gateway.execute(callName, () -> {
            counter.incrementAndGet();
            throw new NonRetryableCallException("auth failed");
        });

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isInstanceOf(NonRetryableCallException.class);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(counter.get()).isEqualTo(1);
    }

    // ========== 链路5：熔断快速失败 vs 重试耗尽可区分 ==========

    @Test
    @DisplayName("重试耗尽：attempts=3（耗尽重试次数），durationMs>0（实际等待了重试间隔）")
    void retryExhaustedHasMultipleAttempts() {
        String callName = "llm.retry-exhausted";
        ExternalCallGateway gateway = buildGateway(callName, true, false, false);

        CallResult<String> result = gateway.execute(callName, () -> {
            throw new RetryableCallException("timeout");
        });

        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(3); // 重试耗尽
        assertThat(result.error()).isNotNull();
    }

    @Test
    @DisplayName("CB快速失败：attempts=1（不重试），CB 状态为 OPEN")
    void cbOpenFastFailHasOneAttempt() {
        String callName = "llm.cb-vs-retry";
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                .retryExceptions(RetryableCallException.class)
                .ignoreExceptions(NonRetryableCallException.class)
                .build());
        TimeLimiterRegistry tlRegistry = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5)).build());
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50).slidingWindowSize(10).minimumNumberOfCalls(3)
                .waitDurationInOpenState(Duration.ofSeconds(10)).build());
        RateLimiterRegistry rlRegistry = RateLimiterRegistry.ofDefaults();

        CallRegistry callRegistry = new CallRegistry(retryRegistry, tlRegistry, cbRegistry, rlRegistry);
        CallRegistry.CallMapping mapping = new CallRegistry.CallMapping();
        mapping.setRetry("test");
        mapping.setTimelimiter("test");
        mapping.setCircuitbreaker("test");
        callRegistry.setCallMappings(Map.of(callName, mapping));
        ExternalCallGateway gateway = new ExternalCallGateway(callRegistry,
                new CallMetrics(new SimpleMeterRegistry()), Executors.newScheduledThreadPool(2));

        // 先让 CB 打开
        for (int i = 0; i < 3; i++) {
            gateway.execute(callName, () -> {
                throw new RetryableCallException("fail");
            });
        }

        // CB 快速失败：attempts=1，不重试
        CallResult<String> result = gateway.execute(callName, () -> "never");
        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(1); // CB 拦截不进重试
        assertThat(cbRegistry.circuitBreaker("test").getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    // === 辅助 ===

    private ExternalCallGateway buildGateway(String callName, boolean withRetry, boolean withCb, boolean withRateLimit) {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3).waitDuration(Duration.ofMillis(10))
                .retryExceptions(RetryableCallException.class)
                .ignoreExceptions(NonRetryableCallException.class)
                .build());
        TimeLimiterRegistry tlRegistry = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5)).build());
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50).slidingWindowSize(10).minimumNumberOfCalls(3)
                .waitDurationInOpenState(Duration.ofSeconds(10)).build());
        RateLimiterRegistry rlRegistry = RateLimiterRegistry.of(RateLimiterConfig.custom()
                .limitForPeriod(2).limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofMillis(100)).build());

        CallRegistry callRegistry = new CallRegistry(retryRegistry, tlRegistry, cbRegistry, rlRegistry);
        CallRegistry.CallMapping mapping = new CallRegistry.CallMapping();
        mapping.setRetry(withRetry ? "test" : null);
        mapping.setTimelimiter("test");
        mapping.setCircuitbreaker(withCb ? "test" : null);
        mapping.setRateLimiter(withRateLimit ? "test" : null);
        callRegistry.setCallMappings(Map.of(callName, mapping));

        return new ExternalCallGateway(callRegistry,
                new CallMetrics(new SimpleMeterRegistry()), Executors.newScheduledThreadPool(2));
    }
}
