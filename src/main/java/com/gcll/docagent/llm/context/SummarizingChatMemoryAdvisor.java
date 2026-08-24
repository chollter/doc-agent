package com.gcll.docagent.llm.context;

import com.gcll.docagent.resilience.CallResult;
import com.gcll.docagent.resilience.LlmCallExecutor;
import com.gcll.docagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.CallAroundAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘要式对话记忆 advisor（阶段 A：长上下文管理深化）。
 *
 * <p>替代 {@link org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor}，完全接管
 * 工单级记忆：请求前读历史 → 判断 token 超阈值 → LLM 摘要旧消息（保留最近 N 条原文）→
 * 响应后存本轮。实现 DeepSeek Harness 第一支柱"长上下文压缩"，而非简单截断。
 *
 * <h3>为什么完全接管而不是挂第二个 advisor</h3>
 * MessageChatMemoryAdvisor 不可继承（且若两个 advisor 都读写 ChatMemory 会互相打架）。
 * 故本 advisor 继承 {@link AbstractChatMemoryAdvisor}，自己负责记忆存取 + 摘要，单一职责集中一处。
 *
 * <h3>降级策略（呼应项目主线：LLM 不可靠→工程兜底）</h3>
 * 摘要本身是一次 LLM 调用（callName=llm.context-summarize，走 LlmCallExecutor 治理）。
 * 摘要失败/熔断时降级为"窗口裁剪"（保留最近 N 条，丢更旧的）——退化为 MessageWindowChatMemory
 * 的行为，不会比现状更差。三道防线：jtokkit 阈值判断 → LLM 摘要 → 失败裁剪兜底。
 *
 * <h3>token 精度说明</h3>
 * 用 {@link JTokkitTokenCountEstimator}（cl100k，偏 OpenAI 词表）对通义 qwen 有 10-20% 误差。
 * 故摘要触发阈值设为窗口的 {@code summarizeThresholdRatio}（默认 0.65），留 35% 余量吸收误差
 * + 安全垫。
 *
 * <p><b>注意</b>：本类基于 spring-ai-core 1.0.0-M6 的 advisor API（AdvisedRequest/aroundCall），
 * 项目因 spring-ai-alibaba-starter 1.0.0-M6.1 传递依赖了 M6 的 spring-ai-core，advisor 接口
 * 是老版本（非 GA 的 before/after）。
 */
public class SummarizingChatMemoryAdvisor extends AbstractChatMemoryAdvisor<ChatMemory> {

    private static final Logger log = LoggerFactory.getLogger(SummarizingChatMemoryAdvisor.class);

    private final LlmCallExecutor summarizer;          // 调 LLM 做摘要
    private final TokenCountEstimator tokenEstimator;  // jtokkit token 估算
    private final long contextWindowTokens;            // 目标模型窗口（如 qwen-plus 28000）
    private final double summarizeThresholdRatio;      // 触发摘要的比例（默认 0.65）
    private final int keepRecentMessages;              // 摘要时保留最近 N 条原文

    public SummarizingChatMemoryAdvisor(ChatMemory chatMemory,
                                        LlmCallExecutor summarizer,
                                        long contextWindowTokens,
                                        double summarizeThresholdRatio,
                                        int keepRecentMessages) {
        super(chatMemory);
        this.summarizer = summarizer;
        this.tokenEstimator = new JTokkitTokenCountEstimator();
        this.contextWindowTokens = contextWindowTokens;
        this.summarizeThresholdRatio = summarizeThresholdRatio;
        this.keepRecentMessages = keepRecentMessages;
    }

    /**
     * 核心钩子：请求前处理历史（读+摘要）→ 调下游 → 响应后存本轮。
     *
     * <p>流程：
     * <ol>
     *   <li>取 conversationId（来自 advisorParams，由 LlmGateway 设 runId）</li>
     *   <li>从 ChatMemory 读历史，超阈值则摘要旧消息，重建 messages</li>
     *   <li>chain.nextAroundCall(改后的 request) 得到响应</li>
     *   <li>把本轮 user（来自 request.userText）+ assistant（来自 response）存进 ChatMemory</li>
     * </ol>
     */
    @Override
    public AdvisedResponse aroundCall(AdvisedRequest request, CallAroundAdvisorChain chain) {
        String conversationId = doGetConversationId(request.adviseContext());

        // 无 conversationId（单轮调用，runId=null）→ 不做记忆处理，直接透传
        if (conversationId == null || DEFAULT_CHAT_MEMORY_CONVERSATION_ID.equals(conversationId)) {
            return chain.nextAroundCall(request);
        }

        // 1. 读历史（用反射兼容 M6/GA 两种 ChatMemory.get 签名——见 readHistory 注释）
        List<Message> history = readHistory(getChatMemoryStore(), conversationId);
        AdvisedRequest advisedRequest = request;
        if (history != null && !history.isEmpty()) {
            List<Message> managed = manageHistory(conversationId, history);
            // 把管理后的历史替换进 request.messages（保留原 userText 在末尾）
            List<Message> merged = new ArrayList<>(managed);
            // 注：request.messages() 是当前消息（含本轮 user），managed 是历史。
            // MessageChatMemoryAdvisor 的做法是把历史插到 user 前面。这里合并：历史 + 本轮消息
            merged.addAll(request.messages());
            advisedRequest = AdvisedRequest.from(request).messages(merged).build();
        }

        // 2. 调下游得到响应
        AdvisedResponse advisedResponse = chain.nextAroundCall(advisedRequest);

        // 3. 存本轮 user + assistant 到 ChatMemory
        persistTurn(conversationId, request, advisedResponse);

        return advisedResponse;
    }

    /**
     * 流式调用：暂不做摘要（流式下中间 token 难以聚合），直接透传。
     * 项目主流程用 aroundCall，流式仅在 SSE token 输出（阶段 D）启用，届时再扩展。
     */
    @Override
    public reactor.core.publisher.Flux<AdvisedResponse> aroundStream(
            AdvisedRequest request, org.springframework.ai.chat.client.advisor.api.StreamAroundAdvisorChain chain) {
        return chain.nextAroundStream(request);
    }

    /**
     * 核心算法：管理历史消息，超阈值则摘要旧消息、保留最近 N 条原文。
     * 失败时降级为窗口裁剪（保留最近 N 条）。
     *
     * <p>package-private 便于单测直接验证算法，不必构造复杂的 AdvisedRequest/Response。
     */
    List<Message> manageHistory(String conversationId, List<Message> history) {
        long threshold = (long) (contextWindowTokens * summarizeThresholdRatio);
        long tokens = estimateTokens(history);

        if (tokens <= threshold) {
            // 未超阈值，原样返回（不摘要，省一次 LLM 调用）
            return new ArrayList<>(history);
        }

        log.info("History exceeded threshold, summarizing: conversationId={}, tokens={}, threshold={}",
                conversationId, tokens, threshold);

        // 分割：保留最近 keepRecentMessages 条原文，其余待摘要
        int splitAt = Math.max(0, history.size() - keepRecentMessages);
        List<Message> toSummarize = new ArrayList<>(history.subList(0, splitAt));
        List<Message> recent = new ArrayList<>(history.subList(splitAt, history.size()));

        if (toSummarize.isEmpty()) {
            // 历史条数 ≤ keepRecent，没东西可摘要——降级裁剪（保留 recent 即可）
            return recent;
        }

        // 调 LLM 摘要旧消息；失败则降级裁剪
        try {
            String summary = summarize(toSummarize);
            // 摘要成功：summary system message + recent 原文
            List<Message> result = new ArrayList<>(recent.size() + 1);
            result.add(new SystemMessage("[此前对话历史摘要] " + summary));
            result.addAll(recent);
            log.info("History summarized ok: conversationId={}, summarizedMsgs={}, kept={}",
                    conversationId, toSummarize.size(), recent.size());
            return result;
        } catch (Exception ex) {
            // 降级：摘要失败，退化为窗口裁剪（保留 recent），不比现状差
            log.warn("Summarize failed, degrade to window trim: conversationId={}, error={}",
                    conversationId, ex.getMessage());
            return recent;
        }
    }

    /** 调 LlmCallExecutor 摘要一批旧消息。走 execute(单轮,不挂记忆,避免摘要触发摘要的递归)。 */
    private String summarize(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是之前排查工单的对话历史，请压缩成一段不超过 300 字的摘要，")
          .append("保留关键事实：涉及的系统/模块、已查的证据（日志/指标）、已排除的假设、当前倾向的根因。")
          .append("丢弃客套和重复内容。\n\n");
        for (Message m : messages) {
            sb.append("[").append(messageRole(m)).append("] ")
              .append(m.getText() == null ? "" : m.getText()).append("\n");
        }
        CallResult<LlmResponse> result = summarizer.execute(
                "llm.context-summarize", "context-summarize.txt", sb.toString());
        if (!result.success() || result.value() == null) {
            throw new RuntimeException("summarize call failed: "
                    + (result.circuitOpen() ? "circuit open" : "call error"));
        }
        return result.value().content();
    }

    /** 把本轮 user（request）+ assistant（response）存进 ChatMemory。失败不阻断主流程。 */
    private void persistTurn(String conversationId, AdvisedRequest request, AdvisedResponse response) {
        try {
            List<Message> toAdd = new ArrayList<>(2);
            // user：优先 request.userText（本轮用户输入）
            if (request.userText() != null && !request.userText().isBlank()) {
                toAdd.add(new UserMessage(request.userText()));
            }
            // assistant：从响应取
            if (response.response() != null && response.response().getResult() != null
                    && response.response().getResult().getOutput() != null) {
                toAdd.add(response.response().getResult().getOutput());
            }
            if (!toAdd.isEmpty()) {
                getChatMemoryStore().add(conversationId, toAdd);
            }
        } catch (Exception ex) {
            log.warn("Failed to persist turn to memory: conversationId={}, error={}",
                    conversationId, ex.getMessage());
        }
    }

    // ---- 辅助 ----

    /**
     * 反射读取对话历史，兼容 M6/GA 两种 ChatMemory.get 签名。
     *
     * <p>背景:项目 classpath 同时有 spring-ai-core 1.0.0-M6（ChatMemory.get(String,int)）
     * 和 spring-ai-model 1.0.0 GA（ChatMemory.get(String)），二者同名接口冲突。
     * MessageWindowChatMemory 是 GA 实现（只有单参 get），但编译期 ChatMemory 接口解析为
     * M6（双参 get），运行时双参调用会抛 AbstractMethodError。
     *
     * <p>解法:用反射按实际实例的方法签名调用——优先单参 get(String)（GA），
     * 退化双参 get(String,int)（M6）。这样无论运行时加载哪个版本的接口都能工作。
     */
    @SuppressWarnings("unchecked")
    private List<Message> readHistory(ChatMemory memory, String conversationId) {
        try {
            // 优先 GA 单参签名 get(String)
            try {
                java.lang.reflect.Method m = memory.getClass().getMethod("get", String.class);
                return (List<Message>) m.invoke(memory, conversationId);
            } catch (NoSuchMethodException gaMiss) {
                // 退化 M6 双参签名 get(String, int)
                java.lang.reflect.Method m = memory.getClass().getMethod("get", String.class, int.class);
                return (List<Message>) m.invoke(memory, conversationId, Integer.MAX_VALUE);
            }
        } catch (Exception ex) {
            log.warn("readHistory via reflection failed, treat as empty: conversationId={}, error={}",
                    conversationId, ex.getMessage());
            return List.of();
        }
    }

    private long estimateTokens(List<Message> messages) {
        long total = 0;
        for (Message m : messages) {
            String text = m.getText();
            if (text != null) {
                total += tokenEstimator.estimate(text);
            }
        }
        return total;
    }

    private String messageRole(Message m) {
        if (m instanceof UserMessage) return "user";
        if (m instanceof AssistantMessage) return "assistant";
        if (m instanceof SystemMessage) return "system";
        return m.getMessageType() == null ? "unknown" : m.getMessageType().name().toLowerCase();
    }
}
