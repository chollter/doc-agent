package com.gcll.docagent.resilience;

/**
 * 外部调用结果。
 * <p>成功时 {@link #success()} 为 true 且 {@link #value()} 有值；
 * 失败时携带 {@link #error()}；熔断打开时 {@link #circuitOpen()} 为 true。
 */
public record CallResult<T>(
        boolean success,
        T value,
        Throwable error,
        int attempts,
        long durationMs,
        boolean circuitOpen
) {

    public static <T> CallResult<T> ok(T value, int attempts, long durationMs) {
        return new CallResult<>(true, value, null, attempts, durationMs, false);
    }

    public static <T> CallResult<T> fail(Throwable error, int attempts, long durationMs) {
        return new CallResult<>(false, null, error, attempts, durationMs, false);
    }

    public static <T> CallResult<T> circuitOpen(long durationMs) {
        return new CallResult<>(false, null, null, 0, durationMs, true);
    }
}
