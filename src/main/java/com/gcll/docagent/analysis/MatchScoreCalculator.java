package com.gcll.docagent.analysis;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 匹配度计算器：基于对齐矩阵和红旗，量化简历与目标的匹配程度
 * 公式：匹配度 = (覆盖度得分 × 证据强度系数) - 风险扣分
 */
@Service
public class MatchScoreCalculator {

    /**
     * 计算匹配度
     * @param requirementVerdicts 要求裁决列表
     * @param evidenceAssessments 证据评估列表
     * @param redFlags 红旗列表
     * @return 0-100分的匹配度及分项明细
     */
    public MatchScore calculate(List<RequirementVerdict> requirementVerdicts,
                               List<EvidenceAssessment> evidenceAssessments,
                               List<RedFlag> redFlags) {
        if (requirementVerdicts == null || requirementVerdicts.isEmpty()) {
            return MatchScore.empty();
        }

        // 1. 覆盖度得分（0-100）：MET=1.0, PARTIAL=0.5, MISSING=0.0
        double coverageScore = requirementVerdicts.stream()
                .mapToDouble(verdict -> switch (verdict.status()) {
                    case MET -> 1.0;
                    case PARTIAL -> 0.5;
                    case MISSING -> 0.0;
                })
                .average()
                .orElse(0.0) * 100;

        // 2. 证据强度系数（0.25-1.0）：基于所有证据的平均等级
        double avgEvidenceLevel = evidenceAssessments != null ? evidenceAssessments.stream()
                .filter(ev -> ev.evidenceLevel() != null)
                .mapToInt(ev -> ev.evidenceLevel().ordinal())
                .average()
                .orElse(0.0) : 0.0;
        // L0=0, L1=1, L2=2, L3=3, L4=4 → 除以4得到0-1系数
        // 最低0.25避免有证据但全是L0时得分归零
        double strengthMultiplier = Math.max(0.25, avgEvidenceLevel / 4.0);

        // 3. 风险扣分：HIGH红旗-10分，MEDIUM红旗-5分
        int riskPenalty = redFlags != null ? redFlags.stream()
                .mapToInt(flag -> switch (flag.severity()) {
                    case HIGH -> 10;
                    case MEDIUM -> 5;
                    case LOW -> 0;
                })
                .sum() : 0;

        // 4. 最终分数：覆盖度 × 强度 - 风险，钳制到0-100
        int overall = Math.max(0, Math.min(100,
                (int) (coverageScore * strengthMultiplier) - riskPenalty));

        // 5. 分项明细（用于前端展示雷达图）
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("覆盖度", (int) coverageScore);
        breakdown.put("证据强度", (int) (strengthMultiplier * 100));
        breakdown.put("风险扣分", -riskPenalty);

        return new MatchScore(overall, breakdown);
    }
}
