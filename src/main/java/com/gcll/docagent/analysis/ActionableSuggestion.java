package com.gcll.docagent.analysis;

/**
 * 精准可执行建议——定位到简历具体段落，给出 before/after 改写对比。
 * <p>与普通 suggestions（纯文本方向性建议）的区别：
 * <ul>
 *   <li>target 定位到具体段落（如"工作经历-订单系统重构"）</li>
 *   <li>before 引用简历原文</li>
 *   <li>after 给出改写后的版本（更量化、更有说服力）</li>
 *   <li>severity 按影响力排序（HIGH &gt; MEDIUM &gt; LOW）</li>
 * </ul>
 */
public record ActionableSuggestion(
        String severity,
        String target,
        String sectionId,
        String before,
        String after,
        String reason
) {}
