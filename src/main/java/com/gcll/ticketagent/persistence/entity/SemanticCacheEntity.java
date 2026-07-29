package com.gcll.ticketagent.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("semantic_cache")
public class SemanticCacheEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    private String cacheType;
    private String callName;
    private String queryText;
    private String queryHash;
    private String responseText;
    private String metadataJson;
    private Integer hitCount;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCacheType() { return cacheType; }
    public void setCacheType(String cacheType) { this.cacheType = cacheType; }
    public String getCallName() { return callName; }
    public void setCallName(String callName) { this.callName = callName; }
    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }
    public String getQueryHash() { return queryHash; }
    public void setQueryHash(String queryHash) { this.queryHash = queryHash; }
    public String getResponseText() { return responseText; }
    public void setResponseText(String responseText) { this.responseText = responseText; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public Integer getHitCount() { return hitCount; }
    public void setHitCount(Integer hitCount) { this.hitCount = hitCount; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
