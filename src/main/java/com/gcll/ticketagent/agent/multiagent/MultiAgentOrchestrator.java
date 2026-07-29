package com.gcll.ticketagent.agent.multiagent;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.resilience.LlmRunContext;
import com.gcll.ticketagent.understanding.gap.InfoGapAnalysis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 多智能体编排器（阶段C：任务拆解/多 Agent 协作）。
 *
 * <p>supervisor-worker-critic 模式：
 * <ol>
 *   <li>Supervisor（本类承担）：根据工单内容拆出子任务列表</li>
 *   <li>Worker（3 个子智能体）：并行执行各自调查，产出发现写入共享黑板</li>
 *   <li>Critic（{@link CriticAgent}）：交叉验证各 worker 结论，综合根因</li>
 * </ol>
 *
 * <p><b>并行执行</b>：3 个 worker 用 CompletableFuture 并行跑（比串行快约 3 倍）。
 * 单个 worker 失败不阻断——它在 investigate 内部已降级为低置信度 finding。
 *
 * <p><b>共享黑板</b>：所有 agent 通过 {@link AgentRunContext} 读写，而非局部变量传参。
 * 这是多 Agent 协作的本质——多个独立 agent 往共享状态区读写。
 *
 * <p>本类只编排，不产出最终根因文本（那是 Critic 的职责）。返回的 CriticVerdict 由
 * {@link MultiAgentRootCauseStrategy} 转成根因结果。
 */
@Component
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);

    private final List<WorkerAgent> workers;
    private final CriticAgent criticAgent;
    private final SupervisorAgent supervisorAgent;

    public MultiAgentOrchestrator(List<WorkerAgent> workers, CriticAgent criticAgent,
                                  SupervisorAgent supervisorAgent) {
        this.workers = workers;
        this.criticAgent = criticAgent;
        this.supervisorAgent = supervisorAgent;
    }

    /**
     * 执行多 Agent 协作：拆任务 → 并行调查 → 审查综合。
     *
     * @return Critic 的审查结论（综合根因 + 置信度 + 矛盾点）
     */
    public CriticVerdict orchestrate(String runId, String originalContent,
                                     TicketExtractResult extract, InfoGapAnalysis gap) {
        // 1. 共享黑板
        AgentRunContext ctx = new AgentRunContext(runId, originalContent, extract, gap);

        // 2. Supervisor 智能拆解：LLM 根据工单决定该跑哪些 worker（不是无脑全跑）
        List<WorkerAgent> selectedWorkers = supervisorAgent.decide(originalContent, extract, workers);
        for (WorkerAgent w : selectedWorkers) {
            ctx.addTask(w.role() + "-investigation");
        }
        log.info("Multi-agent orchestrating, runId={}, supervisor selected {} of {} workers, tasks={}",
                runId, selectedWorkers.size(), workers.size(), ctx.tasks());

        // 3. Worker 并行调查（CompletableFuture.allOf 汇合）
        // 每个 future 内部 catch：即使某个 worker 违反契约抛异常，也不拖垮其他 worker 的并行
        List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
        for (WorkerAgent worker : selectedWorkers) {
            futures.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                LlmRunContext.bind(runId);
                try {
                    AgentFinding finding = worker.investigate(ctx);
                    ctx.addFinding(finding);
                } catch (Exception ex) {
                    // 防御：worker 不应抛异常（AbstractWorkerAgent 已 catch），但兜底防御
                    log.warn("Worker [{}] threw unexpectedly, skip it, runId={}: {}",
                            worker.role(), runId, ex.getMessage());
                    ctx.addFinding(AgentFinding.of(worker.role(), "子智能体异常：" + ex.getMessage(), 0.0));
                } finally {
                    LlmRunContext.clear();
                }
            }));
        }
        @SuppressWarnings("unchecked")
        java.util.concurrent.CompletableFuture<Void>[] arr = futures.toArray(
                (java.util.concurrent.CompletableFuture<Void>[]) new java.util.concurrent.CompletableFuture<?>[0]);
        java.util.concurrent.CompletableFuture.allOf(arr).join();

        log.info("Multi-agent workers done, runId={}, findings={}", runId, ctx.findings().size());

        // 4. Critic 交叉验证
        CriticVerdict verdict = criticAgent.review(ctx);
        ctx.setVerdict(verdict);

        log.info("Multi-agent critic done, runId={}, consistent={}, confidence={}",
                runId, verdict.consistent(), verdict.confidence());
        return verdict;
    }
}
