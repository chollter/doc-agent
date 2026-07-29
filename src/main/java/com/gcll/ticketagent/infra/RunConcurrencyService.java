package com.gcll.ticketagent.infra;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 幂等键 + Run 锁，统一用 StringRedisTemplate（setIfAbsent + Lua 脚本解锁）。
 * <p>
 * v2 移除了 Redisson 依赖：分布式锁通过 RedisTemplate + Lua 脚本实现，
 * 不需要看门狗续期场景下足够可靠，避免引入 2.5MB+ 的 Redisson。
 */
@Service
public class RunConcurrencyService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);

    private static final String UNLOCK_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
            "  return redis.call('del', KEYS[1]) " +
            "else " +
            "  return 0 " +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> unlockRedisScript;
    private final Map<String, String> localIdempotencyCache = new ConcurrentHashMap<>();
    private final Map<String, String> localLocks = new ConcurrentHashMap<>();

    public RunConcurrencyService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.unlockRedisScript = new DefaultRedisScript<>(UNLOCK_SCRIPT, Long.class);
    }

    public String submitKey(String sessionId, String userId, String content) {
        return "submit:" + sha256(sessionId + "|" + userId + "|" + content);
    }

    public String clientRequestKey(String requestId) {
        return "client:" + requestId.trim();
    }

    public String supplementKey(String runId, String content) {
        return "supplement:" + sha256(runId + "|" + content);
    }

    public Optional<String> getRunIdByIdempotencyKey(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get("idempotency:" + key));
        } catch (RuntimeException ex) {
            return Optional.ofNullable(localIdempotencyCache.get(key));
        }
    }

    public void rememberRunId(String key, String runId) {
        try {
            redisTemplate.opsForValue().set("idempotency:" + key, runId, IDEMPOTENCY_TTL);
        } catch (RuntimeException ex) {
            localIdempotencyCache.put(key, runId);
        }
    }

    /**
     * 原子写入幂等键；若已存在则返回已有 runId。
     */
    public Optional<String> rememberRunIdIfAbsent(String key, String runId) {
        try {
            Boolean inserted = redisTemplate.opsForValue().setIfAbsent("idempotency:" + key, runId, IDEMPOTENCY_TTL);
            if (Boolean.TRUE.equals(inserted)) {
                return Optional.empty();
            }
            return Optional.ofNullable(redisTemplate.opsForValue().get("idempotency:" + key));
        } catch (RuntimeException ex) {
            String existing = localIdempotencyCache.putIfAbsent(key, runId);
            return Optional.ofNullable(existing);
        }
    }

    public <T> Optional<T> withRunLock(String runId, Supplier<T> supplier) {
        String lockKey = "run-lock:" + runId;
        return withRedisTemplateLock(lockKey, supplier);
    }

    private <T> Optional<T> withRedisTemplateLock(String lockKey, Supplier<T> supplier) {
        String token = UUID.randomUUID().toString();
        boolean locked = lockWithRedisTemplate(lockKey, token);
        if (!locked) {
            return Optional.empty();
        }
        try {
            return Optional.of(supplier.get());
        } finally {
            unlockWithRedisTemplate(lockKey, token);
        }
    }

    private boolean lockWithRedisTemplate(String lockKey, String token) {
        try {
            Boolean result = redisTemplate.opsForValue().setIfAbsent(lockKey, token, LOCK_TTL);
            return Boolean.TRUE.equals(result);
        } catch (RuntimeException ex) {
            return localLocks.putIfAbsent(lockKey, token) == null;
        }
    }

    private void unlockWithRedisTemplate(String lockKey, String token) {
        try {
            redisTemplate.execute(unlockRedisScript, List.of(lockKey), token);
        } catch (RuntimeException ex) {
            localLocks.remove(lockKey, token);
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
