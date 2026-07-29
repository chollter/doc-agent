package com.gcll.ticketagent.observability.trace;

import com.gcll.ticketagent.agent.AgentStepEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 桥接 TraceRecorder 的内部接口到现有的 AgentStepEventPublisher。
 * <p>
 * TraceRecorder 定义了自己的 AgentStepEventPublisher 内部接口以保持解耦，
 * 这个 Bridge 把它适配到 Spring 管理的 AgentStepEventPublisher Bean。
 */
@Component
public class StepEventPublisherBridge implements TraceRecorder.AgentStepEventPublisher {

    private final AgentStepEventPublisher delegate;

    public StepEventPublisherBridge(AgentStepEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(String runId, String stepName, String status, String message) {
        delegate.publish(runId, stepName, status, message);
    }
}
