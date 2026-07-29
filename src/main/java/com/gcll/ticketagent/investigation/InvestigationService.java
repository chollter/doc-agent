package com.gcll.ticketagent.investigation;

import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.triage.TriageResult;
import com.gcll.ticketagent.investigation.strategy.InvestigationStrategy;
import com.gcll.ticketagent.investigation.strategy.InvestigationStrategySelector;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.observability.trace.TraceRecorderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 排查阶段服务
 * 
 * 定位：Agent做辅助不做决策
 * 策略：证据收集→证据整理→相似案例检索，核心产出是证据包，初步诊断作为低置信度参考
 * 
 * 与分诊阶段解耦：
 * - 分诊阶段：同步，秒级出结果，工单路由到团队
 * - 排查阶段：异步，不阻塞分诊，通过 Kafka 事件触发，证据包附带给接手团队
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvestigationService {

    private final InvestigationStrategySelector strategySelector;
    private final TraceRecorderFactory traceRecorderFactory;
    private final InvestigationEventPublisher eventPublisher;

    /**
     * 启动排查阶段（异步）
     * 
     * @param run Agent 运行实例
     * @param triageResult 分诊结果
     * @return CompletableFuture 异步任务
     */
    public CompletableFuture<InvestigationResult> startInvestigation(AgentRun run, TriageResult triageResult) {
        String investigationId = "investigation-" + run.getId() + "-" + System.currentTimeMillis();
        
        log.info("启动排查阶段 - runId: {}, investigationId: {}, issueType: {}", 
                run.getId(), investigationId, triageResult.getIssueType());
        
        return CompletableFuture.supplyAsync(() -> {
            try (TraceRecorder tracer = traceRecorderFactory.create(run)) {
                String parentId = tracer.begin("INVESTIGATION");
                
                // 1. 策略选择：根据 TriageResult 动态选择策略
                InvestigationStrategy strategy = strategySelector.selectStrategy(triageResult);
                log.info("选择排查策略 - strategy: {}, priority: {}, issueType: {}", 
                        strategy.getName(), triageResult.getPriority(), triageResult.getIssueType());
                
                // 2. 执行排查策略
                InvestigationResult result = strategy.execute(run, triageResult, tracer);
                
                // 3. 记录结果
                tracer.end(parentId, result.getSummary(), result.hasError() ? result.getError() : null);
                
                // 4. 发布完成事件
                eventPublisher.publishInvestigationCompleted(run, triageResult, result);
                
                return result;
                
            } catch (Exception e) {
                log.error("排查阶段执行失败 - runId: {}", run.getId(), e);
                return InvestigationResult.failed(run.getId(), e.getMessage());
            }
        });
    }
}