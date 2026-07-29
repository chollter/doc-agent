package com.gcll.ticketagent.observability.trace;

import com.gcll.ticketagent.agent.AgentStepEventPublisher;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.persistence.repository.AgentStepRepository;
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

    public TraceRecorderFactory(AgentStepRepository stepRepository,
                                AgentStepEventPublisher eventPublisher) {
        this.stepRepository = stepRepository;
        this.eventPublisher = eventPublisher;
    }

    public TraceRecorder create(AgentRun run) {
        return new TraceRecorder(run, stepRepository, eventPublisher);
    }
}
