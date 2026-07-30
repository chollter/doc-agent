package com.gcll.ticketagent.langchain4j;

import com.gcll.ticketagent.observability.trace.TraceRecorder;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import dev.langchain4j.observability.api.listener.ToolExecutedEventListener;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j 配置——ReAct 推理循环的底层支撑。
 *
 * <h3>与 Spring AI Alibaba 共存策略</h3>
 * Spring AI 管分诊/抽取/建议等流程调用（ChatClient + dashscope starter），
 * LangChain4j 管 ReAct 自主推理+工具迭代（AiService + @Tool）。
 * 两者通过 OpenAI 兼容协议接入同一个 dashscope 模型，互不冲突：
 * <ul>
 *   <li>Spring AI：spring.ai.dashscope.* 配置，自动注入 ChatClient.Builder</li>
 *   <li>LangChain4j：此处手动构建 OpenAiChatModel，baseUrl 指向阿里云百炼 OpenAI 兼容端点</li>
 * </ul>
 *
 * <h3>模型配置</h3>
 * ReAct 循环需要强推理能力，默认用 qwen-plus（与 Spring AI 默认模型一致）。
 * 可通过环境变量 LANGCHAIN4J_MODEL 覆盖。
 */
@Configuration
public class LangChain4jConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jConfig.class);

    /** ReAct 最大工具调用轮次——每轮可调多个工具，防止无限循环 */
    private static final int MAX_TOOL_CALLING_ROUNDS = 8;

    @Bean
    public OpenAiChatModel langChain4jChatModel(
            @Value("${spring.ai.dashscope.chat.options.model:${LLM_MODEL:qwen-plus}}") String model,
            @Value("${spring.ai.dashscope.api-key:${LLM_API_KEY:}}") String apiKey
    ) {
        return OpenAiChatModel.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .apiKey(apiKey)
                .modelName(model)
                .timeout(Duration.ofSeconds(90))
                .temperature(0.3)
                .maxRetries(1)
                .build();
    }

    /**
     * ReAct AiService——LangChain4j 的类型安全 Agent 接口。
     * AiService 自动处理：工具注册、ReAct 循环、步数限制。
     *
     * <p>系统提示词通过 systemMessageProvider 动态注入（含工单上下文），
     * 每次调用前由 {@link ReActInvestigationStrategy} 设置到 {@link ReActContextHolder}。
     *
     * <h3>步级 Trace：双 Listener</h3>
     * 注册两个监听器实现完整步级 Trace：
     * <ul>
     *   <li>{@link ReActToolExecutedListener}——每次工具执行后触发，
     *       记录 LLM 原始 ToolExecutionRequest（name + arguments JSON）和返回结果。
     *       与 ReActToolProvider.delegate() 中的 Trace 互补——后者记录合并后参数。</li>
     *   <li>{@link ReActResponseReceivedListener}——每次 LLM 响应时触发，
     *       记录响应摘要（是否有 tool call、tool call 数量）。</li>
     * </ul>
     * 两个 Listener 都通过 {@link ReActTraceHolder} 获取当前线程的 TraceRecorder + parentStepId。
     */
    @Bean
    public ReActAssistant reActAssistant(OpenAiChatModel chatModel, ReActToolProvider toolProvider) {
        return AiServices.builder(ReActAssistant.class)
                .chatModel(chatModel)
                .tools(toolProvider)
                .systemMessageProvider(args -> ReActContextHolder.getSystemPrompt())
                .maxToolCallingRoundTrips(MAX_TOOL_CALLING_ROUNDS)
                .registerListeners(new ReActToolExecutedListener(), new ReActResponseReceivedListener())
                .build();
    }

    // --- 步级 Trace Listener ---

    /**
     * 工具执行监听器——每次 @Tool 方法被 LLM 调用后触发。
     * <p>
     * 记录 LLM 生成的原始 ToolExecutionRequest（name + arguments JSON）和工具返回结果。
     * 这与 ReActToolProvider.delegate() 中的步级 Trace 互补：
     * <ul>
     *   <li>delegate() 记录的是 <b>合并后</b>的参数（argMerger.merge 后）</li>
     *   <li>这里记录的是 <b>LLM 原始生成</b>的参数（未经 merge）</li>
     * </ul>
     * 用于审计：对比 LLM 意图与实际执行参数的差异。
     */
    static class ReActToolExecutedListener implements ToolExecutedEventListener {
        @Override
        public void onEvent(ToolExecutedEvent event) {
            TraceRecorder tracer = ReActTraceHolder.getTracer();
            String parentStepId = ReActTraceHolder.getParentStepId();
            if (tracer == null || parentStepId == null) {
                return;
            }

            String toolName = event.request() != null ? event.request().name() : "unknown";
            String stepId = tracer.begin("REACT_TOOL_TRACE: " + toolName, parentStepId);
            tracer.recordMeta(stepId, false, toolName);

            String argsJson = event.request() != null ? event.request().arguments() : "{}";
            tracer.recordInput(stepId, TraceRecorder.fingerprint("llm_args", argsJson));

            String resultText = event.resultText();
            tracer.end(stepId, TraceRecorder.fingerprint("result", resultText), null);
        }
    }

    /**
     * LLM 响应监听器——每次 LLM 生成响应时触发（包括 ReAct 循环中的每一轮推理）。
     * <p>
     * 记录 LLM 响应摘要（是否有 tool call、tool call 数量），用于审计 LLM 的推理过程。
     * 一个完整的 ReAct 循环会触发多次此事件（每轮推理一次）。
     */
    static class ReActResponseReceivedListener implements AiServiceResponseReceivedListener {
        @Override
        public void onEvent(AiServiceResponseReceivedEvent event) {
            TraceRecorder tracer = ReActTraceHolder.getTracer();
            String parentStepId = ReActTraceHolder.getParentStepId();
            if (tracer == null || parentStepId == null) {
                return;
            }

            String stepId = tracer.begin("REACT_LLM_RESPONSE", parentStepId);
            tracer.recordMeta(stepId, true, "LangChain4j");

            String responseSummary;
            if (event.response() != null && event.response().aiMessage() != null) {
                var aiMsg = event.response().aiMessage();
                boolean hasToolCall = aiMsg.hasToolExecutionRequests();
                int toolCallCount = hasToolCall ? aiMsg.toolExecutionRequests().size() : 0;
                responseSummary = "hasToolCall=" + hasToolCall + ",toolCallCount=" + toolCallCount;
            } else {
                responseSummary = "response=<null>";
            }
            tracer.end(stepId, responseSummary, null);
        }
    }
}
