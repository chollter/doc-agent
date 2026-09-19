package com.gcll.docagent.analysis;

/**
 * 精准可执行建议——定位到简历具体段落，给出 before/after 改写对比。
 * <p>与普通 suggestions（纯文本方向性建议）的区别：
 * <ul>
 *   <li>target 定位到具体段落（如"工作经历-订单系统重构"）</li>
 *   <li>before 引用简历原文</li>
 *   <li>after 给出改写后的版本（更量化、更有说服力）</li>
 *   <li>severity 按影响力排序（HIGH &gt; MEDIUM &gt; LOW）</li>
 *   <li>roiScore ROI分数（用于优先级排序）</li>
 *   <li>fixType 改进类型（ENHANCE/ADD/REFRAME）</li>
 *   <li>effort 工作量（LOW/MEDIUM/HIGH）</li>
 * </ul>
 */
public record ActionableSuggestion(
        String severity,
        String target,
        String sectionId,
        String before,
        String after,
        String reason,
        Integer roiScore,
        String fixType,
        String effort
) {
    public ActionableSuggestion(String severity, String target, String sectionId,
                               String before, String after, String reason) {
        this(severity, target, sectionId, before, after, reason, null, null, null);
    }

    public ActionableSuggestion withRoi(int roi) {
        return new ActionableSuggestion(severity, target, sectionId, before, after, reason,
                roi, fixType, effort);
    }

    public ActionableSuggestion withFix(String type, String effortLevel) {
        return new ActionableSuggestion(severity, target, sectionId, before, after, reason,
                roiScore, type, effortLevel);
    }
}
