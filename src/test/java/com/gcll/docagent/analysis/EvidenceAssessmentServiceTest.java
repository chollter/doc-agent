package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceAssessmentServiceTest {
    private final EvidenceAssessmentService service = new EvidenceAssessmentService();

    @Test
    void scoresProjectFromGroundedSlotsNotKeywords() {
        String source = "知识库问答：主导检索增强系统，采用降级策略，日均调用 12 万次。";
        ResumeProjectFact project = project(
                explicit("知识库问答", "知识库问答"),
                explicit("检索不稳定", "采用降级策略"),
                List.of(explicit("主导系统建设", "主导检索增强系统")),
                List.of(),
                missing(),
                List.of(explicit("故障时降级", "采用降级策略")),
                explicit("日均调用 12 万次", "日均调用 12 万次"),
                missing(),
                missing());

        EvidenceAssessment assessment = service.assess(new ResumeEntities(List.of(), List.of(project)), source).get(0);

        assertThat(assessment.evidenceLevel()).isEqualTo(EvidenceLevel.L4_TRADE_OFF);
        assertThat(assessment.hasOriginalBasis()).isTrue();
        assertThat(assessment.evidenceFound()).anyMatch(value -> value.contains("12 万次"));
        assertThat(assessment.missingFacts()).isEmpty();
    }

    @Test
    void responsibilitiesWithoutResultStayAtActivityEvenIfTextHasMetrics() {
        String source = "负责订单接口。通过 Redis 缓存降低接口延迟 30%，并在故障时降级。";
        ResumeProjectFact project = project(
                missing(),
                missing(),
                List.of(explicit("负责订单接口", "通过 Redis 缓存降低接口延迟 30%，并在故障时降级")),
                List.of(),
                missing(),
                List.of(),
                missing(),
                missing(),
                missing());

        EvidenceAssessment assessment = service.assessProject(project, source);

        assertThat(assessment.evidenceLevel()).isEqualTo(EvidenceLevel.L1_ACTIVITY);
        assertThat(assessment.missingFacts()).anyMatch(value -> value.contains("规模、约束、结果或指标"));
        assertThat(assessment.evidenceFound()).allMatch(source::contains);
    }

    @Test
    void inferredOrUngroundedSlotsDoNotRaiseTheLevel() {
        String source = "负责订单接口开发";
        ResumeProjectFact project = project(
                missing(),
                missing(),
                List.of(explicit("负责订单接口开发", "负责订单接口开发")),
                List.of(),
                missing(),
                List.of(),
                new ResumeProjectFact.Fact("降低延迟 30%", "inferred", "降低延迟 30%"),
                new ResumeProjectFact.Fact("百万 QPS", "explicit", "百万 QPS"),
                missing());

        EvidenceAssessment assessment = service.assessProject(project, source);

        assertThat(assessment.evidenceLevel()).isEqualTo(EvidenceLevel.L1_ACTIVITY);
        assertThat(assessment.evidenceFound()).containsExactly("负责订单接口开发");
    }

    @Test
    void methodSlotWithoutResultIsMethodLevel() {
        String source = "使用 Redis 缓存订单查询";
        ResumeProjectFact project = project(
                missing(),
                missing(),
                List.of(explicit("负责订单查询", "使用 Redis 缓存订单查询")),
                List.of(explicit("Redis", "Redis")),
                missing(),
                List.of(),
                missing(),
                missing(),
                missing());

        assertThat(service.assessProject(project, source).evidenceLevel())
                .isEqualTo(EvidenceLevel.L2_METHOD);
    }

    @Test
    void prefersAuditableSourceQuoteAsEvidence() {
        ResumeEntity claim = new ResumeEntity(ResumeEntity.EntityType.CLAIM,
                "订单服务重构", "重构服务", Map.of(
                "sectionId", "sec-4", "sourceQuote", "通过幂等和补偿处理重复订单"));

        EvidenceAssessment assessment = service.assessClaim(claim,
                "项目经历：通过幂等和补偿处理重复订单");

        assertThat(assessment.sectionId()).isEqualTo("sec-4");
        assertThat(assessment.sourceQuote()).isEqualTo("通过幂等和补偿处理重复订单");
        assertThat(assessment.evidenceFound()).contains("通过幂等和补偿处理重复订单");
        assertThat(assessment.hasOriginalBasis()).isTrue();
        assertThat(assessment.evidenceLevel()).isEqualTo(EvidenceLevel.L1_ACTIVITY);
    }

    @Test
    void linksRequirementToEvidenceWithoutUpgradingItsLevel() {
        ResumeEntity claim = new ResumeEntity(ResumeEntity.EntityType.CLAIM,
                "负责订单接口", "负责订单接口开发", Map.of(
                "sectionId", "sec-2", "sourceQuote", "负责订单接口开发"));
        EvidenceAssessment evidence = service.assessClaim(claim, "工作经历：负责订单接口开发");
        MustHaveCoverage coverage = new MustHaveCoverage("distributed", "分布式系统设计",
                MustHaveCoverage.Status.PARTIAL, "负责订单接口开发", "sec-2");

        RequirementVerdict linked = service
                .assessRequirements(List.of(coverage), List.of(evidence)).get(0);

        assertThat(linked.evidenceLevel()).isEqualTo(EvidenceLevel.L1_ACTIVITY);
        assertThat(linked.claim()).isEqualTo("负责订单接口");
        assertThat(linked.missingFacts()).anyMatch(value -> value.contains("技术路径"));
        assertThat(linked.hasOriginalBasis()).isTrue();
    }

    @Test
    void neverInventsMissingNumbersOrResults() {
        ResumeEntities entities = new ResumeEntities(List.of(
                ResumeEntity.of(ResumeEntity.EntityType.CLAIM, "负责接口开发", "负责接口开发")
        ));

        EvidenceAssessment assessment = service.assess(entities, "负责接口开发").get(0);

        assertThat(assessment.evidenceLevel()).isEqualTo(EvidenceLevel.L1_ACTIVITY);
        assertThat(assessment.missingFacts()).anyMatch(value -> value.contains("规模、约束、结果或指标"));
        assertThat(assessment.preparationAdvice()).anyMatch(value -> value.contains("没有事实时明确说未知"));
        assertThat(assessment.evidenceFound()).noneMatch(value -> value.matches(".*\\d+.*"));
    }

    @Test
    void sameSectionWithoutEvidenceOverlapDoesNotMatch() {
        // 同一 sectionId 但证据文本无任何重合：不得建立关联（旧实现仅凭 sectionId 相等即匹配）。
        ResumeEntity claim = new ResumeEntity(ResumeEntity.EntityType.CLAIM,
                "负责订单接口", "负责订单接口开发", Map.of(
                "sectionId", "sec-2", "sourceQuote", "负责订单接口开发"));
        EvidenceAssessment evidence = service.assessClaim(claim, "工作经历：负责订单接口开发");
        MustHaveCoverage coverage = new MustHaveCoverage("distributed", "分布式系统设计",
                MustHaveCoverage.Status.MET, "主导跨团队微服务治理", "sec-2");

        RequirementVerdict linked = service
                .assessRequirements(List.of(coverage), List.of(evidence)).get(0);

        assertThat(linked.status()).isEqualTo(MustHaveCoverage.Status.MISSING);
        assertThat(linked.evidenceLevel()).isEqualTo(EvidenceLevel.L0_KEYWORD);
        assertThat(linked.hasOriginalBasis()).isFalse();
        assertThat(linked.claim()).isNull();
    }

    @Test
    void l0EvidenceDowngradesMetToMissing() {
        // 没有任何可匹配证据时，即使 coverage 宣称 MET 也必须钳制为 MISSING。
        MustHaveCoverage coverage = new MustHaveCoverage("rag", "有 RAG 项目经验",
                MustHaveCoverage.Status.MET, "主导检索增强系统落地", null);

        RequirementVerdict linked = service
                .assessRequirements(List.of(coverage), List.of()).get(0);

        assertThat(linked.status()).isEqualTo(MustHaveCoverage.Status.MISSING);
        assertThat(linked.evidenceLevel()).isEqualTo(EvidenceLevel.L0_KEYWORD);
    }

    @Test
    void l1EvidenceDowngradesMetToPartial() {
        // L1 行为级证据不足以支撑 MET，只能保留 PARTIAL。
        ResumeEntity claim = new ResumeEntity(ResumeEntity.EntityType.CLAIM,
                "负责订单接口", "负责订单接口开发", Map.of(
                "sectionId", "sec-2", "sourceQuote", "负责订单接口开发"));
        EvidenceAssessment evidence = service.assessClaim(claim, "工作经历：负责订单接口开发");
        MustHaveCoverage coverage = new MustHaveCoverage("backend", "订单接口开发经验",
                MustHaveCoverage.Status.MET, "负责订单接口开发", "sec-2");

        RequirementVerdict linked = service
                .assessRequirements(List.of(coverage), List.of(evidence)).get(0);

        assertThat(evidence.evidenceLevel()).isEqualTo(EvidenceLevel.L1_ACTIVITY);
        assertThat(linked.status()).isEqualTo(MustHaveCoverage.Status.PARTIAL);
        assertThat(linked.hasOriginalBasis()).isTrue();
    }

    @Test
    void ungroundedExplicitSlotStaysMissing() {
        // sourceQuote 无法在原文回锚的 explicit 槽位不得提升证据等级。
        String source = "负责订单接口开发";
        ResumeProjectFact project = new ResumeProjectFact("project-1", "sec-2",
                new ResumeProjectFact.Fact("知识库问答", "explicit", "原文中不存在的引用"),
                missing(), List.of(), List.of(), missing(), List.of(), missing(), missing(), missing());

        EvidenceAssessment assessment = service.assessProject(project, source);

        assertThat(assessment.evidenceLevel()).isEqualTo(EvidenceLevel.L0_KEYWORD);
        assertThat(assessment.hasOriginalBasis()).isFalse();
        assertThat(assessment.evidenceFound()).isEmpty();
    }

    @Test
    void groundedEvidenceOverlapLinksRequirement() {
        // coverage 证据与已回锚的原文证据文本重合时，关联成立。
        ResumeEntity claim = new ResumeEntity(ResumeEntity.EntityType.CLAIM,
                "订单服务重构", "通过幂等和补偿处理重复订单", Map.of(
                "sectionId", "sec-4", "sourceQuote", "通过幂等和补偿处理重复订单"));
        EvidenceAssessment evidence = service.assessClaim(claim, "项目经历：通过幂等和补偿处理重复订单");
        MustHaveCoverage coverage = new MustHaveCoverage("idempotency", "幂等与补偿机制经验",
                MustHaveCoverage.Status.PARTIAL, "通过幂等和补偿处理重复订单", "sec-4");

        RequirementVerdict linked = service
                .assessRequirements(List.of(coverage), List.of(evidence)).get(0);

        assertThat(linked.status()).isEqualTo(MustHaveCoverage.Status.PARTIAL);
        assertThat(linked.hasOriginalBasis()).isTrue();
        assertThat(linked.claim()).isEqualTo("订单服务重构");
    }

    @Test
    void l2OrHigherPreservesMetStatus() {
        // L2 及以上证据在 coverage 为 MET 时保持 MET——钳制不是一律保守化。
        String source = "使用 Redis 缓存订单查询";
        ResumeProjectFact project = new ResumeProjectFact("project-1", "sec-2",
                missing(), missing(),
                List.of(explicit("负责订单查询", "使用 Redis 缓存订单查询")),
                List.of(explicit("Redis", "Redis")),
                missing(), List.of(), missing(), missing(), missing());
        EvidenceAssessment assessment = service.assessProject(project, source);
        MustHaveCoverage coverage = new MustHaveCoverage("caching", "缓存实践经验",
                MustHaveCoverage.Status.MET, "使用 Redis 缓存订单查询", "sec-2");

        RequirementVerdict linked = service
                .assessRequirements(List.of(coverage), List.of(assessment)).get(0);

        assertThat(assessment.evidenceLevel()).isEqualTo(EvidenceLevel.L2_METHOD);
        assertThat(linked.status()).isEqualTo(MustHaveCoverage.Status.MET);
        assertThat(linked.hasOriginalBasis()).isTrue();
    }

    private static ResumeProjectFact project(
            ResumeProjectFact.Fact context,
            ResumeProjectFact.Fact problem,
            List<ResumeProjectFact.Fact> responsibilities,
            List<ResumeProjectFact.Fact> technologies,
            ResumeProjectFact.Fact aiPipeline,
            List<ResumeProjectFact.Fact> decisions,
            ResumeProjectFact.Fact results,
            ResumeProjectFact.Fact scale,
            ResumeProjectFact.Fact deployment) {
        return new ResumeProjectFact("project-1", "sec-2", context, problem, responsibilities,
                technologies, aiPipeline, decisions, results, scale, deployment);
    }

    private static ResumeProjectFact.Fact explicit(String value, String quote) {
        return new ResumeProjectFact.Fact(value, "explicit", quote);
    }

    private static ResumeProjectFact.Fact missing() {
        return ResumeProjectFact.Fact.missing();
    }
}
