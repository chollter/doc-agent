package com.gcll.ticketagent.investigation;

import com.gcll.ticketagent.agent.AgentStepEventPublisher;
import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.domain.AgentStep;
import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.human.HumanConfirmService;
import com.gcll.ticketagent.governance.human.HumanConfirmTrigger;
import com.gcll.ticketagent.governance.priority.TicketPriority;
import com.gcll.ticketagent.investigation.strategy.InvestigationStrategy;
import com.gcll.ticketagent.investigation.strategy.InvestigationStrategyResolver;
import com.gcll.ticketagent.investigation.strategy.LinearInvestigationStrategy;
import com.gcll.ticketagent.investigation.strategy.ReActInvestigationStrategy;
import com.gcll.ticketagent.langchain4j.ReActAssistant;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.observability.trace.TraceRecorderFactory;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.persistence.repository.AgentStepRepository;
import com.gcll.ticketagent.triage.TriageResult;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 端到端排查流程集成测试——验证 v2 核心架构：策略选择→ReAct降级→Trace树形结构。
 *
 * <h3>测试范围</h3>
 * <ul>
 *   <li>P0 工单 → InvestigationStrategyResolver 选中 ReAct</li>
 *   <li>ReAct 失败（模拟 LangChain4j LLM 调用异常）→ 真正降级到 LinearInvestigationStrategy</li>
 *   <li>Linear 排查完整执行（stub 依赖返回预设数据）→ 人工确认 → WAIT_HUMAN_CONFIRM</li>
 *   <li>Trace 树形结构验证：parentStepId 链完整</li>
 * </ul>
 *
 * <h3>不依赖 Spring Context</h3>
 * 纯单元测试，手动构建策略 + mock 外部依赖（LLM/DB/Kafka/Redis），
 * 不需要 Redis/Kafka 运行，可在 CI 中直接执行。
 */
class EndToEndInvestigationTest {

    private InMemoryAgentStepRepository stepRepository;
    private InMemoryAgentRunRepository agentRunRepository;
    private TraceRecorderFactory traceRecorderFactory;

    @BeforeEach
    void setUp() {
        stepRepository = new InMemoryAgentStepRepository();
        agentRunRepository = new InMemoryAgentRunRepository();
        AgentStepEventPublisher eventPublisher = new AgentStepEventPublisher();
        Tracer otelTracer = mock(Tracer.class);
        io.micrometer.tracing.Span mockSpan = mock(io.micrometer.tracing.Span.class);
        when(otelTracer.nextSpan()).thenReturn(mockSpan);
        when(mockSpan.name(any(String.class))).thenReturn(mockSpan);
        when(mockSpan.tag(any(String.class), any(String.class))).thenReturn(mockSpan);
        when(mockSpan.start()).thenReturn(mockSpan);

        traceRecorderFactory = new TraceRecorderFactory(stepRepository, eventPublisher, otelTracer);
    }

    @Test
    @DisplayName("P0工单 → ReAct策略被选中 → ReAct失败降级到Linear → 完整排查 → WAIT_HUMAN_CONFIRM")
    void p0ReActDegradeToLinearAndComplete() {
        // --- 构造 P0 分诊结果 ---
        TriageResult triageResult = TriageResult.builder()
                .issueType(IssueType.INCIDENT)
                .priority(TicketPriority.P0)
                .confidence(1.0)
                .source(TriageResult.TriageSource.RULE)
                .affectedSystem("支付系统")
                .affectedModule("回调模块")
                .routedTeam("支付研发组")
                .needHumanConfirm(true)
                .followUpRound(0)
                .build();

        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT, "支付系统", "回调模块",
                "/pay/callback", "500", "Internal Server Error",
                "production", "全量用户", "10:00-now", "核心支付不可用",
                List.of("P0", "OOM"), 0.9
        );

        AgentRun run = new AgentRun("run-001", "trace-001", "sess-001", "u-001",
                "生产环境核心支付全量不可用，503错误");
        run.setStatus(AgentRunStatus.INVESTIGATING);
        agentRunRepository.save(run);

        // --- 构造 ReActAssistant mock（模拟 LLM 调用失败） ---
        ReActAssistant reActAssistant = mock(ReActAssistant.class);
        when(reActAssistant.investigate(any(String.class)))
                .thenThrow(new RuntimeException("LLM API key invalid (test-key)"));

        // --- 构造 LinearInvestigationStrategy stub（跳过真实依赖） ---
        LinearStubStrategy linearStub = new LinearStubStrategy();

        // --- 构造 ReActInvestigationStrategy ---
        HumanConfirmTrigger humanConfirmTrigger = mock(HumanConfirmTrigger.class);
        HumanConfirmService humanConfirmService = mock(HumanConfirmService.class);
        TransactionTemplate txTemplate = mock(TransactionTemplate.class);

        ReActInvestigationStrategy reActStrategy = new ReActInvestigationStrategy(
                reActAssistant,
                humanConfirmTrigger,
                humanConfirmService,
                agentRunRepository,
                txTemplate,
                linearStub
        );

        // --- 构造 InvestigationService ---
        InvestigationStrategyResolver resolver = new InvestigationStrategyResolver(
                List.of(reActStrategy, linearStub));
        InvestigationService service = new InvestigationService(
                resolver, traceRecorderFactory, agentRunRepository, txTemplate);

        // --- 执行排查 ---
        InvestigationResult result = service.investigate(
                run, triageResult, extract, run.getOriginalContent(), false);

        // --- 验证结果 ---
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.summary()).isNotNull();
        assertThat(result.needHumanConfirm()).isTrue();

        // --- 验证 Trace 树形结构 ---
        List<AgentStep> steps = stepRepository.findByRunId(run.getId());
        assertThat(steps).isNotEmpty();

        // 顶层步骤：REACT_INVESTIGATION
        AgentStep reactInvestigation = findStep(steps, "REACT_INVESTIGATION");
        assertThat(reactInvestigation).as("REACT_INVESTIGATION step should exist").isNotNull();
        assertThat(reactInvestigation.getParentStepId()).isNull(); // 顶层

        // 子步骤：REACT_LOOP（ReAct 循环，会失败）
        AgentStep reactLoop = findStep(steps, "REACT_LOOP");
        assertThat(reactLoop).as("REACT_LOOP step should exist").isNotNull();
        assertThat(reactLoop.getParentStepId()).isEqualTo(reactInvestigation.getId());

        // 降级步骤：REACT_DEGRADE_TO_LINEAR
        AgentStep degradeStep = findStep(steps, "REACT_DEGRADE_TO_LINEAR");
        assertThat(degradeStep).as("REACT_DEGRADE_TO_LINEAR step should exist").isNotNull();
        assertThat(degradeStep.getParentStepId()).isEqualTo(reactInvestigation.getId());

        // Linear 排查步骤（由 LinearStubStrategy 产生）
        AgentStep linearStep = findStep(steps, "LINEAR_STUB");
        assertThat(linearStep).as("LINEAR_STUB step should exist").isNotNull();
    }

    @Test
    @DisplayName("P1工单 → Linear策略被选中 → 不走ReAct")
    void p1SelectsLinearNotReAct() {
        TriageResult triageResult = TriageResult.builder()
                .issueType(IssueType.INCIDENT)
                .priority(TicketPriority.P1)
                .confidence(1.0)
                .source(TriageResult.TriageSource.RULE)
                .routedTeam("支付研发组")
                .needHumanConfirm(true)
                .followUpRound(0)
                .build();

        InvestigationStrategy reActStrategy = mock(InvestigationStrategy.class);
        when(reActStrategy.name()).thenReturn("REACT");

        InvestigationStrategy linearStrategy = mock(InvestigationStrategy.class);
        when(linearStrategy.name()).thenReturn("LINEAR");

        InvestigationStrategyResolver resolver = new InvestigationStrategyResolver(
                List.of(reActStrategy, linearStrategy));

        InvestigationStrategy selected = resolver.resolve(triageResult);
        assertThat(selected.name()).isEqualTo("LINEAR");
    }

    @Test
    @DisplayName("P0工单 → ReAct策略被选中（而非Linear）")
    void p0SelectsReActNotLinear() {
        TriageResult triageResult = TriageResult.builder()
                .issueType(IssueType.INCIDENT)
                .priority(TicketPriority.P0)
                .confidence(1.0)
                .source(TriageResult.TriageSource.RULE)
                .needHumanConfirm(true)
                .followUpRound(0)
                .build();

        InvestigationStrategy reActStrategy = mock(InvestigationStrategy.class);
        when(reActStrategy.name()).thenReturn("REACT");

        InvestigationStrategy linearStrategy = mock(InvestigationStrategy.class);
        when(linearStrategy.name()).thenReturn("LINEAR");

        InvestigationStrategyResolver resolver = new InvestigationStrategyResolver(
                List.of(reActStrategy, linearStrategy));

        InvestigationStrategy selected = resolver.resolve(triageResult);
        assertThat(selected.name()).isEqualTo("REACT");
    }

    @Test
    @DisplayName("P2/P3工单 → Consult策略被选中")
    void p2p3SelectsConsult() {
        for (TicketPriority p : List.of(TicketPriority.P2, TicketPriority.P3)) {
            TriageResult triageResult = TriageResult.builder()
                    .issueType(IssueType.CONSULT)
                    .priority(p)
                    .confidence(1.0)
                    .source(TriageResult.TriageSource.RULE)
                    .followUpRound(0)
                    .build();

            InvestigationStrategy consultStrategy = mock(InvestigationStrategy.class);
            when(consultStrategy.name()).thenReturn("CONSULT");

            InvestigationStrategy linearStrategy = mock(InvestigationStrategy.class);
            when(linearStrategy.name()).thenReturn("LINEAR");

            InvestigationStrategyResolver resolver = new InvestigationStrategyResolver(
                    List.of(consultStrategy, linearStrategy));

            InvestigationStrategy selected = resolver.resolve(triageResult);
            assertThat(selected.name()).isEqualTo("CONSULT");
        }
    }

    // --- 辅助 ---

    private AgentStep findStep(List<AgentStep> steps, String stepName) {
        return steps.stream()
                .filter(s -> stepName.equals(s.getStepName()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Linear 排查策略 stub——继承 LinearInvestigationStrategy 但 override execute()，
     * 跳过所有真实依赖，只记录 Trace + 返回成功结果。
     *
     * <p>用于 ReAct 降级测试：验证 ReAct catch 块真正调用了 LinearInvestigationStrategy.execute()。
     */
    static class LinearStubStrategy extends LinearInvestigationStrategy {

        LinearStubStrategy() {
            super(null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null);
        }

        @Override
        public InvestigationResult execute(
                AgentRun run,
                TriageResult triageResult,
                TicketExtractResult extract,
                String draftContent,
                boolean extractLlmUsed,
                TraceRecorder tracer) {
            String parentStepId = tracer.begin("LINEAR_STUB");
            tracer.end(parentStepId, "strategy=linear_stub,degraded_from_react", null);
            return InvestigationResult.success(
                    run.getId(), "Linear降级排查完成", "Linear证据", "Linear根因", "Linear建议",
                    true, "P0需人工确认");
        }

        @Override
        public String name() {
            return "LINEAR";
        }
    }

    // --- 内存 Repository ---

    static class InMemoryAgentStepRepository implements AgentStepRepository {
        private final List<AgentStep> steps = new ArrayList<>();

        @Override
        public void save(AgentStep step) {
            steps.add(step);
        }

        @Override
        public List<AgentStep> findByRunId(String runId) {
            return steps.stream().filter(s -> runId.equals(s.getRunId())).toList();
        }
    }

    static class InMemoryAgentRunRepository implements AgentRunRepository {
        private final Map<String, AgentRun> runs = new ConcurrentHashMap<>();

        @Override
        public AgentRun save(AgentRun run) {
            runs.put(run.getId(), run);
            return run;
        }

        @Override
        public Optional<AgentRun> findById(String id) {
            return Optional.ofNullable(runs.get(id));
        }

        @Override
        public Optional<AgentRun> findByIdempotencyKey(String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public List<AgentRun> findStuckRunningRuns(Instant updatedBefore) {
            return List.of();
        }

        @Override
        public Collection<AgentRun> findAll() {
            return runs.values();
        }
    }
}
