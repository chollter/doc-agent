package com.gcll.docagent.analysis;

import java.util.List;

/** 采纳结果：修改稿 + 成功/失锚明细。 */
public record AppliedRevision(String revisedMarkdown, int appliedCount, int requestedCount,
                              List<String> missingBefores, List<String> changedSectionIds) {
}
