package com.gcll.ticketagent.investigation;

import com.gcll.ticketagent.domain.AgentRun;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 排查结果
 * 
 * 核心产出：证据包，初步诊断作为低置信度参考
 */
@Data
@Builder
public class InvestigationResult {
    
    private final String investigationId;
    private final String runId;
    private final Instant completedAt;
    private final String strategy;
    private final String summary;
    private final String preliminaryDiagnosis; // 初步诊断（低置信度参考）
    private final List<Evidence> evidences; // 证据包
    private final Map<String, Object> metadata; // 元数据
    private final String error;
    private final boolean success;
    
    /**
     * 证据项
     */
    @Data
    @Builder
    public static class Evidence {
        private final String type; // 证据类型：RAG, TOOL, ANALYSIS, etc.
        private final String source; // 证据来源
        private final String content; // 证据内容
        private final double confidence; // 置信度 0.0-1.0
        private final Instant timestamp;
    }
    
    /**
     * 创建成功结果
     */
    public static InvestigationResult success(String runId, String strategy, String summary, 
                                           String preliminaryDiagnosis, List<Evidence> evidences, 
                                           Map<String, Object> metadata) {
        return InvestigationResult.builder()
                .investigationId("investigation-" + runId + "-" + System.currentTimeMillis())
                .runId(runId)
                .completedAt(Instant.now())
                .strategy(strategy)
                .summary(summary)
                .preliminaryDiagnosis(preliminaryDiagnosis)
                .evidences(evidences)
                .metadata(metadata)
                .error(null)
                .success(true)
                .build();
    }
    
    /**
     * 创建失败结果
     */
    public static InvestigationResult failed(String runId, String error) {
        return InvestigationResult.builder()
                .investigationId("investigation-" + runId + "-" + System.currentTimeMillis())
                .runId(runId)
                .completedAt(Instant.now())
                .strategy(null)
                .summary(null)
                .preliminaryDiagnosis(null)
                .evidences(null)
                .metadata(null)
                .error(error)
                .success(false)
                .build();
    }
    
    /**
     * 是否有错误
     */
    public boolean hasError() {
        return error != null;
    }
}