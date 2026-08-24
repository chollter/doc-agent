package com.gcll.docagent.observability.trace;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 可观测性配置
 *
 * 注册 MDC TraceId 过滤器，确保所有日志自动带 traceId。
 * TraceMdcFilter 通过 @Component 自动注册，
 * 此处仅配置 FilterRegistrationBean（URL pattern + order）。
 * 注意：方法名不能与 TraceMdcFilter 的 bean 名重复，否则报 BeanDefinitionOverrideException。
 */
@Configuration
public class ObservabilityConfig {

    /**
     * 注册 MDC TraceId 过滤器的 Servlet 配置
     */
    @Bean
    public FilterRegistrationBean<TraceMdcFilter> traceMdcFilterRegistration(TraceMdcFilter traceMdcFilter) {
        FilterRegistrationBean<TraceMdcFilter> registrationBean = new FilterRegistrationBean<>();
        registrationBean.setFilter(traceMdcFilter);
        registrationBean.addUrlPatterns("/*");
        registrationBean.setOrder(1);
        registrationBean.setName("traceMdcFilterRegistration");
        return registrationBean;
    }
}