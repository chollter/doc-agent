package com.gcll.ticketagent.investigation.strategy;

import com.gcll.ticketagent.governance.priority.TicketPriority;
import com.gcll.ticketagent.triage.TriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 排查策略选择器——按优先级动态选择排查深度。
 * <p>
 * P0 → ReAct（自主推理+工具迭代，Day11 实现，当前降级到 Linear）
 * P1 → Linear（固定顺序：RAG → 工具 → 根因 → 建议）
 * P2/P3 → Consult（仅 RAG + 建议，不调工具，省成本）
 */
@Component
public class InvestigationStrategyResolver {

    private static final Logger log = LoggerFactory.getLogger(InvestigationStrategyResolver.class);

    private final Map<String, InvestigationStrategy> strategyMap;

    public InvestigationStrategyResolver(List<InvestigationStrategy> strategies) {
        this.strategyMap = strategies.stream()
                .collect(Collectors.toMap(InvestigationStrategy::name, Function.identity()));
        log.info("已注册排查策略: {}", strategyMap.keySet());
    }

    /**
     * 根据分诊结果选择排查策略
     *
     * @param triageResult 分诊结果
     * @return 排查策略
     */
    public InvestigationStrategy resolve(TriageResult triageResult) {
        TicketPriority priority = triageResult.priority();

        InvestigationStrategy strategy = switch (priority) {
            case P0 -> {
                // P0 优先用 ReAct，未实现时降级到 Linear
                InvestigationStrategy react = strategyMap.get("REACT");
                if (react != null) {
                    yield react;
                }
                log.warn("ReAct策略未实现，P0降级为Linear, runId将自动分配");
                yield strategyMap.get("LINEAR");
            }
            case P1 -> strategyMap.get("LINEAR");
            case P2, P3 -> strategyMap.get("CONSULT");
        };

        if (strategy == null) {
            log.error("排查策略未找到, priority={}, 降级为Linear", priority);
            strategy = strategyMap.get("LINEAR");
        }

        return strategy;
    }
}
