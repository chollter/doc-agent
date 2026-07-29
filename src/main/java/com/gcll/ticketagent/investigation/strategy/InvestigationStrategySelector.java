package com.gcll.ticketagent.investigation.strategy;

import com.gcll.ticketagent.triage.TriageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 排查策略选择器
 * 
 * 根据分诊结果选择合适的排查策略：
 * - 优先级 + 场景 → 策略类型
 * - 支持的策略列表 → 具体实现
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvestigationStrategySelector {

    private final List<InvestigationStrategy> strategies;

    /**
     * 选择合适的排查策略
     */
    public InvestigationStrategy selectStrategy(TriageResult triageResult) {
        // 1. 根据优先级和场景确定策略类型
        InvestigationStrategyType strategyType = InvestigationStrategyType.fromPriorityAndIssueType(
                triageResult.priority().name(), 
                triageResult.issueType().toString()
        );
        
        log.debug("策略类型选择 - priority: {}, issueType: {}, strategyType: {}", 
                triageResult.priority().name(), triageResult.issueType().toString(), strategyType);
        
        // 2. 获取支持该策略类型的所有策略
        List<InvestigationStrategy> candidateStrategies = strategies.stream()
                .filter(s -> s.getType() == strategyType)
                .sorted((s1, s2) -> Integer.compare(s1.getPriority(), s2.getPriority()))
                .collect(Collectors.toList());
        
        if (candidateStrategies.isEmpty()) {
            throw new IllegalStateException("没有找到支持的策略: " + strategyType);
        }
        
        // 3. 选择优先级最高的策略
        InvestigationStrategy selected = candidateStrategies.get(0);
        log.info("选择策略 - strategy: {}, type: {}, priority: {}", 
                selected.getName(), selected.getType(), selected.getPriority());
        
        return selected;
    }
    
    /**
     * 注册策略（Spring 自动注入）
     */
    public void registerStrategy(InvestigationStrategy strategy) {
        strategies.add(strategy);
    }
}