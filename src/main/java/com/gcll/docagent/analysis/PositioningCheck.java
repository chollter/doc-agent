package com.gcll.docagent.analysis;

/**
 * 定位清晰度检查——广撒网简历最常见的死法不是缺技能，而是招聘方 6 秒内
 * 看不出"这个人是干嘛的"。"后端开发，也了解 AI"输给"AI 应用工程师，后端背景"。
 */
public record PositioningCheck(boolean anchored, String currentAnchor, String suggestedAnchor, String comment) {
}
