package com.gcll.docagent.analysis;

import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.llm.context.ContextWindowManager;
import com.gcll.docagent.llm.routing.ModelRouter;
import com.gcll.docagent.persistence.mapper.LlmInteractionMapper;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import com.gcll.docagent.resilience.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-09-18 事故修复的回归锁：
 * 1) 截断窗口必须按 callName 查（此前误传 runId，窗口查找永远 miss）；
 * 2) 管线 4 参 invoke 默认单轮无记忆（此前 runId 兼作 conversationId，历史膨胀击穿 30720 输入上限）；
 * 3) 每次 LLM 调用前 touch 心跳，防 stale 重排在执行中误触发（僵尸双执行）；
 * 4) invokeStream 聚合增量并逐块回调。
 */
@ExtendWith(MockitoExtension.class)
class LlmGatewayStreamAndMemoryTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ChatClient.CallResponseSpec callSpec;
    @Mock
    private ChatClient.StreamResponseSpec streamSpec;
    @Mock
    private ModelRouter modelRouter;
    @Mock
    private ContextWindowManager contextWindowManager;
    @Mock
    private LlmInteractionMapper interactionMapper;
    @Mock
    private AgentRunRepository agentRunRepository;

    private LlmGateway llmGateway;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        ObjectProvider<AgentRunRepository> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(agentRunRepository);
        lenient().when(contextWindowManager.truncate(anyString(), anyInt()))
                .thenAnswer(inv -> inv.getArgument(0));
        llmGateway = new LlmGateway(chatClientBuilder, modelRouter, contextWindowManager,
                interactionMapper, provider, 180);
    }

    private void stubBlockingCall() {
        when(modelRouter.clientFor(anyString())).thenReturn(chatClient);
        lenient().when(modelRouter.windowFor(anyString())).thenReturn(8192);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        // withMemory=true 路径会链式调 advisors 后继续用返回值，需回 this；
        // lenient：stateless 用例不触发（advisors 从不被调），strict 模式下免 unnecessary-stubbing 报错
        lenient().when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.chatResponse()).thenReturn(chunk("ok"));
    }

    @Test
    void invokeLooksUpWindowByCallNameNotRunId() {
        stubBlockingCall();

        llmGateway.invoke("llm.entity-extract", "resume-entity-extract.txt", "内容", "doc-run-1");

        verify(modelRouter).windowFor("llm.entity-extract");
        verify(modelRouter, never()).windowFor("doc-run-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fourArgInvokeIsStatelessAndHeartbeats() {
        stubBlockingCall();

        llmGateway.invoke("llm.entity-extract", "resume-entity-extract.txt", "内容", "doc-run-1");

        verify(requestSpec, never()).advisors(any(Consumer.class));
        verify(agentRunRepository).touch("doc-run-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void withMemoryInvokeAttachesConversationAdvisor() {
        stubBlockingCall();

        llmGateway.invoke("llm.entity-extract", "resume-entity-extract.txt", "内容", "doc-run-1", true);

        verify(requestSpec).advisors(any(Consumer.class));
    }

    @Test
    void invokeStreamAggregatesDeltasAndCallsBack() {
        when(modelRouter.clientFor(anyString())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.chatResponse()).thenReturn(Flux.just(chunk("你好"), chunk("，世界")));

        List<String> deltas = new ArrayList<>();
        LlmResponse response = llmGateway.invokeStream(
                "llm.resume-review", "resume-review.txt", "内容", "run-1", deltas::add);

        assertThat(response.content()).isEqualTo("你好，世界");
        assertThat(deltas).containsExactly("你好", "，世界");
        verify(agentRunRepository).touch("run-1");
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
