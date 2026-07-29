package com.gcll.ticketagent.observability.trace;

import io.micrometer.tracing.Tracer;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * MDC TraceId 注入过滤器
 * 
 * 自动将当前 traceId 注入到 MDC 中，让所有日志自动带 traceId。
 * 同时支持从 HTTP header 中传入外部 traceId（用于分布式追踪）。
 */
@Component
public class TraceMdcFilter extends OncePerRequestFilter {

    private final Tracer tracer;

    public TraceMdcFilter(Tracer tracer) {
        this.tracer = tracer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        // 1. 从 HTTP header 获取外部 traceId（如果有）
        String externalTraceId = extractExternalTraceId(request);
        
        // 2. 获取当前 traceId（优先用外部的，否则用内部的）
        String traceId = externalTraceId != null ? externalTraceId : getCurrentTraceId();
        
        // 3. 将 traceId 注入 MDC
        if (traceId != null) {
            MDC.put("traceId", traceId);
        }
        
        try {
            // 4. 继续过滤器链
            filterChain.doFilter(request, response);
        } finally {
            // 5. 清理 MDC
            MDC.remove("traceId");
        }
    }
    
    /**
     * 从 HTTP header 提取外部 traceId
     * 支持常见的追踪 header 格式
     */
    private String extractExternalTraceId(HttpServletRequest request) {
        // 优先级：trace-id > traceparent > x-trace-id
        String traceId = request.getHeader("trace-id");
        if (traceId != null && !traceId.trim().isEmpty()) {
            return traceId;
        }
        
        String traceparent = request.getHeader("traceparent");
        if (traceparent != null && !traceparent.trim().isEmpty()) {
            // traceparent 格式: 00-0af7651916cd43dd8448eb211c80319c-00f067aa0ba902b7-01
            // 提取 traceId 部分: 0af7651916cd43dd8448eb211c80319c
            String[] parts = traceparent.split("-");
            if (parts.length >= 3) {
                return parts[1];
            }
        }
        
        String xTraceId = request.getHeader("x-trace-id");
        if (xTraceId != null && !xTraceId.trim().isEmpty()) {
            return xTraceId;
        }
        
        return null;
    }
    
    /**
     * 获取当前线程的 traceId
     */
    private String getCurrentTraceId() {
        try {
            // 尝试从 OTel Tracer 获取当前 traceId
            if (tracer != null && tracer.currentSpan() != null) {
                return tracer.currentSpan().context().traceId();
            }
        } catch (Exception ex) {
            // 忽略异常，返回 null
        }
        return null;
    }
    
    /**
     * 是否需要跳过此过滤器（比如健康检查端点）
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || 
               path.startsWith("/api/health") ||
               path.startsWith("/api/metrics");
    }
}