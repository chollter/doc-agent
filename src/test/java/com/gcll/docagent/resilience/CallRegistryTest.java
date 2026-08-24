package com.gcll.docagent.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CallRegistry} 三级匹配测试：精确 > 前缀兜底 > empty。
 * <p>call-mappings 以通配 key（如 {@code llm.*}）声明前缀规则，未显式列出的 callName
 * 按前缀自动继承策略，防新增调用遗漏配置而裸奔。
 */
class CallRegistryTest {

    private CallRegistry registryWith(Map<String, CallRegistry.CallMapping> mappings) {
        CallRegistry registry = new CallRegistry(
                RetryRegistry.ofDefaults(),
                TimeLimiterRegistry.ofDefaults(),
                CircuitBreakerRegistry.ofDefaults(),
                RateLimiterRegistry.ofDefaults());
        registry.setCallMappings(mappings);
        return registry;
    }

    private CallRegistry.CallMapping mapping(String retry, String tl, String cb) {
        CallRegistry.CallMapping m = new CallRegistry.CallMapping();
        m.setRetry(retry);
        m.setTimelimiter(tl);
        m.setCircuitbreaker(cb);
        return m;
    }

    /** 前缀规则：llm.* / tool.* / vector.* 三类，模拟压缩后的 yml。 */
    private CallRegistry registryWithPrefixRules() {
        Map<String, CallRegistry.CallMapping> mappings = new LinkedHashMap<>();
        mappings.put("llm.*", mapping("llm-default", "llm-default", "llm-default"));
        mappings.put("vector.*", mapping("vector-default", "vector-default", "vector-default"));
        mappings.put("tool.*", mapping("tool-default", "tool-default", null));
        return registryWith(mappings);
    }

    @Test
    void prefixRuleCoversUngovernedLlmCall() {
        CallRegistry registry = registryWithPrefixRules();

        // llm.ticket-extract 未显式列出，应由 llm.* 兜底
        CallDecorators d = registry.get("llm.ticket-extract");

        assertThat(d.retry()).isNotNull()
                .extracting(Retry::getName).isEqualTo("llm-default");
        assertThat(d.timeLimiter()).isNotNull()
                .extracting(TimeLimiter::getName).isEqualTo("llm-default");
        assertThat(d.circuitBreaker()).isNotNull()
                .extracting(CircuitBreaker::getName).isEqualTo("llm-default");
    }

    @Test
    void toolDefaultCaughtByToolPrefix() {
        // EvidenceCollectionService.mapCallName 的 default 分支产出 "tool.default"，
        // 前缀兜底后应被 tool.* 纳入治理（修正原先裸奔）。
        CallRegistry registry = registryWithPrefixRules();

        CallDecorators d = registry.get("tool.default");

        assertThat(d.retry()).isNotNull()
                .extracting(Retry::getName).isEqualTo("tool-default");
        // tool.* 规则无 circuitbreaker，与副作用工具不挂熔断的设计一致
        assertThat(d.circuitBreaker()).isNull();
    }

    @Test
    void exactMatchOverridesPrefix() {
        // 将来给某个调用单独配策略时，精确 key 应优先于前缀规则
        Map<String, CallRegistry.CallMapping> mappings = new LinkedHashMap<>();
        mappings.put("llm.*", mapping("llm-default", "llm-default", "llm-default"));
        mappings.put("llm.root-cause", mapping("llm-strict", "llm-strict", "llm-strict"));
        CallRegistry registry = registryWith(mappings);

        CallDecorators d = registry.get("llm.root-cause");

        assertThat(d.retry()).isNotNull()
                .extracting(Retry::getName).isEqualTo("llm-strict");
    }

    @Test
    void callWithoutAnyPrefixReturnsEmpty() {
        // 不以 llm./tool./vector. 开头的 callName 无前缀匹配，裸奔（plain 路径）
        CallRegistry registry = registryWithPrefixRules();

        CallDecorators d = registry.get("unmapped.call");

        assertThat(d.retry()).isNull();
        assertThat(d.timeLimiter()).isNull();
        assertThat(d.circuitBreaker()).isNull();
    }

    @Test
    void emptyMappingsFallsBackToBuiltinDefault() {
        // yml 不配 call-mappings 时，用内置默认（llm.* → llm-default 策略）
        CallRegistry registry = registryWith(new LinkedHashMap<>());

        CallDecorators d = registry.get("llm.anything");

        // 内置默认：llm.* 应匹配到 llm-default 重试策略（不再返回 empty）
        assertThat(d.retry()).isNotNull();
    }
}
