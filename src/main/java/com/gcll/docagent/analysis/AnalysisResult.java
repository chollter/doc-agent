package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 文档分析的结构化结果（ReAct / 直连 LLM / 规则兜底三条路径统一产出此模型）。
 * <p>P10 起支持岗位匹配模式：提供 JD 时产出 matchDimensions / gaps / interviewQuestions。
 * <p>P11 起携带语义实体和模式检查结果，支撑简历深度分析。
 * <p>简历深度分析字段：profile（结构化画像）、qualityScore（质量评分）、
 * actionableSuggestions（精准建议）、enhancedKeyPoints/enhancedRisks（增强亮点与风险）。
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
        List<InterviewQuestion> interviewQuestions,
        // ---- 语义实体和模式检查（简历分析时产出） ----
        List<ResumeEntity> entities,
        List<String> patternFindings,
        // ---- 简历深度分析（resume-review skill 产出） ----
        ResumeProfile profile,
        QualityScore qualityScore,
        List<ActionableSuggestion> actionableSuggestions,
        List<EnhancedKeyPoint> enhancedKeyPoints,
        List<EnhancedRisk> enhancedRisks
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

    /** 增强亮点——带原文证据和面试价值判断。 */
    public record EnhancedKeyPoint(String point, String evidence, String sectionId, String interviewValue) {
    }

    /** 增强风险——带具体细节和面试官挑战角度。 */
    public record EnhancedRisk(String risk, String detail, String sectionId, String challengeAngle) {
    }

    public static AnalysisResult empty() {
        return new AnalysisResult("", List.of(), List.of(), List.of(), List.of(),
                null, null, null, null, null,
                null, null, null, null, null);
    }

    public AnalysisResult withCitations(List<Citation> newCitations) {
        return new AnalysisResult(summary, keyPoints, risks, suggestions, newCitations,
                matchDimensions, gaps, interviewQuestions, entities, patternFindings,
                profile, qualityScore, actionableSuggestions, enhancedKeyPoints, enhancedRisks);
    }

    public AnalysisResult withEntitiesAndFindings(List<ResumeEntity> newEntities, List<String> newFindings) {
        return new AnalysisResult(summary, keyPoints, risks, suggestions, citations,
                matchDimensions, gaps, interviewQuestions, newEntities, newFindings,
                profile, qualityScore, actionableSuggestions, enhancedKeyPoints, enhancedRisks);
    }

    public AnalysisResult withResumeDeepAnalysis(ResumeProfile newProfile, QualityScore newQualityScore,
                                                  List<ActionableSuggestion> newSuggestions,
                                                  List<EnhancedKeyPoint> newKeyPoints,
                                                  List<EnhancedRisk> newRisks) {
        return new AnalysisResult(summary, keyPoints, risks, suggestions, citations,
                matchDimensions, gaps, interviewQuestions, entities, patternFindings,
                newProfile, newQualityScore, newSuggestions, newKeyPoints, newRisks);
    }
}
