package com.gcll.docagent.analysis;

import java.util.List;

/**
 * 确定性迭代报告——新版简历对着用户确认的上一版，纯代码 diff 出的"这次改了什么、有没有变好"。
 *
 * <p>Phase 2 S1 的核心产出。刻意不掺 LLM：红旗增删、档位迁移、分数变化、维度变化、
 * 改法落地检测全部可由两个已落库的 {@link AnalysisResult} 直接算出，零 token、可复现、可单测钉死。
 * LLM 增量评估（只重评变化区域）是 Phase 2 S3，建立在这份确定性骨架之上。
 *
 * <p>{@code base==null || new==null || 任一 funnelVerdict 缺失} 时无法有意义对比，
 * 返回 {@link #unavailable}(degraded=true)，调用方据此提示"缺基线，非真实迭代对比"，不编造变化。
 */
public record IterationReport(
        String baseRunId,
        String newRunId,
        boolean degraded,
        List<RedFlag> redFlagsAdded,
        List<RedFlag> redFlagsRemoved,
        String strengthBandBefore,
        String strengthBandAfter,
        Integer presentationScoreBefore,
        Integer presentationScoreAfter,
        boolean positioningChanged,
        String positioningBefore,
        String positioningAfter,
        List<DimensionChange> dimensionChanges,
        List<SuggestionLanding> suggestionLandings) {

    /** 某定性维度档位迁移（level 变了，或维度本身新增/消失）。 */
    public record DimensionChange(String dimension, String levelBefore, String levelAfter) {
    }

    public enum LandingStatus { LANDED, NOT_LANDED, INDETERMINATE }

    /** 一条改法在新一轮简历里的落地判定。 */
    public record SuggestionLanding(String target, LandingStatus status) {
    }

    public static IterationReport unavailable(String baseRunId, String newRunId) {
        return new IterationReport(baseRunId, newRunId, true,
                List.of(), List.of(), null, null, null, null,
                false, null, null, List.of(), List.of());
    }
}
