package com.gcll.docagent.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * 文档分析 run 的对外 DTO 集合。
 */
public final class AnalysisRunDtos {

    private AnalysisRunDtos() {
    }

    /** POST /api/analysis/runs 的返回（202）。 */
    public record Start(String runId, String status) {
    }

    /** 历史列表条目。 */
    public record Summary(
            String runId,
            String fileName,
            String skill,
            String instruction,
            String status,
            String executionMode,
            Instant createdAt
    ) {
    }

    /** 单个 run 详情（结果以解析后的 JSON 嵌入，前端直接渲染）。 */
    public record Detail(
            String runId,
            String fileName,
            String skill,
            String instruction,
            String status,
            String executionMode,
            String summary,
            JsonNode result,
            String lastError,
            java.util.List<HumanActionDto> pending,
            Instant createdAt,
            Instant finishedAt
    ) {
    }

    /** 追问消息。 */
    public record MessageDto(Integer turn, String role, String content, String createdAt) {
    }

    /** 简历档案条目（历史简历列表——免上传再分析的数据源）。 */
    public record ResumeItem(
            String id,
            String fileName,
            String fileType,
            Integer charCount,
            Integer runCount,
            java.time.LocalDateTime createdAt,
            java.time.LocalDateTime lastUsedAt
    ) {
    }

    /** 文档分节视图（右侧文档面板 + 引用定位）。 */
    public record DocumentView(
            String runId,
            String fileName,
            String fileType,
            Integer sectionCount,
            java.util.List<SectionDto> sections
    ) {
        public record SectionDto(String id, String heading, Integer page, int charCount, String text) {
        }
    }

    /** 优化历史列表条目（含评分明细 + 交互数量）。 */
    public record OptimizationHistoryItem(
            String runId,
            String fileName,
            String skill,
            String promptVersion,
            String optimizationNote,
            Integer scoreOverall,
            String scoreDimensions,
            String executionMode,
            Long tokensUsed,
            int interactionCount,
            Instant createdAt
    ) {
    }

    /** 单次运行链路诊断：阶段状态 + LLM 调用聚合，定位“哪里断了、为什么断”。 */
    public record RunPipelineDto(
            String runId,
            String runStatus,
            String executionMode,
            boolean analysisDegraded,
            String lastError,
            java.util.List<PipelineStage> stages,
            java.util.List<LlmCallGroup> llmCalls
    ) {
        /** 阶段：agent_step 顶层步骤。status 取 SUCCESS/FAILED；detail 为输出摘要。 */
        public record PipelineStage(String stage, String status, String detail, Long costMs, String error) {
        }

        /** 按调用点聚合的 LLM 调用（含失败次数——失败也留痕）。 */
        public record LlmCallGroup(String callSite, int total, int failures) {
        }
    }

    /** LLM 交互详情。 */
    public record LlmInteractionDto(
            String id,
            String callSite,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            String fullPrompt,
            String fullResponse,
            Long durationMs,
            Boolean success,
            String createdAt
    ) {
    }
}
