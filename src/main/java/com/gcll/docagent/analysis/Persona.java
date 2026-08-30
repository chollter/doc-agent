package com.gcll.docagent.analysis;

/**
 * 候选人人群预设——不同人群的红旗阈值与匹配权重不同。
 * <p>P11 的五维评分对所有人一套权重，应届的教育信号和资深的稳定性信号被同等对待。
 * P12 起人群是显式输入（用户可选，默认自动推断），影响红旗阈值而非重新打分。
 */
public enum Persona {
    /** 默认/未指定。 */
    GENERAL,
    /** 应届/初级：实习短任期不视为跳槽，教育信号权重高。 */
    NEW_GRAD,
    /** 资深：稳定性与领导力要求更严，教育缺失仅提示。 */
    SENIOR,
    /** 转行：可迁移证据优先，旧领域经历不计入相关性。 */
    CAREER_SWITCH;

    /** 按总工作年限与教育时间自动推断人群。 */
    public static Persona infer(int yearsOfExperience, boolean recentGraduate) {
        if (recentGraduate || yearsOfExperience <= 1) {
            return NEW_GRAD;
        }
        if (yearsOfExperience >= 8) {
            return SENIOR;
        }
        return GENERAL;
    }
}
