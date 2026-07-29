package com.gcll.ticketagent.investigation.strategy.consult;

import com.gcll.ticketagent.domain.AgentRun;
import com.gcll.ticketagent.triage.TriageResult;
import com.gcll.ticketagent.investigation.InvestigationResult;
import com.gcll.ticketagent.investigation.strategy.InvestigationStrategy;
import com.gcll.ticketagent.investigation.strategy.InvestigationStrategyType;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.knowledge.KnowledgeSearchService;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 咨询模式策略
 * 
 * 特点：只查 RAG，证据明确，P2/P3 优先级
 * 流程：
 * 1. RAG 查询相关知识
 * 2. 整理证据包
 * 3. 生成初步诊断
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsultInvestigationStrategy implements InvestigationStrategy {

    private final KnowledgeSearchService knowledgeSearchService;

    @Override
    public String getName() {
        return "ConsultInvestigationStrategy";
    }

    @Override
    public InvestigationStrategyType getType() {
        return InvestigationStrategyType.CONSULT;
    }

    @Override
    public boolean supports(TriageResult triageResult) {
        // 咨询模式支持 P2/P3 优先级
        return "P2".equals(triageResult.priority().name()) || "P3".equals(triageResult.priority().name());
    }

    @Override
    public InvestigationResult execute(AgentRun run, TriageResult triageResult, TraceRecorder tracer) {
        String stepId = tracer.begin("CONSULT_RAG_SEARCH");
        
        try {
            // 1. RAG 查询相关知识
            List<KnowledgeHit> searchResults = knowledgeSearchService.search(
                    triageResult.issueType().toString(), 
                    triageResult.affectedSystem(), 
                    triageResult.affectedModule(), 
                    triageResult.issueType().toString()
            );
            
            // 记录输入
            tracer.recordInput(stepId, "查询问题: " + triageResult.issueType().toString() + " - " + triageResult.affectedSystem());
            
            // 2. 整理证据包
            List<InvestigationResult.Evidence> evidences = searchResults.stream()
                    .map(result -> InvestigationResult.Evidence.builder()
                            .type("RAG")
                            .source(result.sourceId())
                            .content(result.summary())
                            .confidence(result.score())
                            .timestamp(Instant.now())
                            .build())
                    .toList();
            
            // 3. 生成初步诊断
            String preliminaryDiagnosis = generatePreliminaryDiagnosis(searchResults, triageResult);
            
            // 4. 生成总结
            String summary = String.format("咨询模式完成，找到 %d 条相关知识，初步诊断: %s", 
                    searchResults.size(), preliminaryDiagnosis);
            
            tracer.recordMeta(stepId, true, null);
            tracer.end(stepId, summary, null);
            
            return InvestigationResult.success(
                    run.getId(),
                    getName(),
                    summary,
                    preliminaryDiagnosis,
                    evidences,
                    Map.of("searchResultsCount", searchResults.size())
            );
            
        } catch (Exception e) {
            log.error("咨询模式执行失败 - runId: {}", run.getId(), e);
            tracer.end(stepId, null, e.getMessage());
            return InvestigationResult.failed(run.getId(), e.getMessage());
        }
    }
    
    /**
     * 生成初步诊断
     */
    private String generatePreliminaryDiagnosis(List<KnowledgeSearchService.SearchResult> searchResults, TriageResult triageResult) {
        if (searchResults.isEmpty()) {
            return "暂无相关知识，建议人工介入";
        }
        
        // 基于搜索结果生成初步诊断
        double avgConfidence = searchResults.stream()
                .mapToDouble(KnowledgeSearchService.SearchResult::getScore)
                .average()
                .orElse(0.0);
        
        if (avgConfidence > 0.8) {
            return "高置信度匹配，建议按解决方案执行";
        } else if (avgConfidence > 0.6) {
            return "中等置信度，建议参考解决方案";
        } else {
            return "低置信度，建议结合人工判断";
        }
    }
}