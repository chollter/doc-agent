package com.gcll.docagent.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 2026-09-18 流式输出回归锁：token 增量事件（事件名 "token"，data 含 runId/stepId/delta）。
 */
@ExtendWith(MockitoExtension.class)
class AgentStepEventPublisherTest {

    @Mock
    private SseEmitter emitter;

    @Test
    void publishTokenSendsTokenEventToSubscribedEmitter() throws IOException {
        AgentStepEventPublisher publisher = new AgentStepEventPublisher();
        publisher.register("run-1", emitter);

        publisher.publishToken("run-1", "step-1", "你好");

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void blankDeltaIsNoOp() throws IOException {
        AgentStepEventPublisher publisher = new AgentStepEventPublisher();
        publisher.register("run-1", emitter);

        publisher.publishToken("run-1", "step-1", "");

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void publishWithoutSubscriberDoesNotThrow() {
        AgentStepEventPublisher publisher = new AgentStepEventPublisher();

        assertThatCode(() -> publisher.publishToken("run-x", "step-1", "增量内容"))
                .doesNotThrowAnyException();
    }
}
