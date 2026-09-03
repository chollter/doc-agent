package com.gcll.docagent.analysis;

/**
 * 面试杠杆卡——把"简历是面试剧本"显式建模。
 * <p>P11 的 enhancedKeyPoints.interviewValue / enhancedRisks.challengeAngle
 * 是两个自由文本字段，前端无法结构化展示"面试官会问什么、我怎么答"。
 * P12 统一为杠杆卡：亮点给"追问预测+准备提示"，风险给"挑战问题+防御策略"，
 * 直接喂给追问对话层做面试预演。
 */
public record LeverageCard(
        Kind kind,
        String point,
        String sectionId,
        String likelyQuestion,
        String prepHint,
        String defenseStrategy
) {

    /** 亮点（可展开的钩子）或风险（会被挑战的模糊处）。 */
    public enum Kind { STRENGTH, RISK }

    public static LeverageCard strength(String point, String sectionId,
                                        String likelyQuestion, String prepHint) {
        return new LeverageCard(Kind.STRENGTH, point, sectionId, likelyQuestion, prepHint, null);
    }

    public static LeverageCard risk(String point, String sectionId,
                                    String challengeQuestion, String defenseStrategy) {
        return new LeverageCard(Kind.RISK, point, sectionId, challengeQuestion, null, defenseStrategy);
    }
}
