package com.gcll.ticketagent.audit;

import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.observability.trace.TraceRecorderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审计日志服务——统一入口。
 * <p>
 * v2 统一到 TraceRecorder（树形结构 + parentStepId + spanId）。
 * 所有业务层通过本服务创建 TraceRecorder 并调用 begin/end，
 * 不再直接操作 AgentStepRepository。
 * <p>
 * 使用方式：
 * <pre>
 *   TraceRecorder tracer = auditLogService.createTracer(run);
 *   String stepId = tracer.begin("TICKET_EXTRACT");
 *   // ... do work ...
 *   tracer.end(stepId, summary, null);
 * </pre>
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final TraceRecorderFactory traceRecorderFactory;

    public AuditLogService(TraceRecorderFactory traceRecorderFactory) {
        this.traceRecorderFactory = traceRecorderFactory;
    }

    /**
     * 为一个工单创建独立的 TraceRecorder。
     * 每个工单有独立的 span 序号 + parentStepId 隔离。
     */
    public TraceRecorder createTracer(AgentRun run) {
        return traceRecorderFactory.create(run);
    }

    /**
     * v1 兼容：一步式记录（平铺，无 parentStepId）。
     * <p>
     * 仅用于尚未迁移到 begin/end 模式的旧调用方。
     * 新代码必须用 createTracer + begin/end。
     *
     * @deprecated 使用 createTracer + begin/end 替代
     */
    @Deprecated
    public void recordStep(AgentRun run, AgentStepName stepName,
                           String inputSnapshot, String outputSnapshot,
                           boolean llmUsed, String toolUsed,
                           long costMs, String errorMessage) {
        TraceRecorder tracer = traceRecorderFactory.create(run);
        String stepId = tracer.begin(stepName.name());
        tracer.recordInput(stepId, truncate(inputSnapshot));
        tracer.recordMeta(stepId, llmUsed, toolUsed);
        tracer.end(stepId, truncate(outputSnapshot), errorMessage);
    }

    private String truncate(String text) {
        if (text == null) return null;
        if (text.length() <= 2000) return text;
        log.warn("Snapshot exceeds 2000 chars ({} chars), truncating", text.length());
        return text.substring(0, 500) + "...(truncated, " + text.length() + " chars)";
    }
}
