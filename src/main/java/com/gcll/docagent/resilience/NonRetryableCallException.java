package com.gcll.docagent.resilience;

/**
 * 不可重试异常：4xx / 鉴权失败 / prompt 加载失败 / 确定性错误。
 * <p>Resilience4j retry 配置的 {@code ignoreExceptions} 包含本类，命中后立即失败不重试。
 */
public class NonRetryableCallException extends RuntimeException {

    public NonRetryableCallException(String message) {
        super(message);
    }

    public NonRetryableCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
