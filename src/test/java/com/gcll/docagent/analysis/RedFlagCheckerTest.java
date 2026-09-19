package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
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

    /** 词表资源是单点事实源：可解析且两组非空，防止静默空表禁用教育分流/职级检查。 */
    @Test
    void screeningLexiconResourceLoadsNonEmpty() throws Exception {
        RedFlagChecker.ScreeningLexicon lexicon = RedFlagChecker.ScreeningLexicon.load();

        assertThat(lexicon.seniorTitleMarkers()).isNotEmpty();
        assertThat(lexicon.educationMarkers()).contains("大学", "本科");
        assertThat(new ClassPathResource("lexicons/screening-lexicon.json")
                .getContentAsString(StandardCharsets.UTF_8))
                .contains("seniorTitleMarkers")
                .contains("educationMarkers");
        assertThat(new ObjectMapper().readTree(new ClassPathResource("lexicons/screening-lexicon.json")
                .getContentAsString(StandardCharsets.UTF_8)).get("seniorTitleMarkers").isArray()).isTrue();
    }

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
        // 含当月修正后真实空窗 = 2020.11~2021.01 共 3 个月 → MEDIUM
        List<RedFlag> flags = check(List.of(
                period("2020.01-2020.10", "work"),
                period("2021.02-2022.06", "work")));

        assertThat(flags).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(RedFlag.TIMELINE_GAP);
            assertThat(f.severity()).isEqualTo(RedFlag.Severity.MEDIUM);
            // 文案契约：区间为空窗月闭区间（结束次月→下段开始前月）并注明入职月——
            // 旧格式把下段开始日写进区间，读起来像"空窗持续到入职月"（线上被用户判错）
            assertThat(f.message()).contains("段间")
                    .contains("3 个月空窗（段间：2020-11 至 2021-01")
                    .contains("2021-02 已入职下一段");
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
            assertThat(f.message()).contains("段间").contains("粗查");
            assertThat(f.message()).contains("已入职下一段");
        });
    }

    @Test
    void textFallbackShouldIgnoreBirthDateAndEducationLines() {
        // 线上真实缺陷回归：单点日期（出生）与教育行不得入时间线——
        // 旧实现扫全文所有日期逐对算间隔，产出 231/52 个月垃圾红旗；
        // 本例工作区间实际无缝衔接，粗查必须零空窗红旗（联系方式在，无其他红旗）
        String text = """
                出生 1995.06，电话 13800138000
                ## 教育经历
                华南理工大学 本科 2014.09-2019.01 软件工程（五年制）
                ## 工作经历
                ### 甲公司 · 后端工程师 2019.01-2020.03
                - 负责订单系统
                """;

        List<RedFlag> flags = checker.checkFromText(text);

        assertThat(flags).noneMatch(f -> f.type().equals(RedFlag.TIMELINE_GAP));
    }

    @Test
    void textFallbackShouldDetectOnlyRealWorkGap() {
        // 混合噪声（出生日期 + 教育区间 + 两段工作）中只报工作区间间的真实空窗：
        // 2020.03→2022.06 = 27 个月，且只报这一条，不得报 231/52/14 个月
        String text = """
                出生 1995.06，电话 13800138000
                华东师范大学 硕士 2014.09-2019.01 计算机技术（学制四年半）
                2019.01-2020.03 甲公司 Java 开发工程师（应届入职）
                2022.06-至今 乙公司 高级开发工程师（主导重构）
                """;

        List<RedFlag> flags = checker.checkFromText(text);

        List<RedFlag> gaps = flags.stream()
                .filter(f -> f.type().equals(RedFlag.TIMELINE_GAP)).toList();
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).message()).contains("26 个月");
    }

    @Test
    void realTwoMonthTransitionShouldNotBeFlagged() {
        // 含当月修正锁定：结束月次月算起，真实 2 个月交接期不报旗（旧口径会误报 3 个月）
        List<RedFlag> flags = check(List.of(
                period("2020.01-2020.06", "work"),
                period("2020.09-2021.05", "work")));

        assertThat(flags).noneMatch(f -> f.type().equals(RedFlag.TIMELINE_GAP));
    }

    @Test
    void contiguousJobsShouldHaveZeroGap() {
        List<RedFlag> flags = check(List.of(
                period("2020.01-2020.03", "work"),
                period("2020.04-2021.05", "work")));

        assertThat(flags).noneMatch(f -> f.type().equals(RedFlag.TIMELINE_GAP));
    }

    @Test
    void closedLastEmploymentShouldFlagTrailingGap() {
        List<RedFlag> flags = check(List.of(
                period("2023.01-2025.06", "work"),
                period("2025.06-2026.01", "work")));

        assertThat(flags).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(RedFlag.TRAILING_GAP);
            assertThat(f.severity()).isEqualTo(RedFlag.Severity.HIGH);
            assertThat(f.message()).contains("尾部");
        });
    }

    @Test
    void openEmploymentShouldHaveNoTrailingFlag() {
        List<RedFlag> flags = check(List.of(
                period("2023.01-2025.06", "work"),
                period("2025.07-至今", "work")));

        assertThat(flags).noneMatch(f -> f.type().equals(RedFlag.TRAILING_GAP)
                || f.type().equals(RedFlag.EMPLOYMENT_GAP_COVERED));
    }

    @Test
    void projectCoveredLeavingShouldBeHintNotGap() {
        // 用户真实场景：雇佣结束 + 独立项目至今——不是空窗，是面试准备信号
        List<RedFlag> flags = check(List.of(
                period("2025.02-2026.02", "work"),
                period("2026.04-至今", "project")));

        assertThat(flags).anySatisfy(f -> {
            assertThat(f.type()).isEqualTo(RedFlag.EMPLOYMENT_GAP_COVERED);
            assertThat(f.severity()).isEqualTo(RedFlag.Severity.LOW);
            assertThat(f.message()).contains("尾部").contains("面试必问");
        });
        assertThat(flags).noneMatch(f -> f.type().equals(RedFlag.TRAILING_GAP));
    }

    @Test
    void nestedProjectShouldNotCreateGapsOrOverlap() {
        // 线上真实缺陷回归：在职期内的项目段（kind=project）混入时间线后，
        // “项目结束 2024.04→下份工作 2025.02”被算成假空窗、“项目 2023.05 与雇佣 2022.08
        // 嵌套”被算成假重叠 17 个月。项目段必须不参与段间空窗/重叠计算；
        // 雇佣段之间的真实间隔（2024.10→2025.02 含当月修正 = 3 个月）照常报 MEDIUM
        List<RedFlag> flags = check(List.of(
                period("2020.03-2022.06", "work"),
                period("2022.08-2024.10", "work"),
                period("2023.05-2024.04", "project"),
                period("2025.02-2026.02", "work"),
                period("2026.04-至今", "project")));

        List<RedFlag> gaps = flags.stream()
                .filter(f -> f.type().equals(RedFlag.TIMELINE_GAP)).toList();
        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).severity()).isEqualTo(RedFlag.Severity.MEDIUM);
        assertThat(gaps.get(0).message()).contains("2024-11");
        assertThat(flags).noneMatch(f -> f.type().equals(RedFlag.OVERLAP));
    }

    @Test
    void untaggedEducationContextShouldBeExcluded() {
        // LLM 漏标 kind 的兜底：上下文含教育词的时间段不参与职业空窗计算
        List<ResumeEntity> entities = List.of(
                new ResumeEntity(ResumeEntity.EntityType.TIME_PERIOD, "2015.09-2019.06",
                        "华南理工 软件工程 本科", java.util.Map.of()),
                period("2019.07-2020.10", null),
                period("2021.07-至今", null));

        List<RedFlag> flags = checker.check(new ResumeEntities(entities), "13800138000", Persona.GENERAL);

        // 教育→首职的 1 个月与首职→当前的间隔正常计算，毕业前不产生空窗旗
        assertThat(flags).noneMatch(f -> f.type().equals(RedFlag.TIMELINE_GAP)
                && f.message().contains("2019-06"));
    }
}
