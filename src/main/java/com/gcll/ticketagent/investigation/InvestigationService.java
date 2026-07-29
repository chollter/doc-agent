package com.gcll.ticketagent.investigation;

import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.investigation.strategy.InvestigationStrategy;
import com.gcll.ticketagent.investigation.strategy.InvestigationStrategyResolver;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.observability.trace.TraceRecorderFactory;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.triage.TriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 排查阶段服务——v2 异步排查的核心调度入口。
 * <p>
 * 定位：Agent 做辅助不做决策。
 * 产出：证据包 + 参考诊断，人是最终判断者。
 * <p>
 * 与分诊阶段解耦：通过 Kafka TriageCompletedEvent 触发。
 * 分诊已完成优先级评估和路由，排查不再重复。
 * <p>
 * 策略选择：按优先级动态选择排查深度
 * - P0 → ReAct（自主推理+工具迭代）
 * - P1 → Linear（固定顺序：RAG → 工具 → 根因 → 建议）
 * - P2/P3 → Consult（仅 RAG + 建议，不调工具，省成本）
 */
@Service
public class InvestigationService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

    private final InvestigationStrategyResolver strategyResolver;
    private final TraceRecorderFactory traceRecorderFactory;
    private final AgentRunRepository agentRunRepository;
    private final TransactionTemplate transactionTemplate;

    public InvestigationService(
            InvestigationStrategyResolver strategyResolver,
            TraceRecorderFactory traceRecorderFactory,
            AgentRunRepository agentRunRepository,
            TransactionTemplate transactionTemplate
    ) {
        this.strategyResolver = strategyResolver;
        this.traceRecorderFactory = traceRecorderFactory;
        this.agentRunRepository = agentRunRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 执行排查——按分诊结果的优先级动态选择策略。
     *
     * @param run            工单
     * @param triageResult   分诊结果（含优先级、路由）
     * @param extract        结构化抽取
     * @param draftContent   工单原文
     * @param extractLlmUsed 抽取是否用了 LLM
     * @return 排查结果
     */
    public InvestigationResult investigate(
            AgentRun run, TriageResult triageResult,
            TicketExtractResult extract, String draftContent,
            boolean extractLlmUsed
    ) {
        // 按优先级选择策略
        InvestigationStrategy strategy = strategyResolver.resolve(triageResult);
        log.info("排查策略选择: runId={}, priority={}, strategy={}",
                run.getId(), triageResult.priority(), strategy.name());

        TraceRecorder tracer = traceRecorderFactory.create(run);
        try {
            return strategy.execute(run, triageResult, extract, draftContent, extractLlmUsed, tracer);
        } catch (Exception ex) {
            log.error("排查执行失败, runId={}, strategy={}", run.getId(), strategy.name(), ex);
            transactionTemplate.executeWithoutResult(status -> {
                run.setStatus(AgentRunStatus.FAILED);
                agentRunRepository.save(run);
            });
            return InvestigationResult.failed(run.getId(), ex.getMessage());
        }
    }
}
