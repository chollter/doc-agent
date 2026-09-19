package com.gcll.docagent.analysis;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Scores resume evidence from structured project slots, never from keyword lists.
 * A number or verb in free text cannot raise the level; only grounded explicit slots can.
 */
@Service
public class EvidenceAssessmentService {

    public List<EvidenceAssessment> assess(ResumeEntities entities, String originalText) {
        if (entities == null) return List.of();
        String source = originalText == null ? "" : originalText;
        if (!entities.getProjects().isEmpty()) {
            return entities.getProjects().stream()
                    .map(project -> assessProject(project, source))
                    .toList();
        }
        return entities.getAll().stream()
                .filter(e -> e != null && (e.type() == ResumeEntity.EntityType.CLAIM
                        || e.type() == ResumeEntity.EntityType.ACHIEVEMENT
                        || e.type() == ResumeEntity.EntityType.WORK_ENTRY))
                .map(entity -> assessClaim(entity, source))
                .toList();
    }

    public EvidenceAssessment assessProject(ResumeProjectFact project, String originalText) {
        String source = originalText == null ? "" : originalText;
        SlotSignals signals = signalsOf(project, source);
        EvidenceLevel level = levelOf(signals);
        List<String> evidence = collectEvidence(project, source);
        List<String> missing = missingFacts(signals);
        String claim = projectClaim(project);
        return new EvidenceAssessment(claim, project.sectionId(), firstQuote(evidence),
                level, evidence, missing, questions(claim), advice(level, missing), !evidence.isEmpty());
    }

    public EvidenceAssessment assessClaim(ResumeEntity entity, String originalText) {
        String claim = clean(entity.value());
        String context = clean(entity.context());
        String sourceQuote = clean(entity.sourceQuote());
        boolean grounded = containsNormalized(originalText, sourceQuote)
                || containsNormalized(originalText, claim)
                || containsNormalized(originalText, context);
        EvidenceLevel level = grounded ? EvidenceLevel.L1_ACTIVITY : EvidenceLevel.L0_KEYWORD;
        List<String> evidence = new ArrayList<>();
        if (!sourceQuote.isBlank() && containsNormalized(originalText, sourceQuote)) evidence.add(sourceQuote);
        else if (grounded && !claim.isBlank()) evidence.add(claim);
        SlotSignals signals = new SlotSignals(grounded, false, false, false);
        List<String> missing = missingFacts(signals);
        return new EvidenceAssessment(claim, entity.sectionId(), sourceQuote, level,
                evidence, missing, questions(claim), advice(level, missing), grounded);
    }

    /**
     * Joins coverage with grounded evidence. Keyword overlap never upgrades the level.
     */
    public List<RequirementVerdict> assessRequirements(
            List<MustHaveCoverage> coverage, List<EvidenceAssessment> assessments,
            TargetProfile targetProfile) {
        if (coverage == null || coverage.isEmpty()) return List.of();
        List<EvidenceAssessment> available = assessments == null ? List.of() : assessments;
        return coverage.stream().filter(c -> c != null).map(c -> {
            EvidenceAssessment best = available.stream()
                    .filter(a -> matches(c, a))
                    .max(java.util.Comparator.comparingInt(a -> a.evidenceLevel().ordinal()))
                    .orElse(null);
            EvidenceLevel level = best == null ? EvidenceLevel.L0_KEYWORD : best.evidenceLevel();
            // 证据等级钳制最终状态：L0 强制 MISSING，L1 不能 MET。
            MustHaveCoverage.Status clamped = clamp(c.status(), level);
            String claim = best == null ? null : best.claim();
            List<String> missing = best == null
                    ? List.of("与该岗位要求对应的原文经历或成果")
                    : best.missingFacts();
            String question = best == null
                    ? "请说明你是否实际做过“" + c.requirement() + "”，以及可核验的事实"
                    : best.likelyInterviewQuestions().get(0);
            List<String> supporting = best == null ? List.of() : matchingEvidence(c, best);
            List<String> sectionIds = best == null || best.sectionId() == null
                    || best.sectionId().isBlank() ? List.of() : List.of(best.sectionId());
            return new RequirementVerdict(c.requirementId(), c.requirement(),
                    priorityOf(c.requirementId(), targetProfile), clamped, level, claim,
                    supporting, sectionIds, missing, verdictReason(c.status(), clamped, level),
                    question, best != null && best.hasOriginalBasis());
        }).toList();
    }

    /** 兼容不需要岗位优先级的调用方。 */
    public List<RequirementVerdict> assessRequirements(
            List<MustHaveCoverage> coverage, List<EvidenceAssessment> assessments) {
        return assessRequirements(coverage, assessments, null);
    }

    private static String priorityOf(String requirementId, TargetProfile targetProfile) {
        if (targetProfile == null || targetProfile.requirements() == null) return null;
        return targetProfile.requirements().stream()
                .filter(r -> r != null && java.util.Objects.equals(requirementId, r.id()))
                .map(TargetProfile.Requirement::priority)
                .findFirst().orElse(null);
    }

    private static String verdictReason(MustHaveCoverage.Status proposed,
                                        MustHaveCoverage.Status decided,
                                        EvidenceLevel level) {
        if (decided != proposed) {
            return "候选状态 " + proposed + " 超过证据等级 " + level + " 的上限，已裁决为 " + decided;
        }
        return "状态与已回锚证据等级 " + level + " 一致";
    }

    private static SlotSignals signalsOf(ResumeProjectFact project, String source) {
        boolean activity = anyUsable(project.responsibilities(), source) || usable(project.context(), source);
        boolean method = anyUsable(project.technologies(), source)
                || usable(project.aiPipeline(), source)
                || anyUsable(project.decisions(), source);
        boolean result = usable(project.results(), source)
                || usable(project.scale(), source)
                || usable(project.deployment(), source);
        boolean tradeOff = usable(project.problem(), source) || anyUsable(project.decisions(), source);
        return new SlotSignals(activity, method, result, tradeOff);
    }

    private static EvidenceLevel levelOf(SlotSignals signals) {
        if (signals.result() && signals.tradeOff()) return EvidenceLevel.L4_TRADE_OFF;
        if (signals.result()) return EvidenceLevel.L3_RESULT;
        if (signals.method()) return EvidenceLevel.L2_METHOD;
        if (signals.activity()) return EvidenceLevel.L1_ACTIVITY;
        return EvidenceLevel.L0_KEYWORD;
    }

    private static List<String> missingFacts(SlotSignals signals) {
        List<String> missing = new ArrayList<>();
        if (!signals.method()) missing.add("具体做法、技术路径或关键决策");
        if (!signals.result()) missing.add("可核验的规模、约束、结果或指标（只能补充真实数据）");
        if (!signals.tradeOff()) missing.add("复杂问题、技术权衡、失败复盘或后续演进");
        return missing;
    }

    private static List<String> collectEvidence(ResumeProjectFact project, String source) {
        List<String> evidence = new ArrayList<>();
        addQuote(evidence, project.context(), source);
        addQuote(evidence, project.problem(), source);
        addQuotes(evidence, project.responsibilities(), source);
        addQuotes(evidence, project.technologies(), source);
        addQuote(evidence, project.aiPipeline(), source);
        addQuotes(evidence, project.decisions(), source);
        addQuote(evidence, project.results(), source);
        addQuote(evidence, project.scale(), source);
        addQuote(evidence, project.deployment(), source);
        return evidence;
    }

    private static String projectClaim(ResumeProjectFact project) {
        if (usableValue(project.context())) return project.context().value();
        if (project.projectId() != null && !project.projectId().isBlank()) return project.projectId();
        return project.responsibilities().stream()
                .filter(EvidenceAssessmentService::usableValue)
                .map(ResumeProjectFact.Fact::value)
                .findFirst()
                .orElse("这段经历");
    }

    private static List<String> questions(String claim) {
        String subject = claim == null || claim.isBlank() ? "这段经历" : "“" + claim + "”";
        return List.of(
                subject + "中你具体负责哪一部分？",
                "为什么选择这个方案，遇到的约束和替代方案是什么？",
                "如何证明结果有效？请给出当时真实的规模或指标"
        );
    }

    private static List<String> advice(EvidenceLevel level, List<String> missing) {
        List<String> advice = new ArrayList<>();
        advice.add("准备一段只包含真实事实的 STAR 叙述，先说明你的个人归因");
        if (!missing.isEmpty()) advice.add("优先补齐：" + missing.get(0) + "；没有事实时明确说未知，不要估算");
        if (level == EvidenceLevel.L3_RESULT || level == EvidenceLevel.L4_TRADE_OFF) {
            advice.add("准备解释指标口径、对照基线和验证方式");
        }
        return advice;
    }

    private static boolean matches(MustHaveCoverage coverage, EvidenceAssessment assessment) {
        if (assessment == null) return false;
        // 仅凭同一 sectionId 不构成匹配——同章节不等于同一事实，
        // 有效关联必须有已回锚的原文证据与 coverage 证据文本重合。
        String evidence = coverage.evidence();
        if (evidence == null || evidence.isBlank()) return false;
        return assessment.evidenceFound().stream()
                .anyMatch(found -> containsNormalized(found, evidence) || containsNormalized(evidence, found));
    }

    private static List<String> matchingEvidence(MustHaveCoverage coverage, EvidenceAssessment assessment) {
        String proposedEvidence = coverage.evidence();
        if (proposedEvidence == null || proposedEvidence.isBlank() || assessment == null) return List.of();
        return assessment.evidenceFound().stream()
                .filter(found -> containsNormalized(found, proposedEvidence)
                        || containsNormalized(proposedEvidence, found))
                .toList();
    }

    /**
     * 确定性状态钳制：证据等级对最终匹配状态设硬上限。
     * 无匹配或 L0 关键词级 → MISSING；L1 行为级不能保留 MET（降为 PARTIAL）；
     * L2 及以上保留 coverage 原状态。LLM 的乐观判断不得突破此规则。
     */
    private static MustHaveCoverage.Status clamp(MustHaveCoverage.Status status, EvidenceLevel level) {
        if (level == null || level == EvidenceLevel.L0_KEYWORD) return MustHaveCoverage.Status.MISSING;
        if (level == EvidenceLevel.L1_ACTIVITY && status == MustHaveCoverage.Status.MET) {
            return MustHaveCoverage.Status.PARTIAL;
        }
        return status;
    }

    private static boolean usable(ResumeProjectFact.Fact fact, String source) {
        return fact != null && fact.isExplicit() && containsNormalized(source, fact.sourceQuote());
    }

    private static boolean anyUsable(List<ResumeProjectFact.Fact> facts, String source) {
        return facts != null && facts.stream().anyMatch(fact -> usable(fact, source));
    }

    private static boolean usableValue(ResumeProjectFact.Fact fact) {
        return fact != null && fact.value() != null && !fact.value().isBlank();
    }

    private static void addQuote(List<String> evidence, ResumeProjectFact.Fact fact, String source) {
        if (usable(fact, source)) evidence.add(clean(fact.sourceQuote()));
    }

    private static void addQuotes(List<String> evidence, List<ResumeProjectFact.Fact> facts, String source) {
        if (facts == null) return;
        facts.forEach(fact -> addQuote(evidence, fact, source));
    }

    private static String firstQuote(List<String> evidence) {
        return evidence.isEmpty() ? "" : evidence.get(0);
    }

    private static boolean containsNormalized(String source, String value) {
        if (source == null || source.isBlank() || value == null || value.isBlank()) return false;
        return source.replaceAll("\\s+", "").contains(value.replaceAll("\\s+", ""));
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    record SlotSignals(boolean activity, boolean method, boolean result, boolean tradeOff) {
    }
}
