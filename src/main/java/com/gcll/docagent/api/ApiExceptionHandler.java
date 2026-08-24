package com.gcll.docagent.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.UUID;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiErrorResponse> business(BusinessException ex, HttpServletRequest request) {
        return error(ex.getErrorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return error(ErrorCode.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        return error(ErrorCode.VALIDATION_FAILED, "Request validation failed.", request);
    }

    private ResponseEntity<ApiErrorResponse> error(ErrorCode code, String message, HttpServletRequest request) {
        return ResponseEntity
                .status(code.httpStatus())
                .body(new ApiErrorResponse(false, code.name(), message, traceId(request)));
    }

    private String traceId(HttpServletRequest request) {
        String traceId = request.getHeader("X-Trace-Id");
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }
}
