package com.gcll.docagent.langchain4j;

import com.gcll.docagent.analysis.SkillDefinition;
import com.gcll.docagent.observability.trace.TraceRecorder;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.observability.api.event.AiServiceResponseReceivedEvent;
import dev.langchain4j.observability.api.event.ToolExecutedEvent;
import dev.langchain4j.observability.api.listener.AiServiceResponseReceivedListener;
import dev.langchain4j.observability.api.listener.ToolExecutedEventListener;
import dev.langchain4j.service.AiServices;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按技能装配 SkillAssistant 的工厂——Runtime 的"技能插拔"落点：
 * 同一模型、同一监听器、同一上下文机制，只有工具集随技能变化。
 * 实例按技能名缓存（AiService 线程安全，可跨 run 复用）。
 */
@Component
public class SkillAssistantFactory {

    /** ReAct 最大工具调用轮次——防止模型无限读文档 */
    static final int MAX_TOOL_CALLING_ROUNDS = 8;

    private final OpenAiChatModel chatModel;
    private final DocumentToolProviders.Outline outlineProvider;
    private final DocumentToolProviders.ReadSection readProvider;
    private final DocumentToolProviders.Search searchProvider;
    private final DocumentToolProviders.ExportReport exportProvider;

    private final Map<String, SkillAssistant> cache = new ConcurrentHashMap<>();

    public SkillAssistantFactory(OpenAiChatModel chatModel,
                                 DocumentToolProviders.Outline outlineProvider,
                                 DocumentToolProviders.ReadSection readProvider,
                                 DocumentToolProviders.Search searchProvider,
                                 DocumentToolProviders.ExportReport exportProvider) {
        this.chatModel = chatModel;
        this.outlineProvider = outlineProvider;
        this.readProvider = readProvider;
        this.searchProvider = searchProvider;
        this.exportProvider = exportProvider;
    }

    public SkillAssistant assistantFor(SkillDefinition skill) {
        return cache.computeIfAbsent(skill.name(), name -> build(skill));
    }

    private SkillAssistant build(SkillDefinition skill) {
        Object[] providers = skill.toolNames().stream().map(this::providerByName).toArray();
        return AiServices.builder(SkillAssistant.class)
                .chatModel(chatModel)
                .tools(providers)
                .systemMessageProvider(args -> ReActContextHolder.getSystemPrompt())
                .maxToolCallingRoundTrips(MAX_TOOL_CALLING_ROUNDS)
                .registerListeners(new ReActToolExecutedListener(), new ReActResponseReceivedListener())
                .build();
    }

    private Object providerByName(String toolName) {
        return switch (toolName) {
            case "get_document_outline" -> outlineProvider;
            case "read_section" -> readProvider;
            case "search_document" -> searchProvider;
            case "export_report" -> exportProvider;
            default -> throw new IllegalArgumentException("Skill 引用了未知工具: " + toolName);
        };
    }

    // --- 步级 Trace 双 Listener（与 ToolProvider 委派 Trace 互补：对比 LLM 意图与实际执行） ---

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
            tracer.end(stepId, TraceRecorder.fingerprint("result", event.resultText()), null);
        }
    }

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
