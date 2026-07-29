package com.gcll.ticketagent.infra;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunConcurrencyServiceTest {

    // --- P0-3: Lock returns Optional.empty() instead of throwing ---

    @Test
    void withRunLockReturnsEmptyWhenAlreadyLocked() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);

        RunConcurrencyService service = new RunConcurrencyService(redisTemplate);

        Optional<String> result = service.withRunLock("run-1", () -> "should not execute");

        assertThat(result).isEmpty();
    }

    @Test
    void withRunLockReturnsValueWhenLockAcquired() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L);

        RunConcurrencyService service = new RunConcurrencyService(redisTemplate);

        Optional<String> result = service.withRunLock("run-1", () -> "executed");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("executed");
    }

    // --- P0-2: Lua atomic unlock ---

    @SuppressWarnings("unchecked")
    @Test
    void unlockUsesLuaScriptForAtomicGetAndDelete() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenReturn(1L);

        RunConcurrencyService service = new RunConcurrencyService(redisTemplate);

        service.withRunLock("run-1", () -> "done");

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("run-lock:run-1")), any());
    }

    @Test
    void rememberRunIdIfAbsentReturnsExistingWhenKeyAlreadyPresent() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(ops.get(anyString())).thenReturn("existing-run");

        RunConcurrencyService service = new RunConcurrencyService(redisTemplate);

        Optional<String> existing = service.rememberRunIdIfAbsent("supplement:abc", "run-new");

        assertThat(existing).contains("existing-run");
    }

    // --- Fallback to local when Redis is down ---

    @Test
    void withRunLockFallsBackToLocalWhenRedisFailsOnLock() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RuntimeException("Redis down"));

        RunConcurrencyService service = new RunConcurrencyService(redisTemplate);

        Optional<String> result = service.withRunLock("run-1", () -> "executed");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("executed");
    }

    @Test
    void withRunLockFallsBackToLocalWhenRedisFailsOnUnlock() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any()))
                .thenThrow(new RuntimeException("Redis down on unlock"));

        RunConcurrencyService service = new RunConcurrencyService(redisTemplate);

        Optional<String> result = service.withRunLock("run-1", () -> "executed");

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("executed");
    }
}
