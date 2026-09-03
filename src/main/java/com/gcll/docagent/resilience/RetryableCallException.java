package com.gcll.docagent.resilience;

/**
 * 可重试异常：5xx / 429 / 超时 / 网络错误。
 * <p>Resilience4j retry 配置的 {@code retryExceptions} 包含本类，触发自动重试。
 */
public class RetryableCallException extends RuntimeException {

    public RetryableCallException(String message) {
        super(message);
    }

    public RetryableCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
