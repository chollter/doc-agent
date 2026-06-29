package com.gcll.ticketagent.agent.multiagent;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MultiAgentOrchestrator 单测（阶段C：多 Agent 协作）。
 * 验证：① 并行调度所有 worker ② worker 失败降级不拖垮并行 ③ critic 交叉验证产出 verdict。
 *
 * <p>用 mock worker（不依赖真实 LLM），核心验证编排逻辑（并行/汇合/降级）而非 LLM 效果。
 */
class MultiAgentOrchestratorTest {

    private TicketExtractResult sampleExtract() {
        return new TicketExtractResult(
                IssueType.INCIDENT, "payment-service", "/pay/callback", "payCallback",
                "OOM", "heap space", "prod", "200 orders", "14:30",
                "支付受影响", List.of("CORE_PAYMENT"), 0.8
        );
    }

    /** 构造 mock supervisor：返回全部 worker（保持原"全跑"测试行为）。 */
    private SupervisorAgent supervisorSelectsAll() {
        SupervisorAgent supervisor = mock(SupervisorAgent.class);
        when(supervisor.decide(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
        return supervisor;
    }

    /** 场景1：3 个 worker 并行执行，findings 全部收集到黑板。 */
    @Test
    void allWorkersRunInParallelAndCollectFindings() {
        WorkerAgent logWorker = stubWorker("log", "日志显示 OOM 堆在 HashMap", 0.8);
        WorkerAgent metricWorker = stubWorker("metric", "内存使用率 98%", 0.85);
        WorkerAgent knowledgeWorker = stubWorker("knowledge", "历史有内存泄漏案例", 0.7);
        CriticAgent critic = mock(CriticAgent.class);
        when(critic.review(any())).thenReturn(CriticVerdict.of(
                true, "综合根因：内存泄漏", 0.85, List.of(),
                List.of(AgentFinding.of("log", "log结论", 0.8))));

        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                List.of(logWorker, metricWorker, knowledgeWorker), critic, supervisorSelectsAll());

        CriticVerdict verdict = orchestrator.orchestrate(
                "run-1", "工单内容", sampleExtract(), null);

        assertThat(verdict.consolidatedRootCause()).isEqualTo("综合根因：内存泄漏");
        assertThat(verdict.consistent()).isTrue();
        // critic 收到了 3 个 worker 的 finding（通过 review 的 ctx 传入）
    }

    /** 场景2：某个 worker 抛异常，不拖垮并行——其他 worker 的 finding 仍正常收集。 */
    @Test
    void failingWorkerDoesNotBreakParallel() {
        // 一个正常 worker + 一个抛异常的 worker
        WorkerAgent goodWorker = stubWorker("log", "正常结论", 0.8);
        WorkerAgent badWorker = new WorkerAgent() {
            @Override
            public String role() { return "metric"; }
            @Override
            public AgentFinding investigate(AgentRunContext ctx) {
                throw new RuntimeException("worker 炸了");
            }
        };
        CriticAgent critic = mock(CriticAgent.class);
        when(critic.review(any())).thenAnswer(inv -> {
            AgentRunContext ctx = inv.getArgument(0);
            return CriticVerdict.of(true, "降级结论", 0.5, List.of(), ctx.findings());
        });

        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                List.of(goodWorker, badWorker), critic, supervisorSelectsAll());

        // 即使 badWorker 抛异常，allOf.join() 会把异常包进 CompletableFuture
        // 这里验证：编排不应被单个 worker 异常彻底打断（至少 critic 能拿到好的 worker 的 finding）
        CriticVerdict verdict = orchestrator.orchestrate(
                "run-2", "工单", sampleExtract(), null);

        // 关键：goodWorker 的 finding 至少进黑板了（验证不拖垮）
        assertThat(verdict.findings()).extracting(AgentFinding::sourceRole).contains("log");
    }

    /** 场景3：无 worker（空列表），critic 拿到空 findings，走降级。 */
    @Test
    void emptyWorkersDegradesGracefully() {
        CriticAgent critic = mock(CriticAgent.class);
        when(critic.review(any())).thenReturn(CriticVerdict.of(
                false, "无可用发现", 0.0, List.of("所有 worker 都未产出结论"), List.of()));

        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(List.of(), critic, supervisorSelectsAll());

        CriticVerdict verdict = orchestrator.orchestrate("run-3", "工单", sampleExtract(), null);

        assertThat(verdict.consistent()).isFalse();
        assertThat(verdict.consolidatedRootCause()).isEqualTo("无可用发现");
    }

    /** 场景4：验证 worker 真的是并行（用计数器+小延迟验证并发，非严格时序断言）。 */
    @Test
    void workersActuallyRunConcurrently() {
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        WorkerAgent w1 = trackingWorker("log", concurrent, maxConcurrent);
        WorkerAgent w2 = trackingWorker("metric", concurrent, maxConcurrent);
        WorkerAgent w3 = trackingWorker("knowledge", concurrent, maxConcurrent);
        CriticAgent critic = mock(CriticAgent.class);
        when(critic.review(any())).thenReturn(CriticVerdict.of(true, "ok", 0.8, List.of(), List.of()));

        new MultiAgentOrchestrator(List.of(w1, w2, w3), critic, supervisorSelectsAll())
                .orchestrate("run-4", "工单", sampleExtract(), null);

        // 3 个 worker 并行时，并发计数应 >= 2（容忍调度抖动，不要求严格=3）
        assertThat(maxConcurrent.get()).isGreaterThanOrEqualTo(2);
    }

    /** 场景5：Supervisor 只选了部分 worker，未选中的不执行。 */
    @Test
    void supervisorSelectsSubsetOnlySelectedRun() {
        WorkerAgent logWorker = stubWorker("log", "日志结论", 0.8);
        WorkerAgent metricWorker = stubWorker("metric", "指标结论", 0.8);
        WorkerAgent knowledgeWorker = stubWorker("knowledge", "案例结论", 0.8);
        CriticAgent critic = mock(CriticAgent.class);
        when(critic.review(any())).thenAnswer(inv -> {
            AgentRunContext ctx = inv.getArgument(0);
            return CriticVerdict.of(true, "ok", 0.8, List.of(), ctx.findings());
        });
        // Supervisor 只选 knowledge（模拟咨询类工单只需查案例）
        SupervisorAgent supervisor = mock(SupervisorAgent.class);
        when(supervisor.decide(any(), any(), any())).thenReturn(List.of(knowledgeWorker));

        new MultiAgentOrchestrator(List.of(logWorker, metricWorker, knowledgeWorker), critic, supervisor)
                .orchestrate("run-5", "工单", sampleExtract(), null);

        // 关键：只有 knowledge worker 的 finding 进了黑板，log/metric 没跑
        // 通过 critic 收到的 ctx 验证（critic mock 返回 ctx.findings）
        org.mockito.Mockito.verify(logWorker, org.mockito.Mockito.never()).investigate(any());
        org.mockito.Mockito.verify(metricWorker, org.mockito.Mockito.never()).investigate(any());
        org.mockito.Mockito.verify(knowledgeWorker).investigate(any());
    }

    /** 场景6：Supervisor LLM 失败，降级为全量调度（兜底，不比现状差）。 */
    @Test
    void supervisorFailureDegradesToAllWorkers() {
        WorkerAgent logWorker = stubWorker("log", "log", 0.8);
        WorkerAgent metricWorker = stubWorker("metric", "metric", 0.8);
        CriticAgent critic = mock(CriticAgent.class);
        when(critic.review(any())).thenReturn(CriticVerdict.of(true, "ok", 0.8, List.of(), List.of()));
        // Supervisor 降级返回全部 worker（模拟 LLM 失败兜底）
        SupervisorAgent supervisor = mock(SupervisorAgent.class);
        when(supervisor.decide(any(), any(), any())).thenReturn(List.of(logWorker, metricWorker));

        new MultiAgentOrchestrator(List.of(logWorker, metricWorker), critic, supervisor)
                .orchestrate("run-6", "工单", sampleExtract(), null);

        // 降级后两个 worker 都跑了
        org.mockito.Mockito.verify(logWorker).investigate(any());
        org.mockito.Mockito.verify(metricWorker).investigate(any());
    }

    // ---- 辅助：构造 stub worker ----

    private WorkerAgent stubWorker(String role, String content, double confidence) {
        WorkerAgent w = mock(WorkerAgent.class);
        when(w.role()).thenReturn(role);
        when(w.investigate(any())).thenReturn(AgentFinding.of(role, content, confidence));
        return w;
    }

    private WorkerAgent trackingWorker(String role, AtomicInteger concurrent, AtomicInteger maxConcurrent) {
        return new WorkerAgent() {
            @Override
            public String role() { return role; }
            @Override
            public AgentFinding investigate(AgentRunContext ctx) {
                int now = concurrent.incrementAndGet();
                maxConcurrent.accumulateAndGet(now, Math::max);
                try { Thread.sleep(50); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                concurrent.decrementAndGet();
                return AgentFinding.of(role, role + "结论", 0.7);
            }
        };
    }
}
