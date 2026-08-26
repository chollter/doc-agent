package com.gcll.docagent.loop;

import com.gcll.docagent.analysis.DocumentAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 队列调度器——DB 队列的驱动心脏（多实例水平扩展的入口）：
 * <ul>
 *   <li>认领循环：按空闲 worker 容量 CAS 认领 QUEUED run，本实例执行；</li>
 *   <li>自愈循环：扫描超时未推进的 ANALYZING / WAIT_HUMAN_CONFIRM run 重新入队，
 *       由任意实例从 checkpoint 续跑——崩溃恢复从"启动时一次"升级为"持续自愈"。</li>
 * </ul>
 */
@Component
public class RunQueueScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunQueueScheduler.class);

    private final DocumentAnalysisService analysisService;

    public RunQueueScheduler(DocumentAnalysisService analysisService) {
        this.analysisService = analysisService;
    }

    @Scheduled(fixedDelayString = "${docagent.analysis.dispatcher.poll-ms:1000}")
    public void claimLoop() {
        try {
            analysisService.claimQueuedRuns();
        } catch (Exception ex) {
            log.warn("Claim loop error: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${docagent.analysis.dispatcher.requeue-scan-ms:60000}")
    public void healLoop() {
        try {
            analysisService.requeueStaleRuns();
        } catch (Exception ex) {
            log.warn("Heal loop error: {}", ex.getMessage());
        }
    }
}
