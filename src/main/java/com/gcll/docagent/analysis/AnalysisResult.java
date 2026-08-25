package com.gcll.docagent.analysis;

import java.util.List;

/**
 * 文档分析的结构化结果（ReAct / 直连 LLM / 规则兜底三条路径统一产出此模型）。
 */
public record AnalysisResult(
        String summary,
        List<String> keyPoints,
        List<String> risks,
        List<String> suggestions,
        List<Citation> citations
) {

    /**
     * 引用——结论与原文的锚点。
     *
     * @param sectionId 文档节 ID（如 sec-3），经 CITATION_VERIFY 校验必然真实存在
     * @param quote     原文短句
     */
    public record Citation(String sectionId, String quote) {
    }

    public static AnalysisResult empty() {
        return new AnalysisResult("", List.of(), List.of(), List.of(), List.of());
    }
}
