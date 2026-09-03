package com.gcll.docagent.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * callName → Resilience4j 装饰器实例映射。
 * <p>所有外部调用（LLM / 向量检索 / MCP 工具）按 callName 匹配治理策略
 * （retry / timelimiter / circuitbreaker 实例，参数由 {@code resilience4j.*} 定义）。
 *
 * <h3>三级匹配</h3>
 * <ol>
 *   <li>精确匹配：callMappings 存在 callName 的精确条目（给特定调用单独配策略）</li>
 *   <li>前缀兜底：以 {@code *} 结尾的 key（如 {@code llm.*}），最长前缀优先</li>
 *   <li>内置默认：yml 未配 call-mappings 时用 {@link #BUILTIN_DEFAULTS}</li>
 * </ol>
 *
 * <h3>内置默认映射（简化配置，方案B 精简）</h3>
 * 治理策略几乎不变（LLM 可重试/工具不重试是语义决定的），没必要每次在 yml 配。
 * yml 不配 call-mappings 时，内置默认的 4 条前缀规则自动生效。
 * 需要覆盖时在 yml 配，精确匹配 > 配置的前缀 > 内置默认。
 */
@Component
@ConfigurationProperties(prefix = "opsmind.resilience")
public class CallRegistry {

    /**
     * 内置默认治理映射（按调用类型语义决定，极少变化）：
     * <ul>
     *   <li>{@code llm.*}：可重试 + 超时 + 熔断——LLM 调用需完整治理</li>
     *   <li>{@code vector.*}：可重试 + 熔断——向量检索是外部依赖</li>
     *   <li>{@code tool.*}：不重试（maxAttempts=1）+ 超时——副作用操作不重试</li>
     *   <li>{@code rerank.*}：可重试 + 熔断——rerank 是外部依赖</li>
     * </ul>
     */
    private static final Map<String, CallMapping> BUILTIN_DEFAULTS = Map.of(
            "llm.*", mapping("llm-default", "llm-default", "llm-default", "llm-default"),
            "vector.*", mapping("vector-default", "vector-default", "vector-default", "vector-default"),
            "tool.*", mapping("tool-default", "tool-default", null, "tool-default"),
            "rerank.*", mapping("rerank-default", "rerank-default", "rerank-default", "rerank-default")
    );

    /** 快速构造 CallMapping（静态工厂）。 */
    private static CallMapping mapping(String retry, String tl, String cb, String rl) {
        CallMapping m = new CallMapping();
        m.retry = retry;
        m.timelimiter = tl;
        m.circuitbreaker = cb;
        m.rateLimiter = rl;
        return m;
    }

    /** 精确 key → 映射；不以 * 结尾的 key 视为精确 key。yml 未配时为 null（走内置默认）。 */
    private Map<String, CallMapping> callMappings;

    /** 前缀规则索引：合并"yml 配置 + 内置默认"后构建，按前缀长度降序（最长优先匹配）。 */
    private List<PrefixEntry> prefixRules = List.of();

    private final RetryRegistry retryRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RateLimiterRegistry rateLimiterRegistry;

    public CallRegistry(RetryRegistry retryRegistry,
                        TimeLimiterRegistry timeLimiterRegistry,
                        CircuitBreakerRegistry circuitBreakerRegistry,
                        RateLimiterRegistry rateLimiterRegistry) {
        this.retryRegistry = retryRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.rateLimiterRegistry = rateLimiterRegistry;
    }

    public Map<String, CallMapping> getCallMappings() {
        return callMappings;
    }

    /**
     * yml 配置注入。set 后重建 prefixRules（合并内置默认）。
     * <p>yml 未配（callMappings=null/空）时，用内置默认——这是方案B 简化的核心：
     * 治理策略写在代码里（语义决定、极少变），yml 可以完全不配 call-mappings。
     */
    public void setCallMappings(Map<String, CallMapping> callMappings) {
        // 合并：yml 配置优先（覆盖同名前缀），内置默认兜底
        Map<String, CallMapping> merged = new LinkedHashMap<>(BUILTIN_DEFAULTS);
        if (callMappings != null) {
            merged.putAll(callMappings);  // yml 覆盖内置
        }
        this.callMappings = merged;
        this.prefixRules = buildPrefixRules(merged);
    }

    /**
     * 返回 callName 对应的装饰器集合。匹配优先级：精确 key > 最长前缀规则 > empty。
     * <p>注意：精确匹配只查 yml 配置（不含内置默认），前缀匹配查合并后的（含内置默认）。
     */
    public CallDecorators get(String callName) {
        // 1. 精确匹配（只查 yml 显式配置的精确 key）
        if (callMappings != null) {
            // 从原始 yml 配置查精确 key（排除 * 结尾的）
            // 注意：callMappings 现在是合并后的，精确 key 也在里面
            CallMapping exact = callMappings.get(callName);
            if (exact != null) {
                return decorate(exact);
            }
        }
        // 2. 前缀匹配（含内置默认）
        for (PrefixEntry rule : prefixRules) {
            if (callName.startsWith(rule.prefix)) {
                return decorate(rule.mapping);
            }
        }
        return CallDecorators.empty();
    }

    private CallDecorators decorate(CallMapping m) {
        return new CallDecorators(
                m.rateLimiter != null ? rateLimiterRegistry.rateLimiter(m.rateLimiter) : null,
                m.retry != null ? retryRegistry.retry(m.retry) : null,
                m.timelimiter != null ? timeLimiterRegistry.timeLimiter(m.timelimiter) : null,
                m.circuitbreaker != null ? circuitBreakerRegistry.circuitBreaker(m.circuitbreaker) : null
        );
    }

    /** 扫描合并后的 mappings，把以 * 结尾的 key 拆成前缀规则，按前缀长度降序排列。 */
    private static List<PrefixEntry> buildPrefixRules(Map<String, CallMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return List.of();
        }
        List<PrefixEntry> rules = new ArrayList<>();
        for (Map.Entry<String, CallMapping> entry : mappings.entrySet()) {
            String key = entry.getKey();
            if (key != null && key.endsWith("*")) {
                rules.add(new PrefixEntry(key.substring(0, key.length() - 1), entry.getValue()));
            }
        }
        rules.sort(Comparator.comparingInt((PrefixEntry e) -> e.prefix.length()).reversed());
        return List.copyOf(rules);
    }

    private record PrefixEntry(String prefix, CallMapping mapping) {
    }

    public static class CallMapping {
        private String retry;
        private String timelimiter;
        private String circuitbreaker;
        private String rateLimiter;

        public String getRetry() {
            return retry;
        }

        public void setRetry(String retry) {
            this.retry = retry;
        }

        public String getTimelimiter() {
            return timelimiter;
        }

        public void setTimelimiter(String timelimiter) {
            this.timelimiter = timelimiter;
        }

        public String getCircuitbreaker() {
            return circuitbreaker;
        }

        public void setCircuitbreaker(String circuitbreaker) {
            this.circuitbreaker = circuitbreaker;
        }

        public String getRateLimiter() {
            return rateLimiter;
        }

        public void setRateLimiter(String rateLimiter) {
            this.rateLimiter = rateLimiter;
        }
    }
}
