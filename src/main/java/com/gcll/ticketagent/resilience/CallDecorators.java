package com.gcll.ticketagent.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;

/**
 * 单个 callName 关联的 Resilience4j 装饰器集合。
 * <p>v2 5层装饰器：RateLimiter → CircuitBreaker → TimeLimiter → Retry → 实际调用
 * <p>任一为 null 表示该 callName 不启用对应治理维度。
 */
public record CallDecorators(
        RateLimiter rateLimiter,
        Retry retry,
        TimeLimiter timeLimiter,
        CircuitBreaker circuitBreaker
) {

    public static CallDecorators empty() {
        return new CallDecorators(null, null, null, null);
    }
}
