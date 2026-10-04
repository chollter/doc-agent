package com.gcll.docagent.llm;

import com.gcll.docagent.llm.context.ContextWindowManager;
import com.gcll.docagent.llm.routing.ModelRouter;
import com.gcll.docagent.persistence.entity.LlmInteractionEntity;
import com.gcll.docagent.persistence.mapper.LlmInteractionMapper;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import com.gcll.docagent.resilience.LlmResponse;
import com.gcll.docagent.resilience.NonRetryableCallException;
import com.gcll.docagent.resilience.RetryableCallException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
 *   <li><b>管线路径 {@link #invoke(String, String, String, String)}</b>：按 callName 经
 *       {@link ModelRouter} 路由到对应模型。<b>2026-09-18 起默认无记忆（单轮）</b>——
 *       此前 runId 兼作 conversationId，管线单轮调用也会累积巨型对话历史，
 *       击穿模型输入上限（39105 &gt; 30720 → 400 InvalidParameter）。需要记忆的调用方
 *       显式用 {@link #invoke(String, String, String, String, boolean)} 传 withMemory=true。</li>
 *   <li><b>流式路径 {@link #invokeStream(String, String, String, String, Consumer)}</b>：
 *       逐块聚合内容并回调增量，供 SSE 实时输出。runId 仅作留痕关联与心跳，不参与记忆。</li>
 * </ul>
 * <p>两条路径并存，逐步把调用方从老路径迁到新路径。迁移期间功能等价（新路径不挂 advisor 时
 * 与老路径行为一致）。
 * <p><b>心跳与超时（2026-09-18 事故修复）</b>：每次调用前推进 run 的 updated_at（心跳）——
 * 防 stale 重排在长 LLM 调用执行中误触发（僵尸双执行）；流式调用按“两个数据块之间的最大
 * 间隔”限时（readTimeoutSeconds），防连接僵死无限挂起。
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
    private final LlmInteractionMapper interactionMapper;
    private final ObjectProvider<AgentRunRepository> agentRunRepositoryProvider;
    /** 流式调用两个数据块之间的最大间隔（秒），防连接僵死无限挂起。 */
    private final long readTimeoutSeconds;

    public LlmGateway(ChatClient.Builder chatClientBuilder,
                      ModelRouter modelRouter,
                      ContextWindowManager contextWindowManager,
                      LlmInteractionMapper interactionMapper,
                      ObjectProvider<AgentRunRepository> agentRunRepositoryProvider,
                      @Value("${docagent.llm.read-timeout-seconds:180}") long readTimeoutSeconds) throws IOException {
        this.chatClient = chatClientBuilder.build();
        this.systemBasePrompt = new ClassPathResource("prompts/system-base.txt")
                .getContentAsString(StandardCharsets.UTF_8);
        this.modelRouter = modelRouter;
        this.contextWindowManager = contextWindowManager;
        this.interactionMapper = interactionMapper;
        this.agentRunRepositoryProvider = agentRunRepositoryProvider;
        this.readTimeoutSeconds = readTimeoutSeconds;
    }

    /**
     * 老路径：单 ChatClient、无记忆、单轮。<b>保留不动</b>，供未迁移调用方使用。
     *
     * <p>迁移完成后所有调用方应改用 {@link #invoke(String, String, String, String)}。
     */
    public LlmResponse invoke(String promptFile, String userContent) {
        return doInvoke(chatClient, promptFile, userContent, null, null, false);
    }

    /**
     * 管线路径：按 callName 路由模型，<b>无记忆（单轮）</b>。
     *
     * <p>2026-09-18 起默认不挂记忆 advisor：runId 仅作留痕关联与心跳。
     * 需要跨调用记忆的调用方显式用 5 参重载。
     *
     * @param callName    调用点标识（如 {@code llm.root-cause}），用于 {@link ModelRouter} 选模型
     * @param promptFile  classpath:prompts/ 下的提示词文件名
     * @param userContent 用户工单内容
     * @param runId       工单运行 ID，作留痕关联 + 心跳；null 表示无 run 上下文
     * @return LLM 响应（内容 + prompt/completion token；token 缺失记 0）
     */
    public LlmResponse invoke(String callName, String promptFile, String userContent, String runId) {
        ChatClient routed = modelRouter.clientFor(callName);
        return doInvoke(routed, promptFile, userContent, runId, callName, false);
    }

    /**
     * 带记忆开关的重载：withMemory=true 时按 runId 设 conversationId，挂记忆 advisor 隔离对话历史。
     * 仅追问等多轮对话场景使用；管线单轮调用一律走 4 参版本（防历史膨胀击穿输入上限）。
     */
    public LlmResponse invoke(String callName, String promptFile, String userContent,
                              String runId, boolean withMemory) {
        ChatClient routed = modelRouter.clientFor(callName);
        return doInvoke(routed, promptFile, userContent, runId, callName, withMemory);
    }

    /**
     * 流式路径：逐块聚合内容，每个增量回调 onDelta（供 SSE 实时输出）。
     *
     * <p>超时语义：两个数据块之间超过 readTimeoutSeconds 即失败（与阻塞调用的读超时对齐），
     * 防连接僵死无限挂起。异常分类与 doInvoke 一致。
     *
     * @param onDelta 增量回调（null 则不回调，仍聚合返回完整内容）
     */
    public LlmResponse invokeStream(String callName, String promptFile, String userContent,
                                    String runId, Consumer<String> onDelta) {
        String fullPrompt = null;
        try {
            heartbeat(runId);
            ChatClient routed = modelRouter.clientFor(callName);
            String promptTemplate = loadPrompt(promptFile);
            String safeContent = truncateByModelWindow(promptTemplate, userContent, callName);
            fullPrompt = promptTemplate + "\n\n简历内容：\n" + safeContent;
            StringBuilder content = new StringBuilder();
            AtomicReference<String> modelRef = new AtomicReference<>();
            AtomicReference<Usage> usageRef = new AtomicReference<>();
            routed.prompt().system(systemBasePrompt).user(fullPrompt)
                    .stream().chatResponse()
                    .timeout(Duration.ofSeconds(readTimeoutSeconds))
                    .doOnNext(chunkResponse -> {
                        String delta = extractDelta(chunkResponse);
                        if (delta != null && !delta.isEmpty()) {
                            content.append(delta);
                            if (onDelta != null) {
                                onDelta.accept(delta);
                            }
                        }
                        ChatResponseMetadata metadata = chunkResponse.getMetadata();
                        if (metadata != null) {
                            if (metadata.getModel() != null) {
                                modelRef.set(metadata.getModel());
                            }
                            if (metadata.getUsage() != null) {
                                usageRef.set(metadata.getUsage());
                            }
                        }
                    })
                    .blockLast();
            Usage usage = usageRef.get();
            int promptTokens = promptTokensOf(usage);
            int completionTokens = completionTokensOf(usage);
            recordInteraction(runId, promptFile, modelRef.get(), promptTokens, completionTokens,
                    fullPrompt, content.toString(), true);
            return LlmResponse.of(content.toString(), promptTokens, completionTokens, modelRef.get());
        } catch (IOException ex) {
            recordInteraction(runId, promptFile, null, 0, 0, null, "ERROR: " + ex.getMessage(), false);
            throw new NonRetryableCallException("Failed to load prompt: " + promptFile, ex);
        } catch (NonRetryableCallException | RetryableCallException ex) {
            recordInteraction(runId, promptFile, null, 0, 0, fullPrompt, "ERROR: " + ex.getMessage(), false);
            throw ex;
        } catch (Exception ex) {
            recordInteraction(runId, promptFile, null, 0, 0, fullPrompt, "ERROR: " + ex.getMessage(), false);
            log.debug("LLM invokeStream failed, classified as retryable, error={}", ex.getMessage());
            throw new RetryableCallException("LLM stream call failed", ex);
        }
    }

    /**
     * 统一执行：加载 prompt、拼系统提示、按是否带记忆选调用方式、解析 token。
     *
     * @param client      路由后的 ChatClient（已挂 advisor 或无）
     * @param promptFile  提示词文件
     * @param userContent 工单内容
     * @param runId       run 标识（留痕 + 心跳）；withMemory=true 时兼作 conversationId
     * @param callName    调用点标识（查模型窗口截断用）；null 走老路径固定字数截断
     * @param withMemory  true 时挂记忆 advisor（多轮对话）；管线单轮调用一律 false
     */
    private LlmResponse doInvoke(ChatClient client, String promptFile, String userContent,
                                 String runId, String callName, boolean withMemory) {
        String fullPrompt = null;
        try {
            heartbeat(runId);
            String promptTemplate = loadPrompt(promptFile);
            // 优化1：按目标模型窗口动态截断 userContent，而非固定字数。
            // 剩余空间 = 模型窗口 - 系统提示已用 - prompt模板已用 - 安全余量(给输出和误差留)
            // 2026-09-18 修复：此处曾误传 runId（UUID），windowFor(uuid) 永远 miss 回退默认小窗口
            String safeContent = truncateByModelWindow(promptTemplate, userContent, callName);
            fullPrompt = promptTemplate + "\n\n简历内容：\n" + safeContent;
            ChatClient.ChatClientRequestSpec request = client.prompt()
                    .system(systemBasePrompt)
                    .user(fullPrompt);
            if (withMemory && runId != null) {
                // 设 conversationId：advisor 据此隔离各工单对话历史
                request = request.advisors(spec -> spec.param(MEMORY_CONVERSATION_ID_KEY, runId));
            }
            ChatResponse chatResponse = request.call().chatResponse();
            String content = chatResponse.getResult().getOutput().getText();
            // 阶段4：透传实际模型名（来自响应 metadata），支撑 token 指标按 model 分维
            ChatResponseMetadata metadata = chatResponse.getMetadata();
            String model = metadata != null ? metadata.getModel() : null;
            Usage usage = metadata != null ? metadata.getUsage() : null;
            int promptTokens = promptTokensOf(usage);
            int completionTokens = completionTokensOf(usage);
            // 记录 LLM 交互日志（优化证据链）
            recordInteraction(runId, promptFile, model, promptTokens, completionTokens,
                    fullPrompt, content, true);
            return LlmResponse.of(content, promptTokens, completionTokens, model);
        } catch (IOException ex) {
            // prompt 文件加载失败 = 确定性错误，不可重试（此时 fullPrompt 尚未拼出，仍留痕）
            recordInteraction(runId, promptFile, null, 0, 0, null, "ERROR: " + ex.getMessage(), false);
            throw new NonRetryableCallException("Failed to load prompt: " + promptFile, ex);
        } catch (NonRetryableCallException | RetryableCallException ex) {
            // 失败调用同样留痕：链路诊断时能看到“哪里断了、为什么断”
            recordInteraction(runId, promptFile, null, 0, 0, fullPrompt, "ERROR: " + ex.getMessage(), false);
            throw ex; // 已分类，透传
        } catch (Exception ex) {
            recordInteraction(runId, promptFile, null, 0, 0, fullPrompt, "ERROR: " + ex.getMessage(), false);
            // 其他异常默认按可重试处理（网络抖动 / 5xx / 超时等）
            log.debug("LLM invoke failed, classified as retryable, error={}", ex.getMessage());
            throw new RetryableCallException("LLM call failed", ex);
        }
    }

    /** 心跳：长 LLM 调用前推进 run 的 updated_at，防 stale 重排在执行中误触发（僵尸双执行）。失败静默——诊断路径不阻塞主流程。 */
    private void heartbeat(String runId) {
        if (runId == null || agentRunRepositoryProvider == null) {
            return;
        }
        try {
            AgentRunRepository repository = agentRunRepositoryProvider.getIfAvailable();
            if (repository != null) {
                repository.touch(runId);
            }
        } catch (Exception ex) {
            log.debug("Heartbeat failed, runId={}: {}", runId, ex.getMessage());
        }
    }

    /** 从流式块中提取增量文本；首末块（无内容/只有元数据）返回 null。 */
    private static String extractDelta(ChatResponse chunk) {
        if (chunk == null || chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return null;
        }
        String text = chunk.getResult().getOutput().getText();
        return text == null ? null : text;
    }

    private static int promptTokensOf(Usage usage) {
        return usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : 0;
    }

    private static int completionTokensOf(Usage usage) {
        return usage != null && usage.getCompletionTokens() != null ? usage.getCompletionTokens().intValue() : 0;
    }

    /**
     * 记录 LLM 交互日志——用于构建优化证据链。
     * 失败不影响主流程。
     */
    private void recordInteraction(String runId, String promptFile, String model,
                                    int promptTokens, int completionTokens,
                                    String fullPrompt, String fullResponse, boolean success) {
        try {
            LlmInteractionEntity entity = new LlmInteractionEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setRunId(runId != null ? runId : "no-run");
            entity.setCallSite(deriveCallSite(promptFile));
            entity.setModel(model);
            entity.setPromptTokens(promptTokens);
            entity.setCompletionTokens(completionTokens);
            entity.setFullPrompt(fullPrompt);
            entity.setFullResponse(fullResponse);
            entity.setSuccess(success);
            entity.setCreatedAt(LocalDateTime.now());
            interactionMapper.insert(entity);
        } catch (Exception ex) {
            log.debug("Failed to record LLM interaction: {}", ex.getMessage());
        }
    }

    /** 从 prompt 文件名推导调用点标识。 */
    private static String deriveCallSite(String promptFile) {
        if (promptFile == null) return "UNKNOWN";
        if (promptFile.contains("entity-extract")) return "ENTITY_EXTRACT";
        if (promptFile.contains("review-react") || promptFile.contains("analysis-react")) return "REACT";
        if (promptFile.contains("review") || promptFile.contains("analysis")) return "DIRECT_LLM";
        return promptFile.replace(".txt", "").toUpperCase();
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
        int availableForContent = budgetForTemplate(callName, promptTemplate);
        if (availableForContent <= 0) {
            // 极端情况：系统提示+模板已撑满窗口，用保守固定截断
            log.warn("No token budget left for userContent (callName={}), fallback to char truncate", callName);
            return contextWindowManager.truncate(userContent);
        }
        return contextWindowManager.truncate(userContent, availableForContent);
    }

    /**
     * 公开装配预算：调用点按目标模型窗口还能装多少 user 内容 token。
     *
     * <p>与 {@link #truncateByModelWindow} 终防线同一公式（窗口 − 系统提示 − prompt 模板 − 安全余量），
     * 供 {@link com.gcll.docagent.llm.context.ContextAssembler} 调用点<b>先按预算装配并记账</b>——
     * 溢出在装配层被折叠（FOLDED，进快照账本），而不是到这里被字符级静默截断。
     * 装配到位后本类的终线截断即为 no-op，仍保留作兜底。
     *
     * @param callName   调用点标识（查模型窗口）
     * @param promptFile classpath:prompts/ 下的模板文件名
     * @return 剩余可用 token 预算；≤0 表示窗口已满或加载失败（调用方应回退不设限/兜底路径）
     */
    public int budgetForCall(String callName, String promptFile) {
        try {
            return budgetForTemplate(callName, loadPrompt(promptFile));
        } catch (IOException ex) {
            log.warn("budgetForCall failed to load prompt {}, caller falls back to unlimited: {}",
                    promptFile, ex.getMessage());
            return -1;
        }
    }

    private int budgetForTemplate(String callName, String promptTemplate) {
        int modelWindow = modelRouter.windowFor(callName);
        int systemTokens = contextWindowManager.estimateTokens(systemBasePrompt);
        int promptTokens = contextWindowManager.estimateTokens(promptTemplate);
        // 安全余量：留给 completion 输出 + jtokkit 估算误差。取窗口的 25%（至少 1024）
        int safetyMargin = Math.max(1024, modelWindow / 4);
        return modelWindow - systemTokens - promptTokens - safetyMargin;
    }
}
