package com.gcll.docagent.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.gcll.docagent.llm.context.SummarizingChatMemoryAdvisor;
import com.gcll.docagent.llm.routing.ModelRouter;
import com.gcll.docagent.llm.routing.ModelRoutingProperties;
import com.gcll.docagent.resilience.LlmCallExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * ChatClient / ChatMemory / 多模型路由装配。
 *
 * <h3>背景与兼容性</h3>
 * 项目依赖 spring-ai-alibaba-starter 1.0.0-M6.1（DashScope），但 spring-ai BOM 是 1.0.0 GA。
 * 两者版本混用：DashScopeChatModel(M6.1) 实现了 GA 的 {@link ChatModel} 接口，
 * 可挂到 GA 的 {@link ChatClient} 与 {@link MessageChatMemoryAdvisor} 上。
 *
 * <h3>装配内容</h3>
 * <ul>
 *   <li>{@link #chatClientBuilder}：保留原有的单 ChatClient.Builder bean（老 LlmGateway 用），
 *       <b>不动</b>，保证现有代码不破坏。</li>
 *   <li>{@link #chatMemory()}：工单级短期记忆，{@link MessageWindowChatMemory} 默认窗口大小由配置控制。</li>
 *   <li>{@link #chatClientFactory}：按模型名建 DashScopeChatModel + 挂 advisor 的工厂，
 *       供 {@link ModelRouter} 惰性创建各模型 ChatClient。</li>
 *   <li>{@link #modelRouter}：路由器本身，注入到 LlmGateway 的新重载方法。</li>
 * </ul>
 *
 * <p><b>降级</b>：当 {@code opsmind.llm.routing.chat-memory.enabled=false} 时，不挂记忆 advisor，
 * ChatClient 退化为无状态（等同现状）。这是为了出问题能一键回退。
 */
@Configuration
@EnableConfigurationProperties(ModelRoutingProperties.class)
public class ChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatClientConfig.class);

    /** 系统 base prompt（与 LlmGateway 同一份，advisor 路径下复用）。 */
    public static final String SYSTEM_BASE_PROMPT_FILE = "prompts/system-base.txt";

    /**
     * 原有 ChatClient.Builder bean —— <b>保留不动</b>。
     *
     * <p>背景：spring-ai-alibaba-starter 1.0.0-M6.1 的 DashScopeAutoConfiguration 与
     * Spring AI 原生 ChatClientAutoConfiguration 存在 bean 注册时序问题，{@code ChatClient.Builder}
     * 虽 condition matched 但未被实例化，导致依赖它的 LlmGateway 不创建。
     * 在此显式从已注册的 {@link ChatModel} 构建，绕开 autoconfig 时序问题。
     * 升级到 GA 后可移除。
     *
     * <p>此 bean 仅供老的 {@code LlmGateway.invoke(promptFile, userContent)} 使用，
     * 新的多模型路由走 {@link ModelRouter}，不经过这个 builder。
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatClient.Builder chatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    /**
     * 工单级短期记忆。{@link MessageWindowChatMemory} 按 conversationId 隔离各工单（runId），
     * 保留最近 N 条消息。N 由 {@code opsmind.llm.routing.chat-memory.history-size} 控制。
     *
     * <p>注意：MessageWindowChatMemory 本身是"按 conversationId 取/存"的存储抽象之上的窗口策略。
     * 这里用其默认实现（内部 InMemory）；生产可换 JdbcChatMemoryRepository 持久化。
     */
    @Bean
    @ConditionalOnMissingBean
    public ChatMemory chatMemory(ModelRoutingProperties properties) {
        return MessageWindowChatMemory.builder()
                .maxMessages(properties.getChatMemory().getHistorySize())
                .build();
    }

    /**
     * 加载系统 base prompt（advisor 装配需要，ChatClient.defaultSystem 用）。
     * 与 LlmGateway 加载同一文件，保证两条路径系统提示一致。
     */
    @Bean("systemBasePrompt")
    public String systemBasePrompt() throws IOException {
        return new ClassPathResource(SYSTEM_BASE_PROMPT_FILE)
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * ChatClient 工厂：按模型名建 DashScopeChatModel，挂上 ChatMemory advisor，产出 ChatClient。
     *
     * <p>每个模型一个 ChatModel + ChatClient（{@link ModelRouter} 缓存复用）。
     * {@code systemBasePrompt} 是无状态 String bean，不参与循环依赖，直接注入即可
     * （曾误加 {@code @Lazy}，但 Spring 无法对 final 的 String 生成 CGLIB 代理，导致启动失败）。
     *
     * <p><b>HTTP 超时（2026-09-18 事故修复）</b>：JDK HttpClient 默认无读超时，
     * 连接僵死时线程无限阻塞（单次调用挂 15 分钟，触发 stale 重排双执行）。
     * 这里显式建带超时的 RestClient 交给 DashScopeApi——阻塞调用读超时；
     * 流式调用的“块间间隔”超时由 {@code LlmGateway.invokeStream} 的 Flux.timeout 兑现。
     *
     * <p><b>兼容性风险点</b>（本地验证重点）：
     * M6.1 的 DashScopeChatModel 挂 GA 的 MessageChatMemoryAdvisor 是否运行时兼容，
     * 需在本地真跑一次确认。若 advisor 不生效或抛类转换异常，关闭 chat-memory.enabled
     * 即退化为无 advisor 的 ChatClient（功能不丢，只是无记忆）。
     */
    @Bean
    public ModelRouter.ChatClientFactory chatClientFactory(
            @Value("${spring.ai.dashscope.api-key:}") String apiKey,
            @Value("${docagent.llm.connect-timeout-seconds:10}") long connectTimeoutSeconds,
            @Value("${docagent.llm.read-timeout-seconds:180}") long readTimeoutSeconds,
            ChatMemory chatMemory,
            ModelRoutingProperties properties,
            LlmCallExecutor llmCallExecutor,
            String systemBasePrompt) {
        ModelRoutingProperties.ChatMemory mem = properties.getChatMemory();
        return model -> {
            // 1. 复用单个 DashScopeApi（apiKey 共用），只换 options.model；
            //    显式装配超时（JDK HttpClient 默认无读超时，会无限挂起）
            java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(connectTimeoutSeconds))
                    .build();
            org.springframework.http.client.JdkClientHttpRequestFactory requestFactory =
                    new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
            requestFactory.setReadTimeout(java.time.Duration.ofSeconds(readTimeoutSeconds));
            org.springframework.web.client.RestClient.Builder restClientBuilder =
                    org.springframework.web.client.RestClient.builder().requestFactory(requestFactory);
            // M6.1 无 builder()，用 4+1 参构造（与官方 2 参构造同源：默认错误处理器 + 自定义超时 RestClient）
            DashScopeApi api = new DashScopeApi(
                    "https://dashscope.aliyuncs.com",
                    apiKey,
                    restClientBuilder,
                    org.springframework.web.reactive.function.client.WebClient.builder(),
                    org.springframework.ai.retry.RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
            DashScopeChatOptions options = DashScopeChatOptions.builder()
                    .withModel(model)
                    .build();
            DashScopeChatModel chatModel = new DashScopeChatModel(api, options);

            // 2. 建 ChatClient，挂系统提示 + 记忆 advisor（按开关）
            ChatClient.Builder builder = ChatClient.builder(chatModel)
                    .defaultSystem(systemBasePrompt);
            if (mem.isEnabled()) {
                if (mem.isSummarize()) {
                    // 阶段 A：摘要式记忆 advisor（替代 MessageChatMemoryAdvisor，完全接管记忆）
                    SummarizingChatMemoryAdvisor advisor = new SummarizingChatMemoryAdvisor(
                            chatMemory, llmCallExecutor,
                            mem.getContextWindowTokens(),
                            mem.getSummarizeThresholdRatio(),
                            mem.getKeepRecentMessages());
                    builder.defaultAdvisors(advisor);
                    log.info("ChatClient[{}] uses SummarizingChatMemoryAdvisor (window={}, thresholdRatio={}, keep={})",
                            model, mem.getContextWindowTokens(), mem.getSummarizeThresholdRatio(), mem.getKeepRecentMessages());
                } else {
                    // 降级：按条数裁剪（退化为原行为）
                    MessageChatMemoryAdvisor advisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
                    builder.defaultAdvisors(advisor);
                }
            }
            return builder.build();
        };
    }

    /**
     * 多模型路由器。注入属性 + 工厂，由 {@code LlmGateway} 新重载方法调用。
     */
    @Bean
    @ConditionalOnMissingBean
    public ModelRouter modelRouter(ModelRoutingProperties properties,
                                   ModelRouter.ChatClientFactory chatClientFactory) {
        log.info("ModelRouter initialized, defaultModel={}, mappings={}, chatMemory.enabled={}",
                properties.getDefaultModel(), properties.getMappings(),
                properties.getChatMemory().isEnabled());
        return new ModelRouter(properties, chatClientFactory);
    }
}
