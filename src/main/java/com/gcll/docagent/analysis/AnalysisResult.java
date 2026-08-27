package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 文档分析的结构化结果（ReAct / 直连 LLM / 规则兜底三条路径统一产出此模型）。
 * <p>P10 起支持岗位匹配模式：提供 JD 时产出 matchDimensions / gaps / interviewQuestions。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisResult(
        String summary,
        List<String> keyPoints,
        List<String> risks,
        List<String> suggestions,
        List<Citation> citations,
        // ---- 岗位匹配模式（resume-review skill + JD 时产出） ----
        List<MatchDimension> matchDimensions,
        List<Gap> gaps,
        List<InterviewQuestion> interviewQuestions
) {

    /**
     * 引用——结论与原文的锚点。
     *
     * @param sectionId 文档节 ID（如 sec-3），经 CITATION_VERIFY 校验必然真实存在
     * @param quote     原文短句
     */
    public record Citation(String sectionId, String quote) {
    }

    /** 匹配维度评分（硬技能/经验深度/加分项等）。 */
    public record MatchDimension(String name, String level, String reason) {
    }

    /** JD 要求与简历的差距：每条差距必须对应一条建议。 */
    public record Gap(String requirement, String gap, String suggestion) {
    }

    /** 面试题预测：基于简历真实 claim 或 JD 要求生成，回答建议须标注是否为差距准备。 */
    public record InterviewQuestion(String question, String intent, String suggestedAnswer, boolean isGapPrep) {
    }

    public static AnalysisResult empty() {
        return new AnalysisResult("", List.of(), List.of(), List.of(), List.of(), null, null, null);
    }

    public AnalysisResult withCitations(List<Citation> newCitations) {
        return new AnalysisResult(summary, keyPoints, risks, suggestions, newCitations,
                matchDimensions, gaps, interviewQuestions);
    }
}
