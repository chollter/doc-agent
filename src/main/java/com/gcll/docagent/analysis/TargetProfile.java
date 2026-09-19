package com.gcll.docagent.analysis;

import java.util.List;

/**
 * 进入证据匹配前的统一岗位标准。
 *
 * <p>方向模式由受控的 {@link Archetype} 转换而来；JD 模式只提取用户给出的 JD，
 * 两者后续使用同一份 requirement/variant 定义，避免主分析模型自行决定检查项。
 */
public record TargetProfile(
        String mode,
        String profileId,
        String title,
        String summary,
        List<Requirement> requirements,
        List<Variant> variants,
        List<String> screeningQuestions,
        boolean degraded
) {
    public static final String MODE_JD = "JD";
    public static final String MODE_DIRECTION = "DIRECTION";

    public TargetProfile {
        requirements = requirements == null ? List.of() : List.copyOf(requirements);
        variants = variants == null ? List.of() : List.copyOf(variants);
        screeningQuestions = screeningQuestions == null ? List.of() : List.copyOf(screeningQuestions);
    }

    public static TargetProfile fromArchetype(Archetype archetype) {
        if (archetype == null) return null;
        return new TargetProfile(MODE_DIRECTION, archetype.getId(), archetype.getName(), archetype.getSummary(),
                archetype.getMustHaves().stream()
                        .map(m -> new Requirement(m.getId(), m.getRequirement(), m.getPriority(),
                                m.getEvidenceExpected(), m.getEvidenceHints(), false))
                        .toList(),
                archetype.getVariants().stream()
                        .map(v -> new Variant(v.getId(), v.getName(), v.getDifferentiators()))
                        .toList(),
                archetype.getScreeningQuestions(), false);
    }

    /** JD 标准化不可用时的显式降级，绝不伪造要求。 */
    public static TargetProfile jdFallback() {
        return new TargetProfile(MODE_JD, null, null, null, List.of(), List.of(), List.of(), true);
    }

    public record Requirement(
            String id,
            String requirement,
            String priority,
            List<String> evidenceExpected,
            List<String> keywords,
            boolean disqualifier
    ) {
        public Requirement {
            evidenceExpected = evidenceExpected == null ? List.of() : List.copyOf(evidenceExpected);
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
        }
    }

    public record Variant(String id, String name, List<String> differentiators) {
        public Variant {
            differentiators = differentiators == null ? List.of() : List.copyOf(differentiators);
        }
    }
}
