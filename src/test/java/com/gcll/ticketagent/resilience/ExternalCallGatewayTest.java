package com.gcll.ticketagent.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalCallGatewayTest {

    private static final String CALL_NAME = "test.call";

    private ExternalCallGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = gatewayWithStrategy(CALL_NAME);
    }

    private ExternalCallGateway gatewayWithStrategy(String callName) {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(RetryableCallException.class)
                .ignoreExceptions(NonRetryableCallException.class)
                .build());
        TimeLimiterRegistry tlRegistry = TimeLimiterRegistry.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(5))
                .build());
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build());
        CallRegistry callRegistry = new CallRegistry(retryRegistry, tlRegistry, cbRegistry, RateLimiterRegistry.ofDefaults());
        CallRegistry.CallMapping mapping = new CallRegistry.CallMapping();
        mapping.setRetry("test");
        mapping.setTimelimiter("test");
        mapping.setCircuitbreaker("test");
        callRegistry.setCallMappings(Map.of(callName, mapping));
        CallMetrics callMetrics = new CallMetrics(new SimpleMeterRegistry());
        return new ExternalCallGateway(callRegistry, callMetrics,
                Executors.newScheduledThreadPool(2));
    }

    @Test
    void executeReturnsOkOnSuccessAndInvokesCallExactlyOnce() {
        AtomicInteger counter = new AtomicInteger();
        CallResult<String> result = gateway.execute(CALL_NAME, () -> {
            counter.incrementAndGet();
            return "ok";
        });
        assertThat(result.success()).isTrue();
        assertThat(result.value()).isEqualTo("ok");
        assertThat(result.attempts()).isEqualTo(1);
        // 关键回归断言：即使配置了 circuitBreaker/retry/timeLimiter，成功路径只调用一次。
        // 这保护 stageSupplier 不会被重复求值（否则一次 gateway.execute 会触发多次底层调用）。
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void executeReturnsFailOnNonRetryableAndDoesNotRetry() {
        AtomicInteger counter = new AtomicInteger();
        CallResult<String> result = gateway.execute(CALL_NAME, () -> {
            counter.incrementAndGet();
            throw new NonRetryableCallException("bad request");
        });
        assertThat(result.success()).isFalse();
        assertThat(result.error()).isInstanceOf(NonRetryableCallException.class);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void executeRetriesOnRetryableExceptionThenSucceeds() {
        AtomicInteger counter = new AtomicInteger();
        CallResult<String> result = gateway.execute(CALL_NAME, () -> {
            if (counter.incrementAndGet() < 2) {
                throw new RetryableCallException("transient");
            }
            return "ok-after-retry";
        });
        assertThat(result.success()).isTrue();
        assertThat(result.value()).isEqualTo("ok-after-retry");
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void executeReturnsFailAfterRetryExhausted() {
        AtomicInteger counter = new AtomicInteger();
        CallResult<String> result = gateway.execute(CALL_NAME, () -> {
            counter.incrementAndGet();
            throw new RetryableCallException("always fails");
        });
        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void executeWithoutStrategyRunsPlainPath() {
        // 无 callMappings → 所有调用走 plain 路径（不治理）
        CallRegistry emptyRegistry = new CallRegistry(
                RetryRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(),
                CircuitBreakerRegistry.ofDefaults(),
                RateLimiterRegistry.ofDefaults());
        ExternalCallGateway plainGateway = new ExternalCallGateway(
                emptyRegistry,
                new CallMetrics(new SimpleMeterRegistry()),
                Executors.newScheduledThreadPool(1));

        AtomicInteger counter = new AtomicInteger();
        CallResult<String> result = plainGateway.execute("unmapped.call", () -> {
            counter.incrementAndGet();
            return "plain";
        });
        assertThat(result.success()).isTrue();
        assertThat(result.value()).isEqualTo("plain");
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(counter.get()).isEqualTo(1);
    }
}
