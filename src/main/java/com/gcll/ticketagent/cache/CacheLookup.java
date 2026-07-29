package com.gcll.ticketagent.cache;

/**
 * 语义缓存查询结果。
 *
 * @param hit    是否命中缓存
 * @param value  缓存的响应内容（hit=true 时非null）
 * @param cacheEntryId 缓存条目 ID（命中后用于更新 hit_count）
 */
public record CacheLookup(
        boolean hit,
        String value,
        String cacheEntryId
) {
    static CacheLookup miss() {
        return new CacheLookup(false, null, null);
    }

    static CacheLookup hit(String value, String cacheEntryId) {
        return new CacheLookup(true, value, cacheEntryId);
    }

    public boolean isHit() {
        return hit;
    }
}
