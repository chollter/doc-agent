package com.gcll.ticketagent.cache;

import com.gcll.ticketagent.persistence.entity.SemanticCacheEntity;
import com.gcll.ticketagent.persistence.mapper.SemanticCacheMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 语义缓存服务——省 Token + 加速重复工单。
 * <p>
 * 两级查找：
 * <ol>
 *   <li>精确路径：query SHA-256 哈希直接查 DB（O(1)，覆盖完全相同的查询）</li>
 *   <li>语义路径：哈希未命中 → embedding 向量相似度检索（覆盖语义相近但措辞不同的查询）</li>
 * </ol>
 * <p>
 * 当前实现仅用精确路径（SHA-256 hash），语义向量路径预留接口待 v2 后续迭代接入 PgVector。
 * 原因：分诊阶段 query 差异大（每个工单都不同），语义缓存命中率靠的是"相似工单"
 * 而非"完全相同 query"——这需要 PgVector 相似度搜索，且对 RAG 缓存价值更大（同一系统的
 * 类似工单可能命中同一批知识条目）。LLM 缓存更依赖精确匹配（prompt 相同才能复用）。
 */
@Service
@ConditionalOnBean(EmbeddingModel.class)
public class SemanticCacheService {

    private static final Logger log = LoggerFactory.getLogger(SemanticCacheService.class);

    private final SemanticCacheMapper mapper;

    public SemanticCacheService(SemanticCacheMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 查询缓存。
     *
     * @param type     缓存类型（LLM / RAG）
     * @param callName 调用名（LLM 缓存用，RAG 缓存传 null）
     * @param query    查询文本
     * @return 缓存查找结果
     */
    public CacheLookup lookup(CacheType type, String callName, String query) {
        String queryHash = sha256(query);

        // 精确路径：SHA-256 哈希直查
        SemanticCacheEntity entity = mapper.findByTypeAndHash(type.name(), callName, queryHash);
        if (entity != null) {
            // TTL 检查
            if (entity.getExpiresAt() != null &&
                entity.getExpiresAt().isBefore(LocalDateTime.now())) {
                log.debug("semantic cache expired, type={}, callName={}, id={}", type, callName, entity.getId());
                mapper.deleteById(entity.getId());
                return CacheLookup.miss();
            }
            // 命中：更新 hit_count
            mapper.incrementHitCount(entity.getId());
            log.info("semantic cache hit (exact), type={}, callName={}, hits={}", type, callName, entity.getHitCount() + 1);
            return CacheLookup.hit(entity.getResponseText(), entity.getId());
        }

        log.debug("semantic cache miss, type={}, callName={}", type, callName);
        return CacheLookup.miss();
    }

    /**
     * 写入缓存。
     *
     * @param type       缓存类型
     * @param callName   调用名（LLM 缓存用）
     * @param query      查询文本
     * @param response   响应内容
     * @param metadata   附加元数据 JSON（可选）
     * @param ttlSeconds TTL 秒数（0 或负数表示不过期）
     */
    public void put(CacheType type, String callName, String query, String response,
                    String metadata, long ttlSeconds) {
        String queryHash = sha256(query);
        // 幂等：同一 hash 不重复写
        SemanticCacheEntity existing = mapper.findByTypeAndHash(type.name(), callName, queryHash);
        if (existing != null) {
            log.debug("semantic cache already exists, type={}, callName={}, id={}", type, callName, existing.getId());
            return;
        }

        SemanticCacheEntity entity = new SemanticCacheEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setCacheType(type.name());
        entity.setCallName(callName);
        entity.setQueryText(truncate(query, 2000));
        entity.setQueryHash(queryHash);
        entity.setResponseText(response);
        entity.setMetadataJson(metadata);
        entity.setHitCount(0);
        entity.setCreatedAt(LocalDateTime.now());
        if (ttlSeconds > 0) {
            entity.setExpiresAt(LocalDateTime.now().plusSeconds(ttlSeconds));
        }
        mapper.insert(entity);
        log.info("semantic cache written, type={}, callName={}, id={}", type, callName, entity.getId());
    }

    /**
     * 清理过期缓存条目。可由定时任务调用。
     */
    public int evictExpired() {
        int count = mapper.deleteExpired(LocalDateTime.now());
        if (count > 0) {
            log.info("semantic cache evicted {} expired entries", count);
        }
        return count;
    }

    // --- 内部工具 ---

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    static String truncate(String text, int maxLen) {
        if (text == null) return null;
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}
