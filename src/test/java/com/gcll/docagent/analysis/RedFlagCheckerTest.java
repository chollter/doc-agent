package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 红旗筛查测试——全部为确定性代码检查，用实体 fixture 驱动。
 * 覆盖七项检查与人群阈值差异；评测门禁（P7）将复用这些行为断言。
 */
class RedFlagCheckerTest {

    private final RedFlagChecker checker = new RedFlagChecker();

    private static ResumeEntity period(String value, String kind) {
        return new ResumeEntity(ResumeEntity.EntityType.TIME_PERIOD, value, "timeline",
                kind != null ? Map.of("kind", kind) : Map.of());
    }

    private static ResumeEntities entities(List<ResumeEntity> list) {
        return new ResumeEntities(list);
    }

    private List<RedFlag> check(List<ResumeEntity> list) {
        return checker.check(entities(list), "13800138000 a@b.com", Persona.GENERAL);
    }

    @Test
    void shouldFlagLargeTimelineGapAsHigh() {
        List<RedFlag> flags = check(List.of(
                period("2020.01-2021.02", "work"),
                period("2022.01-2023.06", "work")));

        assertThat(flags).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(RedFlag.TIMELINE_GAP);
            assertThat(f.severity()).isEqualTo(RedFlag.Severity.HIGH);
        });
    }

    @Test
    void shouldFlagSmallGapAsMediumOnly() {
        List<RedFlag> flags = check(List.of(
                period("2020.01-2020.10", "work"),
                period("2021.01-2022.06", "work")));

        assertThat(flags).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(RedFlag.TIMELINE_GAP);
            assertThat(f.severity()).isEqualTo(RedFlag.Severity.MEDIUM);
        });
    }

    @Test
    void shouldNotTreatEducationPeriodAsCareerGap() {
        // 本科后直接工作，教育与工作衔接——不应产生职业空窗红旗
        List<RedFlag> flags = check(List.of(
                period("2016.09-2020.06", "education"),
                period("2020.07-2023.06", "work")));

        assertThat(flags).noneMatch(f -> f.type().equals(RedFlag.TIMELINE_GAP));
    }

    @Test
    void shouldFlagFrequentJobHopping() {
        List<RedFlag> flags = check(List.of(
                period("2020.01-2020.09", "work"),
                period("2020.10-2021.06", "work"),
                period("2021.07-2022.02", "work"),
                period("2022.03-2022.10", "work")));

        assertThat(flags).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(RedFlag.JOB_HOPPING);
            assertThat(f.severity()).isEqualTo(RedFlag.Severity.HIGH);
        });
    }

    @Test
    void newGradPersonaShouldTolerateShortInternships() {
        // 同样 4 段短经历，应届口径下实习短任期不触发 HIGH
        List<ResumeEntity> periods = List.of(
                period("2022.06-2022.11", "work"),
                period("2022.12-2023.05", "work"),
                period("2023.06-2023.11", "work"),
                period("2023.12-2024.05", "work"));

        List<RedFlag> general = checker.check(entities(periods), "13800138000", Persona.GENERAL);
        List<RedFlag> newGrad = checker.check(entities(periods), "13800138000", Persona.NEW_GRAD);

        assertThat(general).anyMatch(f -> f.type().equals(RedFlag.JOB_HOPPING)
                && f.severity() == RedFlag.Severity.HIGH);
        assertThat(newGrad).noneMatch(f -> f.type().equals(RedFlag.JOB_HOPPING)
                && f.severity() == RedFlag.Severity.HIGH);
    }

    @Test
    void shouldFlagLongOverlap() {
        List<RedFlag> flags = check(List.of(
                period("2020.01-2021.06", "work"),
                period("2020.10-2022.06", "work")));

        assertThat(flags).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(RedFlag.OVERLAP);
            assertThat(f.severity()).isEqualTo(RedFlag.Severity.MEDIUM);
        });
    }

    @Test
    void shouldFlagSeniorTitleWithShortExperience() {
        List<RedFlag> flags = checker.check(entities(List.of(
                period("2022.06-2023.06", "work"),
                ResumeEntity.of(ResumeEntity.EntityType.ROLE, "高级工程师", "XX公司"))),
                "13800138000", Persona.GENERAL);

        assertThat(flags).anyMatch(f -> f.type().equals(RedFlag.TENURE_TITLE_MISMATCH));
    }

    @Test
    void shouldFlagMissingContact() {
        List<RedFlag> flags = checker.check(
                entities(List.of(period("2020.01-2022.06", "work"))),
                "张三 男 本科", Persona.GENERAL);

        assertThat(flags).anyMatch(f -> f.type().equals(RedFlag.CONTACT_MISSING)
                && f.severity() == RedFlag.Severity.LOW);
    }

    @Test
    void shouldFlagLowQuantificationAsHint() {
        List<ResumeEntity> list = new ArrayList<>(List.of(
                period("2020.01-2022.06", "work"),
                ResumeEntity.of(ResumeEntity.EntityType.CLAIM, "负责订单系统开发", "claim"),
                ResumeEntity.of(ResumeEntity.EntityType.CLAIM, "参与性能优化", "claim")));
        List<RedFlag> flags = checker.check(entities(list), "13800138000", Persona.GENERAL);

        assertThat(flags).anyMatch(f -> f.type().equals(RedFlag.LOW_QUANTIFICATION)
                && f.severity() == RedFlag.Severity.LOW);
    }

    @Test
    void cleanResumeShouldProduceNoHighFlags() {
        List<RedFlag> flags = check(List.of(
                period("2019.07-2021.06", "work"),
                period("2021.07-至今", "work")));

        assertThat(flags).noneMatch(f -> f.severity() == RedFlag.Severity.HIGH);
        LocalDate.now(); // 时间锚：本用例依赖"至今"开放区间解析
    }

    @Test
    void textFallbackShouldDetectGapWithoutEntities() {
        // 抽取降级场景：无实体，仅原文文本粗查——硬伤检测不静默消失
        String text = """
                ## 工作经历
                ### 甲公司 2020.01-2021.02
                ### 乙公司 2022.01-2023.06
                电话 13800138000
                """;
        List<RedFlag> flags = checker.checkFromText(text);

        assertThat(flags).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(RedFlag.TIMELINE_GAP);
            assertThat(f.severity()).isEqualTo(RedFlag.Severity.MEDIUM);
            assertThat(f.message()).contains("粗查");
        });
    }
}
