package com.gcll.docagent.observability.trace;

import com.gcll.docagent.agent.AgentStepEventPublisher;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentStep;
import com.gcll.docagent.persistence.repository.AgentStepRepository;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trace 记录器——树形 Trace 的核心，同时桥接 OTel 分布式追踪。
 * <p>
 * 双写机制：
 * <ul>
 *   <li>应用层：写入 agent_step 表（parentStepId 树形结构），供业务查询</li>
 *   <li>OTel 层：创建 Micrometer Tracing Span，与 Spring AI 自动生成的 LLM Span 串在同一个 traceId 下，
 *       导出到 Jaeger/Zipkin/SkyWalking 等后端</li>
 * </ul>
 *
 * <p>使用方式：
 * <pre>
 *   TraceRecorder tracer = traceRecorderFactory.create(run);
 *   String parentId = tracer.begin("TICKET_EXTRACT");
 *   // ... do work ...
 *   tracer.end(parentId, summary, null);  // null = success
 *
 *   // 子步骤：
 *   String childId = tracer.begin("TOOL_CALL", parentId);
 *   tracer.end(childId, toolSummary, null);
 * </pre>
 */
public class TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorder.class);

    private final AgentRun run;
    private final AgentStepRepository stepRepository;
    private final AgentStepEventPublisher eventPublisher;
    private final Tracer otelTracer;
    private final AtomicInteger spanSequence = new AtomicInteger(0);

    /** stepId → OTel Span，用于 end 时关闭对应的 Span */
    private final ConcurrentHashMap<String, io.micrometer.tracing.Span> otelSpans = new ConcurrentHashMap<>();

    /**
     * 当前线程的 parentStepId，支持嵌套 begin/end。
     * 用 ThreadLocal 而非显式传参，避免方法签名膨胀。
     */
    private final ThreadLocal<String> currentParentStepId = new ThreadLocal<>();

    public TraceRecorder(AgentRun run,
                          AgentStepRepository stepRepository,
                          AgentStepEventPublisher eventPublisher,
                          Tracer otelTracer) {
        this.run = run;
        this.stepRepository = stepRepository;
        this.eventPublisher = eventPublisher;
        this.otelTracer = otelTracer;
    }

    /**
     * 开始一个 Span。
     *
     * @param stepName 步骤名（如 TICKET_EXTRACT / TOOL_CALL / ROOT_CAUSE）
     * @return stepId，可作为子步骤的 parentStepId
     */
    public String begin(String stepName) {
        return begin(stepName, currentParentStepId.get());
    }

    /**
     * 开始一个 Span，显式指定父步骤。
     *
     * @param stepName       步骤名
     * @param parentStepId   父步骤 ID（null 表示顶层步骤）
     * @return stepId
     */
    public String begin(String stepName, String parentStepId) {
        String stepId = UUID.randomUUID().toString();
        String spanId = com.gcll.docagent.observability.trace.Span.generateSpanId(run.getTraceId(), spanSequence.incrementAndGet());
        Instant startedAt = Instant.now();

        // --- 应用层：写入 agent_step 表 ---
        AgentStep step = new AgentStep(
                stepId,
                run.getId(),
                parentStepId,
                stepName,
                "RUNNING",
                spanId,
                startedAt,
                startedAt
        );

        run.getSteps().add(step);
        stepRepository.save(step);
        currentParentStepId.set(stepId);

        // --- OTel 层：创建分布式追踪 Span ---
        io.micrometer.tracing.Span otelSpan = null;
        try {
            otelSpan = otelTracer.nextSpan()
                    .name("agent." + stepName.toLowerCase())
                    .tag("agent.runId", run.getId())
                    .tag("agent.stepName", stepName)
                    .tag("agent.spanId", spanId);
            if (parentStepId != null) {
                otelSpan.tag("agent.parentStepId", parentStepId);
            }
            otelSpan.start();
            otelSpans.put(stepId, otelSpan);
        } catch (Exception ex) {
            // OTel 不是关键路径，失败不影响业务
            log.debug("OTel span creation failed for step={}, ignoring: {}", stepName, ex.getMessage());
        }

        eventPublisher.publish(run.getId(), stepId, stepName, "RUNNING", null);
        return stepId;
    }

    /**
     * 结束一个 Span，记录指纹化摘要。
     *
     * @param stepId         步骤 ID
     * @param outputSnapshot 指纹化摘要（不允许传工单原文）
     * @param errorMessage   null 表示成功，非 null 表示失败
     */
    public void end(String stepId, String outputSnapshot, String errorMessage) {
        AgentStep step = findStep(stepId);
        if (step == null) {
            log.warn("Span not found for stepId={}, skipping end", stepId);
            return;
        }

        Instant finishedAt = Instant.now();
        long costMs = finishedAt.toEpochMilli() - step.getStartedAt().toEpochMilli();

        step.setStatus(errorMessage == null ? "SUCCESS" : "FAILED");
        step.setOutputSnapshot(outputSnapshot);
        step.setCostMs(costMs);
        step.setErrorMessage(errorMessage);
        step.setFinishedAt(finishedAt);

        stepRepository.save(step);

        // OTel 层：关闭 Span ---
        io.micrometer.tracing.Span otelSpan = otelSpans.remove(stepId);
        if (otelSpan != null) {
            try {
                if (errorMessage != null) {
                    otelSpan.tag("error", errorMessage);
                }
                if (outputSnapshot != null) {
                    otelSpan.tag("agent.output", truncateForTag(outputSnapshot));
                }
                otelSpan.tag("agent.costMs", String.valueOf(costMs));
                otelSpan.end();
            } catch (Exception ex) {
                log.debug("OTel span end failed for stepId={}, ignoring: {}", stepId, ex.getMessage());
            }
        }

        eventPublisher.publish(run.getId(), stepId, step.getStepName(), step.getStatus(), outputSnapshot);
    }

    /**
     * 为步骤补充 inputSnapshot（指纹化摘要）。
     * 必须在 begin 之后调用。
     */
    public void recordInput(String stepId, String inputSnapshot) {
        AgentStep step = findStep(stepId);
        if (step != null) {
            step.setInputSnapshot(inputSnapshot);
            stepRepository.save(step);

            // OTel 层补充 tag
            io.micrometer.tracing.Span otelSpan = otelSpans.get(stepId);
            if (otelSpan != null && inputSnapshot != null) {
                try {
                    otelSpan.tag("agent.input", truncateForTag(inputSnapshot));
                } catch (Exception ex) {
                    log.debug("OTel tag failed, ignoring: {}", ex.getMessage());
                }
            }
        }
    }

    /**
     * 记录 LLM 使用标记和工具名。
     */
    public void recordMeta(String stepId, boolean llmUsed, String toolUsed) {
        AgentStep step = findStep(stepId);
        if (step != null) {
            step.setLlmUsed(llmUsed);
            step.setToolUsed(toolUsed);
            stepRepository.save(step);

            // OTel 层补充 tag
            io.micrometer.tracing.Span otelSpan = otelSpans.get(stepId);
            if (otelSpan != null) {
                try {
                    otelSpan.tag("agent.llmUsed", String.valueOf(llmUsed));
                    if (toolUsed != null) {
                        otelSpan.tag("agent.toolUsed", toolUsed);
                    }
                } catch (Exception ex) {
                    log.debug("OTel tag failed, ignoring: {}", ex.getMessage());
                }
            }
        }
    }

    /**
     * 生成指纹化摘要，防止原文入库。
     * 调用方应使用此方法格式化 inputSnapshot/outputSnapshot。
     */
    public static String fingerprint(String label, String value) {
        if (value == null || value.isBlank()) return label + "=<empty>";
        if (value.length() <= 200) return label + "=" + value;
        return label + "=" + value.substring(0, 100) + "...(" + value.length() + " chars)";
    }

    // --- internal ---

    private AgentStep findStep(String stepId) {
        return run.getSteps().stream()
                .filter(s -> s.getId().equals(stepId))
                .findFirst()
                .orElse(null);
    }

    /** OTel tag 值有长度限制，截断到 200 字符 */
    private static String truncateForTag(String value) {
        if (value == null) return null;
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
