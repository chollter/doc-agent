package com.gcll.docagent.analysis;

import java.util.List;

/**
 * 岗位要求的唯一裁决结果。
 *
 * <p>LLM 产出的 {@link MustHaveCoverage} 只是候选判断；只有经过原文回锚和
 * 证据等级上限校验后，才能形成 RequirementVerdict。报告、匹配档位和评价均应
 * 读取本对象，不能再次读取未经裁决的候选状态。</p>
 */
public record RequirementVerdict(
        String requirementId,
        String requirement,
        String priority,
        MustHaveCoverage.Status status,
        EvidenceLevel evidenceLevel,
        String claim,
        List<String> supportingEvidence,
        List<String> sectionIds,
        List<String> missingFacts,
        String reason,
        String interviewQuestion,
        boolean hasOriginalBasis
) {
    public RequirementVerdict {
        supportingEvidence = supportingEvidence == null ? List.of() : List.copyOf(supportingEvidence);
        sectionIds = sectionIds == null ? List.of() : List.copyOf(sectionIds);
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
    }

    /** 兼容旧 API 的派生视图；状态只能来自已完成的裁决。 */
    public MustHaveCoverage toCoverage() {
        return new MustHaveCoverage(requirementId, requirement, status,
                supportingEvidence.stream().findFirst().orElse(null),
                sectionIds.stream().findFirst().orElse(null));
    }
}
