package com.gcll.docagent.analysis;

import java.util.Map;

/**
 * 单条经历的强度评估——LLM 逐条判断，代码聚合成 StrengthStats。
 * <p>替代 P11 的 quantification 维度：数字密度（METRIC/CLAIM）可被
 * "参加 3 次会议"这类无意义数字刷分；STAR 完整度 + 结果影响范围 + 归因清晰度
 * 度量的是证据质量——塞数字但 Result 空洞、或"主导"撑不起结果的条目照样露馅。
 */
public record ExperienceStrength(
        String sectionId,
        String entryRef,
        Map<String, Boolean> star,
        ResultQuality resultQuality,
        Attribution attribution,
        String concern
) {

    /** 结果的影响范围。 */
    public enum ResultQuality { NONE, TASK, PROJECT, BUSINESS }

    /** 候选人在该条经历中的归因强度。 */
    public enum Attribution { OBSERVER, PARTICIPANT, OWNER, LEAD }

    public boolean hasResult() {
        return star != null && Boolean.TRUE.equals(star.get("result"));
    }

    public boolean isStrongResult() {
        return resultQuality == ResultQuality.PROJECT || resultQuality == ResultQuality.BUSINESS;
    }

    public boolean isOwnerLevel() {
        return attribution == Attribution.OWNER || attribution == Attribution.LEAD;
    }
}
