package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体规范化测试——时间段格式统一与去重。
 * 规范化是"确定性检查依赖 LLM 抽取"这一链路上的稳定层。
 */
class EntityNormalizerTest {

    private final EntityNormalizer normalizer = new EntityNormalizer();

    @Test
    void shouldNormalizeTimePeriodValues() {
        ResumeEntities entities = new ResumeEntities(List.of(
                ResumeEntity.of(ResumeEntity.EntityType.TIME_PERIOD, "2020年3月-2021年5月", "XX公司"),
                ResumeEntity.of(ResumeEntity.EntityType.SKILL, "Java", "技能")));

        ResumeEntities normalized = normalizer.normalize(entities);

        assertThat(normalized.getByType(ResumeEntity.EntityType.TIME_PERIOD))
                .singleElement()
                .satisfies(p -> assertThat(p.value()).isEqualTo("2020.03-2021.05"));
        // 非时间段实体原样保留
        assertThat(normalized.getSkillNames()).containsExactly("Java");
    }

    @Test
    void shouldDeduplicateSameTypeAndValue() {
        ResumeEntities entities = new ResumeEntities(List.of(
                ResumeEntity.of(ResumeEntity.EntityType.SKILL, "Java", "a"),
                ResumeEntity.of(ResumeEntity.EntityType.SKILL, "java", "b"),
                ResumeEntity.of(ResumeEntity.EntityType.SKILL, "Python", "c")));

        assertThat(normalizer.normalize(entities).getSkillNames())
                .containsExactlyInAnyOrder("Java", "Python");
    }

    @Test
    void shouldKeepDifferentTypesWithSameValue() {
        ResumeEntities entities = new ResumeEntities(List.of(
                ResumeEntity.of(ResumeEntity.EntityType.ORGANIZATION, "阿里", "a"),
                ResumeEntity.of(ResumeEntity.EntityType.ROLE, "阿里", "b")));

        assertThat(normalizer.normalize(entities).getAll()).hasSize(2);
    }

    @Test
    void shouldPreserveAttributesThroughNormalization() {
        ResumeEntities entities = new ResumeEntities(List.of(
                new ResumeEntity(ResumeEntity.EntityType.TIME_PERIOD, "2020年3月至今", "work",
                        Map.of("kind", "work"))));

        var periods = normalizer.normalize(entities).getByType(ResumeEntity.EntityType.TIME_PERIOD);
        assertThat(periods).singleElement().satisfies(p -> {
            assertThat(p.value()).isEqualTo("2020.03至今");
            assertThat(p.kind()).isEqualTo("work");
        });
    }

    @Test
    void shouldExposeSourceAnchorAttributes() {
        ResumeEntity claim = new ResumeEntity(ResumeEntity.EntityType.CLAIM,
                "主导订单重构", "项目经历", Map.of(
                "sectionId", "sec-3", "sourceQuote", "主导订单重构"));

        assertThat(claim.sectionId()).isEqualTo("sec-3");
        assertThat(claim.sourceQuote()).isEqualTo("主导订单重构");
    }
}
