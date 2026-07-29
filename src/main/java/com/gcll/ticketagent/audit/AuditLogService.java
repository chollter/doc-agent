package com.gcll.ticketagent.audit;

import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.observability.trace.TraceRecorderFactory;
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
}
