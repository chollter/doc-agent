package com.gcll.ticketagent.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语义缓存配置属性。
 */
@ConfigurationProperties(prefix = "opsmind.cache.semantic")
public class SemanticCacheProperties {

    /** 是否启用语义缓存（默认关闭，需显式开启）。 */
    private boolean enabled = false;

    /** 缓存 TTL（秒），0 表示不过期。 */
    private long ttlSeconds = 3600;

    /** LLM 缓存命中相似度阈值（0-1，暂未使用，预留给向量相似度路径）。 */
    private double similarityThreshold = 0.95;

    /** 定时清理过期缓存的间隔（毫秒）。 */
    private long evictionIntervalMs = 300_000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getTtlSeconds() { return ttlSeconds; }
    public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
    public double getSimilarityThreshold() { return similarityThreshold; }
    public void setSimilarityThreshold(double similarityThreshold) { this.similarityThreshold = similarityThreshold; }
    public long getEvictionIntervalMs() { return evictionIntervalMs; }
    public void setEvictionIntervalMs(long evictionIntervalMs) { this.evictionIntervalMs = evictionIntervalMs; }
}
