package com.gcll.ticketagent.audit;

import com.gcll.ticketagent.agent.AgentStepEventPublisher;
import com.gcll.ticketagent.agent.AgentStepName;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentStep;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.persistence.repository.AgentStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计日志服务。
 * <p>
 * v2 保留 v1 的 recordStep 方法以兼容旧调用方，
 * 但新代码应优先使用 {@link TraceRecorder} 的 begin/end 模式。
 * <p>
 * 重要：inputSnapshot 参数必须传指纹化摘要，不传工单原文。
 * 使用 {@link TraceRecorder#fingerprint(String, String)} 格式化。
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AgentStepRepository agentStepRepository;
    private final AgentStepEventPublisher stepEventPublisher;

    public AuditLogService(AgentStepRepository agentStepRepository, AgentStepEventPublisher stepEventPublisher) {
        this.agentStepRepository = agentStepRepository;
        this.stepEventPublisher = stepEventPublisher;
    }

    /**
     * v1 兼容方法：一步记录（无 parentStepId）。
     * <p>
     * 旧调用方继续使用此方法，inputSnapshot 必须传摘要，不传原文。
     */
    @Transactional
    public void recordStep(
            AgentRun run,
            AgentStepName stepName,
            String inputSnapshot,
            String outputSnapshot,
            boolean llmUsed,
            String toolUsed,
            long costMs,
            String errorMessage
    ) {
        // 校验：inputSnapshot 不允许传工单原文（启发式：超过2000字符视为原文）
        if (inputSnapshot != null && inputSnapshot.length() > 2000) {
            log.warn("inputSnapshot for step {} exceeds 2000 chars ({} chars), likely raw content. " +
                     "Use TraceRecorder.fingerprint() to generate summary.",
                     stepName.name(), inputSnapshot.length());
            inputSnapshot = inputSnapshot.substring(0, 500) + "...(truncated, " + inputSnapshot.length() + " chars)";
        }

        AgentStep step = new AgentStep(
                UUID.randomUUID().toString(),
                run.getId(),
                null,  // v1 兼容：无 parentStepId
                stepName.name(),
                errorMessage == null ? "SUCCESS" : "FAILED",
                null,  // v1 兼容：无 spanId
                Instant.now(),
                Instant.now()
        );
        step.setInputSnapshot(inputSnapshot);
        step.setOutputSnapshot(outputSnapshot);
        step.setLlmUsed(llmUsed);
        step.setToolUsed(toolUsed);
        step.setCostMs(costMs);
        step.setErrorMessage(errorMessage);
        step.setFinishedAt(Instant.now());

        run.getSteps().add(step);
        agentStepRepository.save(step);
        stepEventPublisher.publish(run.getId(), stepName.name(), step.getStatus(), outputSnapshot);
    }
}
