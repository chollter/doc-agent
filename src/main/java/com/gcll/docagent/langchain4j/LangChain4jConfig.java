package com.gcll.docagent.langchain4j;

import com.gcll.docagent.observability.trace.TraceRecorder;
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
 * LangChain4j 配置——文档分析 ReAct 循环的底层支撑。
 *
 * <h3>与 Spring AI Alibaba 共存</h3>
 * Spring AI 管流程化调用（直连 LLM 降级路径），LangChain4j 管 ReAct 自主推理+工具迭代。
 * 两者通过 OpenAI 兼容协议接入同一个 DashScope 模型，互不冲突。
 *
 * <h3>步级 Trace：双 Listener</h3>
 * <ul>
 *   <li>{@code ReActToolExecutedListener}——每次工具执行后记录 LLM 原始请求（工具名+参数JSON）与返回</li>
 *   <li>{@code ReActResponseReceivedListener}——每轮 LLM 响应记录摘要（是否含工具调用）</li>
 * </ul>
 * 与 {@code AnalysisToolProvider.delegate()} 记录的合并参数 Trace 互补，可对比 LLM 意图与实际执行。
 */
@Configuration
public class LangChain4jConfig {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jConfig.class);

    /** ReAct 最大工具调用轮次——防止模型无限读文档 */
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

    @Bean
    public DocumentAnalysisAssistant documentAnalysisAssistant(
            OpenAiChatModel chatModel, AnalysisToolProvider toolProvider) {
        return AiServices.builder(DocumentAnalysisAssistant.class)
                .chatModel(chatModel)
                .tools(toolProvider)
                .systemMessageProvider(args -> ReActContextHolder.getSystemPrompt())
                .maxToolCallingRoundTrips(MAX_TOOL_CALLING_ROUNDS)
                .registerListeners(new ReActToolExecutedListener(), new ReActResponseReceivedListener())
                .build();
    }

    /** 工具执行监听——记录 LLM 原始生成的工具请求与返回结果。 */
    static class ReActToolExecutedListener implements ToolExecutedEventListener {
        @Override
        public void onEvent(ToolExecutedEvent event) {
            TraceRecorder tracer = ReActContextHolder.getTracer();
            String parentStepId = ReActContextHolder.getParentStepId();
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

    /** LLM 响应监听——每轮推理记录一次摘要（ReAct 循环会触发多次）。 */
    static class ReActResponseReceivedListener implements AiServiceResponseReceivedListener {
        @Override
        public void onEvent(AiServiceResponseReceivedEvent event) {
            TraceRecorder tracer = ReActContextHolder.getTracer();
            String parentStepId = ReActContextHolder.getParentStepId();
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
