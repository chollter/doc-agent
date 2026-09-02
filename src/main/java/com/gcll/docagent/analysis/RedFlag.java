package com.gcll.docagent.analysis;

/**
 * 简历红旗——一票否决性质的问题信号，独立于评分体系存在。
 * <p>P11 把红旗问题（空窗等）混进加权分里，既稀释信号又给不出"先解决什么"的优先级。
 * P12 起红旗是漏斗第一层：不参与计分，直接决定"会不会被筛掉"。
 * type 是稳定标识，供评测断言与前端路由使用。
 */
public record RedFlag(String type, Severity severity, String message) {

    public enum Severity { HIGH, MEDIUM, LOW }

    /** 稳定类型标识。 */
    public static final String TIMELINE_GAP = "TIMELINE_GAP";
    public static final String JOB_HOPPING = "JOB_HOPPING";
    public static final String OVERLAP = "OVERLAP";
    public static final String TENURE_TITLE_MISMATCH = "TENURE_TITLE_MISMATCH";
    public static final String CONTACT_MISSING = "CONTACT_MISSING";
    public static final String SECTION_MISSING = "SECTION_MISSING";
    public static final String LOW_QUANTIFICATION = "LOW_QUANTIFICATION";
    /** 尾部空窗：最后一段雇佣结束至今无覆盖。 */
    public static final String TRAILING_GAP = "TRAILING_GAP";
    /** 尾部有项目覆盖的离职期：不是空窗，但面试必问（准备信号，非否决项）。 */
    public static final String EMPLOYMENT_GAP_COVERED = "EMPLOYMENT_GAP_COVERED";
}
