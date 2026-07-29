package com.gcll.ticketagent.investigation.strategy;

import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.investigation.InvestigationResult;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.triage.TriageResult;

/**
 * 排查策略——v2 按优先级动态选择排查深度。
 * <p>
 * P0 → ReAct（自主推理+工具迭代，Day11 实现）
 * P1 → Linear（固定顺序：RAG → 工具 → 根因 → 建议）
 * P2/P3 → Consult（仅 RAG + 建议，不调工具，省成本）
 * <p>
 * 每种策略产出相同的 InvestigationResult，但内部流程深度不同。
 */
public interface InvestigationStrategy {

    /**
     * 执行排查
     *
     * @param run       工单
     * @param triageResult 分诊结果
     * @param extract   结构化抽取
     * @param draftContent 工单原文
     * @param extractLlmUsed 抽取是否用了 LLM
     * @param tracer    Trace 记录器
     * @return 排查结果
     */
    InvestigationResult execute(
            AgentRun run,
            TriageResult triageResult,
            TicketExtractResult extract,
            String draftContent,
            boolean extractLlmUsed,
            TraceRecorder tracer
    );

    /**
     * 策略名称（用于 Trace 和日志）
     */
    String name();
}
