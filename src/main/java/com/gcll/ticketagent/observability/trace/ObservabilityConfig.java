package com.gcll.ticketagent.observability.trace;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性配置
 * 
 * 注册 MDC TraceId 过滤器，确保所有日志自动带 traceId。
 */
@Configuration
public class ObservabilityConfig {

    /**
     * 注册 MDC TraceId 过滤器
     */
    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilter(TraceMdcFilter traceMdcFilter) {
        FilterRegistrationBean<TraceMdcFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(traceMdcFilter);
        registrationBean.addUrlPatterns("/*"); // 拦截所有请求
        registrationBean.setOrder(1); // 高优先级，在其他过滤器之前执行
        registrationBean.setName("traceMdcFilter");
        return registrationBean;
    }
}