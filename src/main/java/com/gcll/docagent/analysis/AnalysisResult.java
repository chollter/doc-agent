package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 文档分析的结构化结果（ReAct / 直连 LLM / 规则兜底三条路径统一产出此模型）。
 * <p>P12 起主结果为 funnelVerdict（漏斗式五角度结论）。中间产物（实体、项目事实、
 * 对齐矩阵、引用明细等）不再随结果落库——requirementVerdicts 已是其人读汇总。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisResult(
        String summary,
        List<String> keyPoints,
        List<String> risks,
        List<Citation> citations,
        // ---- 候选人画像 ----
        ResumeProfile profile,
        // ---- 可执行建议（before→after，采纳建议的数据源） ----
        List<ActionableSuggestion> actionableSuggestions,
        // ---- P12 漏斗式结论（主结果） ----
        FunnelVerdict funnelVerdict
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
        return new AnalysisResult("", List.of(), List.of(), List.of(),
                null, null, null);
    }

    public AnalysisResult withCitations(List<Citation> newCitations) {
        return new AnalysisResult(summary, keyPoints, risks, newCitations,
                profile, actionableSuggestions, funnelVerdict);
    }

    public AnalysisResult withSummary(String newSummary) {
        return new AnalysisResult(newSummary, keyPoints, risks, citations,
                profile, actionableSuggestions, funnelVerdict);
    }

    public AnalysisResult withProfile(ResumeProfile newProfile) {
        return new AnalysisResult(summary, keyPoints, risks, citations,
                newProfile, actionableSuggestions, funnelVerdict);
    }

    public AnalysisResult withActionableSuggestions(List<ActionableSuggestion> newSuggestions) {
        return new AnalysisResult(summary, keyPoints, risks, citations,
                profile, newSuggestions, funnelVerdict);
    }

    public AnalysisResult withFunnelVerdict(FunnelVerdict verdict) {
        return new AnalysisResult(summary, keyPoints, risks, citations,
                profile, actionableSuggestions, verdict);
    }
}
