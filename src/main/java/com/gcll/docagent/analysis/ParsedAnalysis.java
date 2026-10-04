package com.gcll.docagent.analysis;

/**
 * 解析产物：稳定结果 + 五角度漏斗字段（后者只进 FunnelVerdict，不进 AnalysisResult）。
 * <p>由 {@link FunnelFieldsMapper} 产出，被执行引擎（LoopOutcome/LlmOutcome）与结果装配共享。
 */
public record ParsedAnalysis(AnalysisResult analysis, LlmFunnelFields funnel) {
    public static ParsedAnalysis empty() {
        return new ParsedAnalysis(null, LlmFunnelFields.empty());
    }
}
