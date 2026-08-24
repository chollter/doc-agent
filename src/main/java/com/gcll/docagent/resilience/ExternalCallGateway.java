package com.gcll.docagent.resilience;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 统一外部调用治理入口——5层装饰器。
 * <p>装饰器链（外→内）：RateLimiter → CircuitBreaker → TimeLimiter → Retry → 实际调用
 * <p>所有外部调用（LLM / 向量检索 / MCP 工具）经此包装，按 {@code callName} 应用策略。
 *
 * <h3>5层职责</h3>
 * <ol>
 *   <li><b>RateLimiter</b>：限流，防止突发流量压垮下游</li>
 *   <li><b>CircuitBreaker</b>：熔断，连续失败后快速失败不再调用</li>
 *   <li><b>TimeLimiter</b>：超时，单次调用超过阈值则取消</li>
 *   <li><b>Retry</b>：重试，可重试异常（5xx/429/超时）自动重试</li>
 *   <li><b>Audit</b>：埋点，由 CallMetrics 统一记录（非装饰器，但逻辑上第5层）</li>
 * </ol>
 *
 * <h3>异常分类契约</h3>
 * 执行器（如 {@code LlmGateway}）负责把底层异常翻译成 {@link RetryableCallException}
 * 或 {@link NonRetryableCallException}。Retry 配置按这两个类型决定是否重试。
 */
@Service
public class ExternalCallGateway {

    private static final Logger log = LoggerFactory.getLogger(ExternalCallGateway.class);

    private final CallRegistry registry;
    private final CallMetrics metrics;
    private final ScheduledExecutorService timeLimiterScheduler;

    public ExternalCallGateway(CallRegistry registry,
                               CallMetrics metrics,
                               @Qualifier("timeLimiterScheduler") ScheduledExecutorService timeLimiterScheduler) {
        this.registry = registry;
        this.metrics = metrics;
        this.timeLimiterScheduler = timeLimiterScheduler;
    }

    /**
     * 执行一个外部调用，按 callName 应用治理策略。
     */
    public <T> CallResult<T> execute(String callName, Supplier<T> call) {
        CallDecorators d = registry.get(callName);
        long start = System.currentTimeMillis();
        if (d.rateLimiter() == null && d.retry() == null && d.timeLimiter() == null && d.circuitBreaker() == null) {
            return executePlain(callName, call, start);
        }
        AtomicInteger attemptCounter = new AtomicInteger(0);
        Supplier<T> countedCall = () -> {
            attemptCounter.incrementAndGet();
            return call.get();
        };
        try {
            T value = executeDecorated(d, countedCall);
            long duration = System.currentTimeMillis() - start;
            int attempts = Math.max(1, attemptCounter.get());
            metrics.recordSuccess(callName, duration, attempts);
            return CallResult.ok(value, attempts, duration);
        } catch (RequestNotPermitted ex) {
            long duration = System.currentTimeMillis() - start;
            log.warn("external call rate limited, callName={}, durationMs={}", callName, duration);
            return CallResult.fail(ex, 0, duration);
        } catch (CallNotPermittedException ex) {
            long duration = System.currentTimeMillis() - start;
            metrics.recordCircuitOpen(callName, duration);
            log.warn("external call circuit open, callName={}, durationMs={}", callName, duration);
            return CallResult.circuitOpen(duration);
        } catch (Exception ex) {
            Throwable cause = unwrap(ex);
            long duration = System.currentTimeMillis() - start;
            int attempts = Math.max(1, attemptCounter.get());
            metrics.recordFailure(callName, duration, attempts, cause.getClass().getSimpleName());
            log.warn("external call failed, callName={}, attempts={}, durationMs={}, error={}",
                    callName, attempts, duration, cause.getMessage());
            return CallResult.fail(cause, attempts, duration);
        }
    }

    /**
     * 装饰器链组合（外→内）：RateLimiter → CircuitBreaker → TimeLimiter → Retry → 实际调用。
     * <p>stageSupplier 只在末尾 get() 一次，避免重复执行。
     */
    private <T> T executeDecorated(CallDecorators d, Supplier<T> call) {
        Supplier<CompletionStage<T>> stageSupplier = () -> CompletableFuture.supplyAsync(call);
        if (d.retry() != null) {
            stageSupplier = Retry.decorateCompletionStage(d.retry(), timeLimiterScheduler, stageSupplier);
        }
        if (d.timeLimiter() != null) {
            TimeLimiter tl = d.timeLimiter();
            final Supplier<CompletionStage<T>> inner = stageSupplier;
            stageSupplier = () -> tl.executeCompletionStage(timeLimiterScheduler, inner);
        }
        if (d.circuitBreaker() != null) {
            CircuitBreaker cb = d.circuitBreaker();
            final Supplier<CompletionStage<T>> inner = stageSupplier;
            stageSupplier = () -> cb.executeCompletionStage(inner);
        }
        if (d.rateLimiter() != null) {
            RateLimiter rl = d.rateLimiter();
            final Supplier<CompletionStage<T>> inner = stageSupplier;
            stageSupplier = () -> RateLimiter.decorateCompletionStage(rl, inner).get();
        }
        return stageSupplier.get().toCompletableFuture().join();
    }

    private <T> CallResult<T> executePlain(String callName, Supplier<T> call, long start) {
        try {
            T value = call.get();
            long duration = System.currentTimeMillis() - start;
            metrics.recordSuccess(callName, duration, 1);
            return CallResult.ok(value, 1, duration);
        } catch (Exception ex) {
            long duration = System.currentTimeMillis() - start;
            metrics.recordFailure(callName, duration, 1, ex.getClass().getSimpleName());
            log.warn("external call failed (plain), callName={}, durationMs={}, error={}",
                    callName, duration, ex.getMessage());
            return CallResult.fail(ex, 1, duration);
        }
    }

    private Throwable unwrap(Throwable ex) {
        if ((ex instanceof CompletionException || ex instanceof ExecutionException) && ex.getCause() != null) {
            return ex.getCause();
        }
        return ex;
    }
}
