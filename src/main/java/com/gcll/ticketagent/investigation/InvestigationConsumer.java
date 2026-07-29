package com.gcll.ticketagent.investigation;

import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.observability.trace.TraceRecorderFactory;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.triage.TriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 排查阶段消费者——监听分诊完成事件，异步执行排查。
 * <p>
 * 分诊（同步，秒级）和排查（异步）通过 Kafka 解耦：
 * - 分诊完成后发布 TriageCompletedEvent
 * - 本消费者收到事件后启动排查
 * - 排查产出证据包 + 参考诊断，附带给接手团队
 */
@Component
public class InvestigationConsumer {

    private static final Logger log = LoggerFactory.getLogger(InvestigationConsumer.class);

    private final InvestigationService investigationService;
    private final AgentRunRepository agentRunRepository;
    private final TraceRecorderFactory traceRecorderFactory;
    private final TransactionTemplate transactionTemplate;

    public InvestigationConsumer(
            InvestigationService investigationService,
            AgentRunRepository agentRunRepository,
            TraceRecorderFactory traceRecorderFactory,
            TransactionTemplate transactionTemplate
    ) {
        this.investigationService = investigationService;
        this.agentRunRepository = agentRunRepository;
        this.traceRecorderFactory = traceRecorderFactory;
        this.transactionTemplate = transactionTemplate;
    }

    @KafkaListener(
            topics = "${opsmind.investigation.topic:opsmind.investigation.execute}",
            groupId = "opsmind-investigation-runner"
    )
    public void onTriageCompleted(TriageCompletedEvent event) {
        String runId = event.runId();
        log.info("收到分诊完成事件, runId={}, priority={}, issueType={}",
                runId, event.triageResult().priority(), event.triageResult().issueType());

        AgentRun run = agentRunRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("AgentRun not found, skip investigation, runId={}", runId);
            return;
        }

        // 更新状态为 INVESTIGATING
        transactionTemplate.executeWithoutResult(status -> {
            run.setStatus(AgentRunStatus.INVESTIGATING);
            agentRunRepository.save(run);
        });

        try {
            InvestigationResult result = investigationService.investigate(
                    run, event.triageResult(), event.extract(),
                    event.draftContent(), event.extractLlmUsed()
            );

            if (result.isSuccess()) {
                log.info("排查完成, runId={}, needConfirm={}", runId, result.needHumanConfirm());
            } else {
                log.error("排查失败, runId={}, error={}", runId, result.error());
            }
        } catch (Exception ex) {
            log.error("排查异常, runId={}", runId, ex);
            transactionTemplate.executeWithoutResult(status -> {
                run.setStatus(AgentRunStatus.FAILED);
                agentRunRepository.save(run);
            });
        }
    }
}
