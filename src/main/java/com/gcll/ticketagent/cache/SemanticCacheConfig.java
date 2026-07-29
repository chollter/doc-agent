package com.gcll.ticketagent.cache;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 语义缓存自动配置 + 定时清理。
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(SemanticCacheProperties.class)
@ConditionalOnProperty(name = "opsmind.cache.semantic.enabled", havingValue = "true")
public class SemanticCacheConfig {

    private final SemanticCacheService cacheService;

    public SemanticCacheConfig(SemanticCacheService cacheService) {
        this.cacheService = cacheService;
    }

    @Scheduled(fixedDelayString = "${opsmind.cache.semantic.eviction-interval-ms:300000}")
    public void evictExpired() {
        cacheService.evictExpired();
    }
}
