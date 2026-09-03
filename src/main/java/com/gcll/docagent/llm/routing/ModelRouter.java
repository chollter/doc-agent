package com.gcll.docagent.llm.routing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多模型路由：按 callName 选模型，按模型拿 ChatClient。
 *
 * <p>实现"简单环节用快模型（qwen-turbo，省 token）、复杂环节用强模型（qwen-plus，保效果）"。
 * callName → 模型名的映射来自 {@link ModelRoutingProperties}（配置化，可调）。
 *
 * <p><b>匹配规则</b>：先精确匹配 callName，再按前缀（最长前缀优先）匹配，
 * 都不中用 defaultModel 兜底。这和 {@code CallRegistry} 的治理映射前缀规则一致，
 * 让新增 callName 零配置即可走兜底模型。
 *
 * <p><b>ChatClient 缓存</b>：每个模型名对应一个 ChatClient，构造一次缓存复用
 * （ChatClient 无状态、可并发用）。模型名 → ChatClient 的映射在首次请求时惰性创建。
 *
 * <p><b>降级</b>：某模型对应的 ChatModel 构造失败时，回退到 defaultModel 的 ChatClient，
 * 保证路由失败不阻塞业务调用（和规则兜底主线一致：LLM 不可靠→兜底）。
 */
public class ModelRouter {

    private static final Logger log = LoggerFactory.getLogger(ModelRouter.class);

    private final ModelRoutingProperties properties;
    private final ChatClientFactory chatClientFactory;

    /** 模型名 → ChatClient 缓存。惰性创建，构造失败记 null 表示该模型不可用。 */
    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    public ModelRouter(ModelRoutingProperties properties, ChatClientFactory chatClientFactory) {
        this.properties = properties;
        this.chatClientFactory = chatClientFactory;
    }

    /**
     * 按 callName 解析模型名（精确 > 最长前缀 > default）。
     *
     * @param callName 调用点标识，如 {@code llm.root-cause}
     * @return 模型名，永不返回 null（兜底 defaultModel）
     */
    public String resolveModel(String callName) {
        if (callName == null || callName.isBlank()) {
            return properties.getDefaultModel();
        }
        // 0. 简单任务快路径：simpleCalls 里的 callName 用 simpleModel（省成本）
        if (properties.getSimpleCalls() != null && properties.getSimpleCalls().contains(callName)) {
            return properties.getSimpleModel();
        }
        Map<String, String> mappings = properties.getMappings();
        // 1. 精确匹配（mappings 显式配置覆盖 simpleCalls，优先级最高）
        String exact = mappings.get(callName);
        if (exact != null && !exact.isBlank()) {
            return exact;
        }
        // 2. 最长前缀匹配（mappings 里以 callName 为前缀的 key，如 "llm.*" 或 "llm.root"）
        String bestPrefix = null;
        for (String key : mappings.keySet()) {
            if (isPrefixKey(key) && callName.startsWith(stripWildcard(key))) {
                String stripped = stripWildcard(key);
                if (bestPrefix == null || stripped.length() > bestPrefix.length()) {
                    bestPrefix = stripped;
                }
            }
        }
        if (bestPrefix != null) {
            String mapped = mappings.get(bestPrefix + ".*");
            if (mapped != null) {
                return mapped;
            }
        }
        // 3. 兜底
        return properties.getDefaultModel();
    }

    /**
     * 按 callName 解析目标模型的上下文窗口（token），供 truncate 按模型窗口动态截断。
     *
     * <p>先 resolveModel 拿模型名，再查 {@link ModelRoutingProperties#getModelWindows()}。
     * 未配置的模型用 {@link ModelRoutingProperties#getDefaultModelWindow()} 兜底（保守，按小窗口算更安全）。
     *
     * @param callName 调用点标识；null 时用 defaultModel 的窗口
     * @return 模型上下文窗口（token）
     */
    public int windowFor(String callName) {
        String model = resolveModel(callName);
        Integer window = properties.getModelWindows().get(model);
        return window != null ? window : properties.getDefaultModelWindow();
    }

    /**
     * 按 callName 拿 ChatClient（内部先解析模型，再查/建对应 ChatClient）。
     *
     * @param callName 调用点标识
     * @return ChatClient；若指定模型不可用则回退 defaultModel 的 ChatClient
     */
    public ChatClient clientFor(String callName) {
        String model = resolveModel(callName);
        return clientByModel(model);
    }

    /** 拿 defaultModel 对应的 ChatClient（兜底用）。 */
    public ChatClient defaultClient() {
        return clientByModel(properties.getDefaultModel());
    }

    private ChatClient clientByModel(String model) {
        return clientCache.computeIfAbsent(model, m -> {
            try {
                return chatClientFactory.create(m);
            } catch (Exception ex) {
                // 该模型构造失败（如配置错、缺模型权限）→ 回退 defaultModel
                if (!m.equals(properties.getDefaultModel())) {
                    log.warn("ChatClient for model '{}' construction failed, fallback to default '{}': {}",
                            m, properties.getDefaultModel(), ex.getMessage());
                    return clientByModel(properties.getDefaultModel());
                }
                // defaultModel 自己都失败 → 抛出，让上层 LlmGateway 走规则降级
                throw new IllegalStateException("Failed to build default ChatClient for model: " + m, ex);
            }
        });
    }

    private boolean isPrefixKey(String key) {
        return key != null && key.endsWith(".*");
    }

    private String stripWildcard(String key) {
        return key.substring(0, key.length() - 2);
    }

    /**
     * ChatClient 工厂：由 {@code ChatClientConfig} 实现，按模型名建对应 ChatClient
     * （内含 ChatModel 构造 + Advisor 装配）。
     */
    @FunctionalInterface
    public interface ChatClientFactory {
        /**
         * 按模型名建一个 ChatClient。
         *
         * @param model 模型名，如 {@code qwen-turbo}
         * @return 装配好 Advisor 的 ChatClient
         */
        ChatClient create(String model);
    }
}
