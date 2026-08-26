package com.gcll.docagent.loop;

import com.gcll.docagent.langchain4j.LangChainToolDelegator;
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.parsing.ParsedDocument;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 自研内核的单元测试——脚本化假 ChatModel 驱动状态机，
 * 覆盖：正常循环、预算三重硬顶、观察片段收集、checkpoint 持久化与断点续跑。
 */
class AgentLoopTest {

    private final LangChainToolDelegator delegator = mock(LangChainToolDelegator.class);

    private LoopToolSpecs toolSpecs() {
        when(delegator.delegate(eq("read_section"), anyMap())).thenReturn("【工具返回】节内容");
        return new LoopToolSpecs(delegator, new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /** 可脚本化的假模型：按队列依次出队响应；记录每次请求的消息列表。 */
    static class ScriptedModel implements ChatModel {
        final ArrayDeque<ChatResponse> script = new ArrayDeque<>();
        final List<List<dev.langchain4j.data.message.ChatMessage>> requests = new ArrayList<>();

        ChatResponse respond(AiMessage ai, Integer totalTokens) {
            return ChatResponse.builder().aiMessage(ai)
                    .metadata(ChatResponseMetadata.builder()
                            .tokenUsage(totalTokens == null ? null : new TokenUsage(10, 10, totalTokens))
                            .build())
                    .build();
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            requests.add(request.messages());
            return script.pop();
        }
    }

    /** 内存版 checkpoint 存储。 */
    static class InMemoryStore extends LoopCheckpointStore {
        final Map<String, LoopState> saved = new java.util.concurrent.ConcurrentHashMap<>();

        InMemoryStore() {
            super(null, null);
        }

        @Override
        public void save(String runId, LoopState state) {
            saved.put(runId, state);
        }

        @Override
        public Optional<LoopState> find(String runId) {
            return Optional.ofNullable(saved.get(runId));
        }

        @Override
        public void delete(String runId) {
            saved.remove(runId);
        }
    }

    private AgentLoop newLoop(ScriptedModel model, InMemoryStore store) {
        return new AgentLoop(model, toolSpecs(), store, 10, 16, 60000);
    }

    private AgentLoop.LoopContext ctx(String runId) {
        return new AgentLoop.LoopContext(runId, "document-analysis",
                List.of("get_document_outline", "read_section"), "system-prompt", "user-msg",
                new ParsedDocument("t.md", "markdown", List.of(new DocSection("sec-1", "H", "body", null))),
                null, null, null);
    }

    @Test
    void completesAfterToolRoundWithObservationsAndCheckpoint() {
        ScriptedModel model = new ScriptedModel();
        model.script.add(model.respond(AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("c1").name("read_section").arguments("{\"sectionId\":\"sec-1\"}").build()))
                .build(), 100));
        model.script.add(model.respond(AiMessage.builder().text("{\"summary\":\"done\"}").build(), 100));
        InMemoryStore store = new InMemoryStore();

        AgentLoop.LoopResult result = newLoop(model, store).run(ctx("r1"));

        assertThat(result.success()).isTrue();
        assertThat(result.finalAnswer()).contains("done");
        assertThat(result.toolCalls()).isEqualTo(1);
        assertThat(result.tokensUsed()).isEqualTo(200);
        assertThat(result.observations()).hasSize(1);
        assertThat(result.observations().get(0).observation()).contains("节内容");
        // checkpoint：终态已保存，消息含 system/user/assistant/tool/assistant
        LoopState saved = store.find("r1").orElseThrow();
        assertThat(saved.messages()).hasSize(5);
        assertThat(saved.document()).isNotNull();
    }

    @Test
    void stopsOnRoundsBudgetAndKeepsFragmentsForFallback() {
        ScriptedModel model = new ScriptedModel();
        for (int i = 0; i < 5; i++) {
            model.script.add(model.respond(AiMessage.builder()
                    .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                            .id("c" + i).name("read_section").arguments("{\"sectionId\":\"sec-1\"}").build()))
                    .build(), 100));
        }
        InMemoryStore store = new InMemoryStore();
        AgentLoop tight = new AgentLoop(model, toolSpecs(), store, 2, 16, 60000);

        AgentLoop.LoopResult result = tight.run(ctx("r2"));

        assertThat(result.success()).isFalse();
        assertThat(result.stopReason()).isEqualTo("BUDGET_ROUNDS");
        assertThat(result.observations()).hasSize(2);
    }

    @Test
    void stopsOnTokenBudget() {
        ScriptedModel model = new ScriptedModel();
        model.script.add(model.respond(AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("c1").name("read_section").arguments("{}").build()))
                .build(), 90000));
        InMemoryStore store = new InMemoryStore();
        AgentLoop tight = new AgentLoop(model, toolSpecs(), store, 10, 16, 60000);

        AgentLoop.LoopResult result = tight.run(ctx("r3"));

        assertThat(result.success()).isFalse();
        assertThat(result.stopReason()).isEqualTo("BUDGET_TOKENS");
        assertThat(result.tokensUsed()).isEqualTo(90000);
    }

    @Test
    void resumeContinuesFromCheckpointHistory() {
        ScriptedModel model = new ScriptedModel();
        model.script.add(model.respond(AiMessage.builder()
                .toolExecutionRequests(List.of(ToolExecutionRequest.builder()
                        .id("c1").name("read_section").arguments("{\"sectionId\":\"sec-1\"}").build()))
                .build(), 100));
        model.script.add(model.respond(AiMessage.builder().text("{\"summary\":\"resumed\"}").build(), 100));
        InMemoryStore store = new InMemoryStore();
        AgentLoop.LoopContext context = ctx("r4");

        // 第一段：模拟崩溃——只给一轮响应就中断（用小轮次预算制造停止）
        AgentLoop half = new AgentLoop(model, toolSpecs(), store, 1, 16, 60000);
        AgentLoop.LoopResult first = half.run(context);
        assertThat(first.success()).isFalse();

        // 第二段：从 checkpoint 恢复，模型收到的历史必须包含此前的工具结果消息
        AgentLoop.LoopResult resumed = newLoop(model, store)
                .run(new AgentLoop.LoopContext(context.runId(), context.skillName(), context.toolNames(),
                        context.systemPrompt(), context.userMessage(), context.document(),
                        context.tracer(), context.parentStepId(), store.find("r4").orElseThrow()));

        assertThat(resumed.success()).isTrue();
        // 恢复后模型的最后一次请求包含此前的工具结果消息（历史被完整带回）
        List<dev.langchain4j.data.message.ChatMessage> lastRequest = model.requests.get(model.requests.size() - 1);
        assertThat(lastRequest.toString()).contains("节内容");
    }
}
