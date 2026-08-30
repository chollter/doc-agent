package com.gcll.docagent.analysis;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历质量评分器——混合计算 5 维评分。
 * <p>quantification 和 completeness 由代码确定性计算（基于实体统计）；
 * clarity / credibility / professionalism 由 LLM 评估输出（从 AnalysisResult 中读取）。
 * <p>overall 为加权综合分：quantification 25% + completeness 25% + clarity 20% + credibility 15% + professionalism 15%。
 */
@Component
public class ResumeQualityScorer {

    /** 关键实体类型——简历完整度检查的 5 个维度。 */
    private static final List<ResumeEntity.EntityType> COMPLETENESS_TYPES = List.of(
            ResumeEntity.EntityType.SKILL,
            ResumeEntity.EntityType.TIME_PERIOD,
            ResumeEntity.EntityType.ORGANIZATION,
            ResumeEntity.EntityType.ROLE,
            ResumeEntity.EntityType.EDUCATION
    );

    /**
     * 计算完整质量评分。
     *
     * @param entities           预抽取的简历实体
     * @param llmQualityScores   LLM 输出的 3 维评分（clarity/credibility/professionalism），可为 null
     * @return 5 维质量评分，如果实体为空则返回 null
     */
    public QualityScore score(ResumeEntities entities, Map<String, Integer> llmQualityScores) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }

        Map<String, Integer> dims = new LinkedHashMap<>();

        // 1. 量化程度：METRIC / max(CLAIM, 1) * 100
        int quantification = computeQuantification(entities);
        dims.put(QualityScore.DIM_QUANTIFICATION, quantification);

        // 2. 信息完整度：5 类关键实体各 20 分
        int completeness = computeCompleteness(entities);
        dims.put(QualityScore.DIM_COMPLETENESS, completeness);

        // 3-5. LLM 评估的维度
        if (llmQualityScores != null) {
            for (String key : List.of(QualityScore.DIM_CLARITY, QualityScore.DIM_CREDIBILITY, QualityScore.DIM_PROFESSIONALISM)) {
                Integer val = llmQualityScores.get(key);
                if (val != null) {
                    dims.put(key, clamp(val));
                }
            }
        }

        // 如果 LLM 没有给出评分，用默认值（50 分，不拉高也不拉低）
        dims.putIfAbsent(QualityScore.DIM_CLARITY, 50);
        dims.putIfAbsent(QualityScore.DIM_CREDIBILITY, 50);
        dims.putIfAbsent(QualityScore.DIM_PROFESSIONALISM, 50);

        int overall = QualityScore.computeOverall(dims);
        return new QualityScore(overall, dims);
    }

    /**
     * 量化程度：有数字支撑的声明占比。
     * METRIC 数量 / max(CLAIM 数量, 1) * 100，上限 100。
     */
    private int computeQuantification(ResumeEntities entities) {
        List<ResumeEntity> claims = entities.getByType(ResumeEntity.EntityType.CLAIM);
        List<ResumeEntity> metrics = entities.getMetrics();

        if (claims.isEmpty()) {
            // 没有 claim 时，看 metrics 数量给基础分
            return metrics.isEmpty() ? 30 : Math.min(100, metrics.size() * 25);
        }

        double ratio = (double) metrics.size() / claims.size();
        return clamp((int) (ratio * 100));
    }

    /**
     * 信息完整度：检查 5 类关键实体是否存在，每类 20 分。
     */
    private int computeCompleteness(ResumeEntities entities) {
        int score = 0;
        for (ResumeEntity.EntityType type : COMPLETENESS_TYPES) {
            if (!entities.getByType(type).isEmpty()) {
                score += 20;
            }
        }
        return score;
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
