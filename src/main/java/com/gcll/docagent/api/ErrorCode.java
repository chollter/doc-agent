package com.gcll.docagent.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    TICKET_NOT_FOUND(HttpStatus.NOT_FOUND),
    AGENT_RUN_NOT_FOUND(HttpStatus.NOT_FOUND),
    APPROVAL_NOT_FOUND(HttpStatus.NOT_FOUND),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN),
    INVALID_STATE(HttpStatus.CONFLICT),
    EXTERNAL_SERVICE_FAILED(HttpStatus.BAD_GATEWAY),
    BAD_REQUEST(HttpStatus.BAD_REQUEST),
    RUN_NOT_WAITING_INPUT(HttpStatus.CONFLICT),
    EXTRACT_FAILED(HttpStatus.BAD_GATEWAY);

    private final HttpStatus httpStatus;

    ErrorCode(HttpStatus httpStatus) {
        this.httpStatus = httpStatus;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }
}
