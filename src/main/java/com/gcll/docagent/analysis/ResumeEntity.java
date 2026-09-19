package com.gcll.docagent.analysis;

import java.util.List;
import java.util.Map;

/**
 * 语义实体：从简历中提取的原子信息单元。
 * 不假设简历格式，只提取"简历里出现了什么"。
 */
public record ResumeEntity(
        EntityType type,
        String value,
        String context,
        Map<String, String> attributes
) {
    public enum EntityType {
        SKILL,
        TIME_PERIOD,
        ORGANIZATION,
        ROLE,
        METRIC,
        CLAIM,
        ACHIEVEMENT,
        CERTIFICATION,
        EDUCATION,
        GAP,
        /** 聚合工作经历条目（公司+角色+时间段+核心产出），由实体抽取 prompt 产出。 */
        WORK_ENTRY
    }

    /** 时间段归属流（work/education/project），由抽取 prompt 标注；缺失时视为未知。 */
    public String kind() {
        return attributes != null ? attributes.get("kind") : null;
    }

    /** Source section assigned by the extraction contract, e.g. sec-3. */
    public String sectionId() {
        return attributes != null ? attributes.get("sectionId") : null;
    }

    /** Continuous source quote used to audit a claim without trusting its value. */
    public String sourceQuote() {
        return attributes != null ? attributes.get("sourceQuote") : null;
    }

    public static ResumeEntity of(EntityType type, String value, String context) {
        return new ResumeEntity(type, value, context, Map.of());
    }

    public static ResumeEntity of(EntityType type, String value, String context, Map<String, String> attributes) {
        return new ResumeEntity(type, value, context, attributes);
    }

    public static List<ResumeEntity> filterByType(List<ResumeEntity> entities, EntityType type) {
        return entities.stream().filter(e -> e.type() == type).toList();
    }
}
