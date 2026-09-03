package com.gcll.docagent.analysis;

import java.util.List;

/**
 * 表达质量——P11 的 clarity 与 professionalism 合并为一维。
 * <p>两者在语义上高度重叠（格式乱既不清晰也不专业），拆开是假精度，
 * 还让 LLM 各打一次分向示例值聚集。合并后配锚定 rubric（见 prompt v2），
 * 档位映射为纯代码，供 P7 缺陷注入评测断言。
 */
public record Presentation(int score, Band band, List<String> issues) {

    public enum Band { A, B, C, D }

    public static Presentation of(Integer score, List<String> issues) {
        int s = score == null ? 0 : Math.max(0, Math.min(100, score));
        return new Presentation(s, bandOf(s), issues == null ? List.of() : issues);
    }

    /** 档位映射：A(≥85) 表达专业无障碍 / B(≥70) 有瑕疵不影响理解 / C(≥55) 明显拖累阅读 / D 其余。 */
    public static Band bandOf(int score) {
        if (score >= 85) return Band.A;
        if (score >= 70) return Band.B;
        if (score >= 55) return Band.C;
        return Band.D;
    }
}
