package com.gcll.docagent.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 队列调度器——DB 队列的驱动心脏（多实例水平扩展的入口）。
 * <p>认领由两条路径驱动（互相幂等，CAS 保证不会重复执行）：
 * <ul>
 *   <li>快路径：start()/followUp() 入队后发布 {@link RunQueuedEvent} → {@link #onRunQueued}
 *       立即触发一轮认领，提交到开跑近乎零等待，执行入口沿事件即可找到；</li>
 *   <li>兜底路径：claimLoop 每秒轮询——负责多实例间认领与事件丢失后的补漏；</li>
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

    /** 快路径：入队后立即触发一轮认领（同步执行于提交线程；认领本身有 CAS 互斥，失败由轮询兜底）。 */
    @EventListener
    public void onRunQueued(RunQueuedEvent event) {
        try {
            analysisService.claimQueuedRuns();
        } catch (Exception ex) {
            log.warn("Event-triggered claim error, runId={}: {}", event.runId(), ex.getMessage());
        }
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
