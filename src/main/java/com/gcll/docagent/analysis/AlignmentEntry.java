package com.gcll.docagent.analysis;

import java.util.List;

/**
 * 对齐条目：一条JD/方向要求在简历中的完整匹配结果。
 * <p>核心思路：给定要求 → 在简历中找证据 → 判断覆盖状态 → 生成差距描述 → 给出改进建议。
 * <p>这是匹配分析的最小闭环单元，多条对齐条目组成对齐矩阵。
 */
public record AlignmentEntry(
        String requirementId,
        String requirement,
        String priority,
        MustHaveCoverage.Status status,
        List<EvidenceAssessment> evidence,
        double coverage,
        String gap,
        SuggestionFix fix
) {
    public AlignmentEntry {
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /**
     * 改进建议：类型+改写对比+ROI。
     */
    public record SuggestionFix(
            FixType type,
            String before,
            String after,
            int roiScore,
            String effort,
            String reason
    ) {}

    public enum FixType {
        /** 增强已有内容（补充细节、量化数据） */
        ENHANCE,
        /** 新增案例（简历缺失，需要补充真实经历） */
        ADD,
        /** 重新表述（换个说法以命中关键词） */
        REFRAME
    }
}
