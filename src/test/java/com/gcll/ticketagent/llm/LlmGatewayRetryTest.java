package com.gcll.ticketagent.llm;

import com.gcll.ticketagent.llm.context.ContextWindowManager;
import com.gcll.ticketagent.llm.routing.ModelRouter;
import com.gcll.ticketagent.resilience.RetryableCallException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * LlmGateway 异常分类单测。
 * <p>治理（重试/超时/熔断）由 {@link com.gcll.ticketagent.resilience.ExternalCallGateway} 负责，
 * 已在 {@code ExternalCallGatewayTest} 覆盖；本类只验证 {@code invoke()} 的异常翻译契约。
 */
@ExtendWith(MockitoExtension.class)
class LlmGatewayRetryTest {

    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock
    private ModelRouter modelRouter;
    @Mock
    private ContextWindowManager contextWindowManager;

    private LlmGateway llmGateway;

    @BeforeEach
    void setUp() throws Exception {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        // 阶段1/4：构造器新增 ModelRouter + ContextWindowManager 依赖；truncate 透传不截断
        lenient().when(contextWindowManager.truncate(anyString())).thenAnswer(inv -> inv.getArgument(0));
        llmGateway = new LlmGateway(chatClientBuilder, modelRouter, contextWindowManager);
    }

    @Test
    void invokeTranslatesGenericExceptionToRetryable() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("transient failure"));

        // invoke 把未分类的 RuntimeException 翻译为 RetryableCallException（默认可重试），
        // 由 ExternalCallGateway 的 retry 策略决定是否真正重试。
        assertThatThrownBy(() -> llmGateway.invoke("document-summary.txt", "test"))
                .isInstanceOf(RetryableCallException.class);
    }
}
