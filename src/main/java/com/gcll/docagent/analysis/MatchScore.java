package com.gcll.docagent.analysis;

import java.util.Map;

/**
 * 匹配度评分：综合覆盖度、证据强度、风险扣分的最终量化结果
 */
public record MatchScore(
        int overall,
        Map<String, Integer> breakdown
) {
    public static MatchScore empty() {
        return new MatchScore(0, Map.of());
    }
}
