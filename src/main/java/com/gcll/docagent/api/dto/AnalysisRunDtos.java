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
            String fileType,
            String instruction,
            String status,
            String executionMode,
            Integer sectionCount,
            Instant createdAt,
            Instant finishedAt
    ) {
    }

    /** 单个 run 详情（结果以解析后的 JSON 嵌入，前端直接渲染）。 */
    public record Detail(
            String runId,
            String fileName,
            String fileType,
            String instruction,
            String status,
            String executionMode,
            Integer sectionCount,
            String summary,
            JsonNode result,
            String lastError,
            Instant createdAt,
            Instant finishedAt
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
}
