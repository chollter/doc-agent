package com.gcll.docagent.analysis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 要求全集合并：以 {@link TargetProfile#requirements()} 为基准输出，
 * LLM 漏项不会被静默丢弃——未返回的要求以 {@link MustHaveCoverage.Status#MISSING} 补齐，
 * 保证匹配分母完整；LLM 返回但画像中不存在的条目追加在末尾以保持兼容。
 */
final class CoverageMerge {

    private CoverageMerge() {
    }

    static List<MustHaveCoverage> merge(TargetProfile target, List<MustHaveCoverage> llm) {
        if (target == null) {
            return llm;
        }
        List<MustHaveCoverage> llmList = llm == null ? List.of() : llm;
        List<MustHaveCoverage> merged = new ArrayList<>();
        Set<String> consumed = new HashSet<>();
        for (TargetProfile.Requirement requirement : target.requirements()) {
            MustHaveCoverage match = findById(llmList, requirement.id());
            if (match != null) {
                consumed.add(match.requirementId());
                merged.add(new MustHaveCoverage(match.requirementId(),
                        requirement.requirement(),
                        match.status(), match.evidence(), match.sectionId()));
            } else {
                // 画像中存在但模型未返回：补 MISSING，不退出匹配分母。
                merged.add(new MustHaveCoverage(requirement.id(), requirement.requirement(),
                        MustHaveCoverage.Status.MISSING, null, null));
            }
        }
        for (MustHaveCoverage c : llmList) {
            if (c != null && !consumed.contains(c.requirementId())) {
                merged.add(c);
            }
        }
        return merged;
    }

    private static MustHaveCoverage findById(List<MustHaveCoverage> list, String id) {
        for (MustHaveCoverage c : list) {
            if (c != null && id != null && id.equals(c.requirementId())) {
                return c;
            }
        }
        return null;
    }
}
