package com.gcll.ticketagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gcll.ticketagent.persistence.entity.SemanticCacheEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface SemanticCacheMapper extends BaseMapper<SemanticCacheEntity> {

    /**
     * 按 cacheType + callName + queryHash 精确查找。
     * callName 为 null 时匹配 IS NULL。
     */
    @Select("""
        SELECT * FROM semantic_cache
        WHERE cache_type = #{cacheType}
          AND (call_name = #{callName} OR (#{callName} IS NULL AND call_name IS NULL))
          AND query_hash = #{queryHash}
        LIMIT 1
    """)
    SemanticCacheEntity findByTypeAndHash(@Param("cacheType") String cacheType,
                                          @Param("callName") String callName,
                                          @Param("queryHash") String queryHash);

    @Update("UPDATE semantic_cache SET hit_count = hit_count + 1 WHERE id = #{id}")
    void incrementHitCount(@Param("id") String id);

    @Delete("DELETE FROM semantic_cache WHERE expires_at IS NOT NULL AND expires_at < #{now}")
    int deleteExpired(@Param("now") LocalDateTime now);
}
