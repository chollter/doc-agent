package com.gcll.docagent.llm;

import com.gcll.docagent.llm.context.ContextWindowManager;
import com.gcll.docagent.llm.routing.ModelRouter;
import com.gcll.docagent.resilience.LlmResponse;
import com.gcll.docagent.resilience.NonRetryableCallException;
import com.gcll.docagent.resilience.RetryableCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * LLM 纯执行器：负责 prompt 加载、系统提示拼装、结构化调用与 token 解析。
 * <p><b>不含任何治理逻辑</b>（重试 / 超时 / 熔断）——治理由调用方经
 * {@link com.gcll.docagent.resilience.ExternalCallGateway}（推荐通过
 * {@link com.gcll.docagent.resilience.LlmCallExecutor}）包装。
 *
 * <h3>异常分类契约</h3>
 * 底层异常在本类翻译为 Resilience4j 可识别的两类：
 * <ul>
 *   <li>{@link NonRetryableCallException}：prompt 文件加载失败等确定性错误，不重试</li>
 *   <li>{@link RetryableCallException}：其他异常（网络抖动 / 5xx / 超时等），默认可重试</li>
 * </ul>
 *
 * <h3>双调用路径（阶段1 多模型路由 + 记忆）</h3>
 * <ul>
 *   <li><b>老路径 {@link #invoke(String, String)}</b>：单 ChatClient、无记忆、单轮。保留不动，
 *       供尚未迁移的调用方使用，保证编译通过。</li>
 *   <li><b>新路径 {@link #invoke(String, String, String, String)}</b>：按 callName 经
 *       {@link ModelRouter} 路由到对应模型，按 runId 设 conversationId 隔离工单记忆。
 *       迁移完成的调用方用这条。</li>
 * </ul>
 * <p>两条路径并存，逐步把调用方从老路径迁到新路径。迁移期间功能等价（新路径不挂 advisor 时
 * 与老路径行为一致）。
 */
@Service
@ConditionalOnBean(ChatClient.Builder.class)
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    /** conversationId 作为 advisor 参数的 key（MessageChatMemoryAdvisor 约定）。 */
    private static final String MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    private final ChatClient chatClient;
    private final String systemBasePrompt;
    private final ModelRouter modelRouter;
    private final ContextWindowManager contextWindowManager;

    public LlmGateway(ChatClient.Builder chatClientBuilder,
                      ModelRouter modelRouter,
                      ContextWindowManager contextWindowManager) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.systemBasePrompt = new ClassPathResource("prompts/system-base.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        this.modelRouter = modelRouter;
        this.contextWindowManager = contextWindowManager;
    }

    /**
     * 老路径：单 ChatClient、无记忆、单轮。<b>保留不动</b>，供未迁移调用方使用。
     *
     * <p>迁移完成后所有调用方应改用 {@link #invoke(String, String, String, String)}。
     */
    public LlmResponse invoke(String promptFile, String userContent) {
        return doInvoke(chatClient, promptFile, userContent, null);
    }

    /**
     * 新路径：按 callName 路由模型，按 runId 隔离记忆。
     *
     * @param callName    调用点标识（如 {@code llm.root-cause}），用于 {@link ModelRouter} 选模型
     * @param promptFile  classpath:prompts/ 下的提示词文件名
     * @param userContent 用户工单内容
     * @param runId       工单运行 ID，作为记忆 conversationId；null 表示无记忆（单轮）
     * @return LLM 响应（内容 + prompt/completion token；token 缺失记 0）
     */
    public LlmResponse invoke(String callName, String promptFile, String userContent, String runId) {
        ChatClient routed = modelRouter.clientFor(callName);
        return doInvoke(routed, promptFile, userContent, runId);
    }

    /**
     * 统一执行：加载 prompt、拼系统提示、按是否带记忆选调用方式、解析 token。
     *
     * @param client      路由后的 ChatClient（已挂 advisor 或无）
     * @param promptFile  提示词文件
     * @param userContent 工单内容
     * @param runId       非 null 时设 conversationId 启用记忆；null 走无 advisor 路径
     */
    private LlmResponse doInvoke(ChatClient client, String promptFile, String userContent, String runId) {
        try {
            String promptTemplate = loadPrompt(promptFile);
            // 优化1：按目标模型窗口动态截断 userContent，而非固定字数。
            // 剩余空间 = 模型窗口 - 系统提示已用 - prompt模板已用 - 安全余量(给输出和误差留)
            String safeContent = truncateByModelWindow(promptTemplate, userContent, runId);
            ChatClient.ChatClientRequestSpec request = client.prompt()
                    .system(systemBasePrompt)
                    .user(promptTemplate + "\n\n工单内容：\n" + safeContent);
            if (runId != null) {
                // 设 conversationId：advisor 据此隔离各工单对话历史
                request = request.advisors(spec -> spec.param(MEMORY_CONVERSATION_ID_KEY, runId));
            }
            ChatResponse chatResponse = request.call().chatResponse();
            String content = chatResponse.getResult().getOutput().getText();
            // 阶段4：透传实际模型名（来自响应 metadata），支撑 token 指标按 model 分维
            String model = chatResponse.getMetadata().getModel();
            Usage usage = chatResponse.getMetadata().getUsage();
            int promptTokens = usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : 0;
            int completionTokens = usage.getCompletionTokens() != null ? usage.getCompletionTokens().intValue() : 0;
            return LlmResponse.of(content, promptTokens, completionTokens, model);
        } catch (IOException ex) {
            // prompt 文件加载失败 = 确定性错误，不可重试
            throw new NonRetryableCallException("Failed to load prompt: " + promptFile, ex);
        } catch (NonRetryableCallException | RetryableCallException ex) {
            throw ex; // 已分类，透传
        } catch (Exception ex) {
            // 其他异常默认按可重试处理（网络抖动 / 5xx / 超时等）
            log.debug("LLM invoke failed, classified as retryable, error={}", ex.getMessage());
            throw new RetryableCallException("LLM call failed", ex);
        }
    }

    private String loadPrompt(String promptFile) throws IOException {
        return new ClassPathResource("prompts/" + promptFile).getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 按目标模型窗口动态截断 userContent（优化1）。
     *
     * <p>剩余 token = 模型窗口 - 系统提示 - prompt模板 - 安全余量。
     * 系统提示和模板是固定开销，必须预留；安全余量给输出 token + jtokkit 对 qwen 的估算误差。
     *
     * @param promptTemplate prompt 模板文本（已加载）
     * @param userContent    工单原文
     * @param callName       调用点标识（用于查模型窗口）；null 走老路径固定字数截断
     */
    private String truncateByModelWindow(String promptTemplate, String userContent, String callName) {
        if (callName == null) {
            // 老路径（invoke(promptFile,content)）无 callName，退回固定字数截断（向后兼容）
            return contextWindowManager.truncate(userContent);
        }
        int modelWindow = modelRouter.windowFor(callName);
        int systemTokens = contextWindowManager.estimateTokens(systemBasePrompt);
        int promptTokens = contextWindowManager.estimateTokens(promptTemplate);
        // 安全余量：留给 completion 输出 + jtokkit 估算误差。取窗口的 25%（至少 1024）
        int safetyMargin = Math.max(1024, modelWindow / 4);
        int availableForContent = modelWindow - systemTokens - promptTokens - safetyMargin;
        if (availableForContent <= 0) {
            // 极端情况：系统提示+模板已撑满窗口，用保守固定截断
            log.warn("No token budget left for userContent (window={}, system={}, prompt={}), fallback to char truncate",
                    modelWindow, systemTokens, promptTokens);
            return contextWindowManager.truncate(userContent);
        }
        return contextWindowManager.truncate(userContent, availableForContent);
    }
}
