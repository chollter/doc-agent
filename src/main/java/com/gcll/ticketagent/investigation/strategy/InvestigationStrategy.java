package com.gcll.ticketagent.investigation.strategy;

import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.triage.TriageResult;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.investigation.InvestigationResult;

/**
 * 排查策略接口
 * 
 * 每个策略实现特定的排查逻辑，根据分诊结果选择合适的策略。
 */
public interface InvestigationStrategy {

    /**
     * 策略名称
     */
    String getName();

    /**
     * 策略类型
     */
    InvestigationStrategyType getType();

    /**
     * 是否支持给定的分诊结果
     */
    boolean supports(TriageResult triageResult);

    /**
     * 执行排查策略
     * 
     * @param run Agent 运行实例
     * @param triageResult 分诊结果
     * @param tracer 追踪记录器
     * @return 排查结果
     */
    InvestigationResult execute(AgentRun run, TriageResult triageResult, TraceRecorder tracer);

    /**
     * 策略优先级（数值越小优先级越高）
     */
    default int getPriority() {
        return 0;
    }
}