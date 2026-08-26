package com.gcll.docagent.loop;

import com.gcll.docagent.analysis.DocumentAnalysisService;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 崩溃恢复——启动时扫描非终态 run：
 * 有 checkpoint（含文档快照）→ 从断点续跑；没有 → 标记失败并说明原因。
 * <p>这是自研内核相对框架循环的核心能力：run 的生死不随进程。
 */
@Component
public class LoopRecovery implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LoopRecovery.class);

    private final AgentRunRepository agentRunRepository;
    private final DocumentAnalysisService analysisService;

    public LoopRecovery(AgentRunRepository agentRunRepository, DocumentAnalysisService analysisService) {
        this.agentRunRepository = agentRunRepository;
        this.analysisService = analysisService;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<AgentRun> interrupted = agentRunRepository.findAll().stream()
                .filter(r -> r.getStatus() == AgentRunStatus.RUNNING
                        || r.getStatus() == AgentRunStatus.ANALYZING
                        || r.getStatus() == AgentRunStatus.WAIT_HUMAN_CONFIRM)
                .toList();
        for (AgentRun run : interrupted) {
            try {
                if (analysisService.hasResumableCheckpoint(run.getId())) {
                    log.info("Recovering interrupted run from checkpoint, runId={}, status={}",
                            run.getId(), run.getStatus());
                    analysisService.resume(run.getId());
                } else {
                    log.warn("Interrupted run has no checkpoint, marking failed, runId={}", run.getId());
                    run.setStatus(AgentRunStatus.FAILED);
                    run.setLastError("进程中断且无检查点，无法恢复");
                    run.setFinishedAt(Instant.now());
                    agentRunRepository.save(run);
                }
            } catch (Exception ex) {
                log.error("Recovery failed, runId={}", run.getId(), ex);
                run.setStatus(AgentRunStatus.FAILED);
                run.setLastError("恢复失败: " + ex.getMessage());
                run.setFinishedAt(Instant.now());
                agentRunRepository.save(run);
            }
        }
    }
}
