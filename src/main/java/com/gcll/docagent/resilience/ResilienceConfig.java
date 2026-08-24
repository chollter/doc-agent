package com.gcll.docagent.resilience;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Resilience4j 治理基础设施配置。
 */
@Configuration
public class ResilienceConfig {

    /**
     * TimeLimiter 调度超时检查用的 scheduler。
     * <p>仅承担 Resilience4j {@code TimeLimiter} 的超时定时任务（轻量、高频短任务），
     * 不执行实际阻塞 IO 调用（那些由业务线程/调用方线程承担）。
     * daemon 线程，{@code destroyMethod="shutdown"} 在应用停机时关闭线程池。
     */
    @Bean(name = "timeLimiterScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService timeLimiterScheduler() {
        CustomizableThreadFactory threadFactory = new CustomizableThreadFactory("tl-sched-");
        threadFactory.setDaemon(true);
        return Executors.newScheduledThreadPool(2, threadFactory);
    }
}
