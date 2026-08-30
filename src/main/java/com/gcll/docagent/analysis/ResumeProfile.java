package com.gcll.docagent.analysis;

import java.util.List;

/**
 * 结构化候选人画像——从 ResumeEntities 聚合而来（纯代码，不依赖 LLM）。
 * <p>把扁平的实体列表组装成面试官/HR 想看的结构化视图。
 */
public record ResumeProfile(
        String name,
        int yearsOfExperience,
        String currentRole,
        List<WorkEntry> workTimeline,
        SkillMatrix skillMatrix,
        List<EducationEntry> education
) {

    /** 工作经历条目。 */
    public record WorkEntry(
            String company,
            String role,
            String period,
            int durationMonths,
            List<String> highlights
    ) {}

    /** 教育经历条目。 */
    public record EducationEntry(
            String school,
            String degree,
            String major,
            String year
    ) {}
}
