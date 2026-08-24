package com.gcll.docagent.observability.trace;

import com.gcll.docagent.agent.AgentStepEventPublisher;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.persistence.repository.AgentStepRepository;
import io.micrometer.tracing.Tracer;
import org.springframework.stereotype.Component;

/**
 * TraceRecorder 工厂——每个 AgentRun 创建独立的 TraceRecorder 实例。
 * <p>
 * 为什么用工厂而非单例？
 * <ul>
 *   <li>每个工单有独立的 span 序号计数器</li>
 *   <li>ThreadLocal 的 parentStepId 按工单隔离</li>
 *   <li>避免并发工单互相污染 span 计数</li>
 * </ul>
 */
@Component
public class TraceRecorderFactory {

    private final AgentStepRepository stepRepository;
    private final AgentStepEventPublisher eventPublisher;
    private final Tracer otelTracer;

    public TraceRecorderFactory(AgentStepRepository stepRepository,
                                AgentStepEventPublisher eventPublisher,
                                Tracer otelTracer) {
        this.stepRepository = stepRepository;
        this.eventPublisher = eventPublisher;
        this.otelTracer = otelTracer;
    }

    public TraceRecorder create(AgentRun run) {
        return new TraceRecorder(run, stepRepository, eventPublisher, otelTracer);
    }
}
