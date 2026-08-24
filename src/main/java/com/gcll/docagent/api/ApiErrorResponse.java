package com.gcll.docagent.api;

public record ApiErrorResponse(
        boolean success,
        String code,
        String message,
        String traceId
) {
}
