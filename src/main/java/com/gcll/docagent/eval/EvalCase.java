package com.gcll.docagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 评测用例——固定输入 + 结果断言，让"引用防幻觉""降级可靠"这类主张变得可度量。
 * <p>断言设计为模式无关（REACT/LLM/FALLBACK 皆须通过），保证无 API Key 的 CI 环境
 * 也能跑通全量回归；接入真实 Key 时同一批用例自动验证更强路径。
 * <p>P10 起支持配对缺陷注入：简历×JD 埋已知匹配/差距/幻觉陷阱，
 * 断言差距检出率、匹配维度完整性与面试题 grounding。
 * <p>P12 起支持方向画像模式（targetDirection）与漏斗分角度断言：
 * 红旗类型必现/必不现、强度档位、词汇缺口、覆盖条数，
 * 以及 expectWorseThan 配对单调性——注入缺陷后对应角度必须严格变差，
 * 这是"分数/档位反映好坏"的直接证据。
 *
 * @param name        用例名
 * @param file        classpath 下的文档路径（如 samples/tech-spec.md）
 * @param skill       技能名
 * @param instruction 分析指令
 * @param jobDescription 目标岗位JD（可选；提供时启用匹配分析）
 * @param targetDirection 求职方向短语（可选；无JD时启用方向画像模式）
 * @param calibrationRuns 方差控制：>1 时该用例重复运行并取中位 run 做断言（校准用例建议 3）
 * @param timeoutSeconds 单用例超时
 * @param assertions  结果断言
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EvalCase(
        String name,
        String file,
        String skill,
        String instruction,
        String jobDescription,
        String targetDirection,
        int calibrationRuns,
        int timeoutSeconds,
        Assertions assertions
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Assertions(
            String status,
            List<String> allowedModes,
            Integer minKeyPoints,
            Integer minCitations,
            List<String> keywords,
            // ---- P10: 岗位匹配断言 ----
            Integer minGaps,
            Integer minMatchDimensions,
            Integer minInterviewQuestions,
            List<String> mustContainGapKeywords,
            List<String> mustNotContainGapKeywords,
            // ---- P11 简历深度分析断言（P12 起部分字段由漏斗断言替代） ----
            Integer minActionableSuggestions,
            Integer minEnhancedKeyPoints,
            Boolean hasQualityScore,
            Boolean hasProfile,
            // ---- P12: 漏斗分角度断言 ----
            Boolean hasFunnelVerdict,
            Integer minLeverageCards,
            List<String> mustHaveRedFlagTypes,
            List<String> mustNotHaveRedFlagTypes,
            String strengthBandAtMost,
            List<String> mustContainVocabularyTerms,
            String matchMode,
            Integer minCoverageMet,
            Integer maxCoverageMet,
            /** 配对单调性：本用例的强度档位必须严格差于指定基线用例。 */
            String expectWorseThan
    ) {
    }
}
