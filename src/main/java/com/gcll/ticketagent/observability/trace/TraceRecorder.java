package com.gcll.ticketagent.observability.trace;

import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentStep;
import com.gcll.ticketagent.persistence.repository.AgentStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Trace 记录器——树形 Trace 的核心。
 * <p>
 * 职责：
 * <ul>
 *   <li>维护当前 Trace 的 span 序号，生成 spanId</li>
 *   <li>记录步骤到 agent_step 表（含 parentStepId，表达树形结构）</li>
 *   <li>强制存指纹不存原文：inputSnapshot/outputSnapshot 必须经过摘要格式化</li>
 *   <li>记录 startedAt/finishedAt，精确计算 span 耗时</li>
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
    private final AtomicInteger spanSequence = new AtomicInteger(0);

    /**
     * 当前线程的 parentStepId，支持嵌套 begin/end。
     * 用 ThreadLocal 而非显式传参，避免方法签名膨胀。
     */
    private final ThreadLocal<String> currentParentStepId = new ThreadLocal<>();

    public TraceRecorder(AgentRun run,
                         AgentStepRepository stepRepository,
                         AgentStepEventPublisher eventPublisher) {
        this.run = run;
        this.stepRepository = stepRepository;
        this.eventPublisher = eventPublisher;
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
        String spanId = Span.generateSpanId(run.getTraceId(), spanSequence.incrementAndGet());
        Instant startedAt = Instant.now();

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

        eventPublisher.publish(run.getId(), stepName, "RUNNING", null);
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
        eventPublisher.publish(run.getId(), step.getStepName(), step.getStatus(), outputSnapshot);
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

    /**
     * 发布步骤事件的接口（由外部实现，保持解耦）
     */
    public interface AgentStepEventPublisher {
        void publish(String runId, String stepName, String status, String message);
    }
}
