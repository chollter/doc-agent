package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 红旗筛查器——漏斗第一层，全部为确定性代码检查，不依赖 LLM。
 * <p>P11 的模式检查只有空窗/量化/领导力三项且输出无差别字符串。
 * P12 扩充为七项并按严重度分级（HIGH 一票否决 / MEDIUM 会被追问 / LOW 提示），
 * 人群阈值由 Persona 调节（应届的实习短任期不视为跳槽）。
 */
@Component
public class RedFlagChecker {

    private static final Pattern PHONE = Pattern.compile("1[3-9]\\d{9}|\\d{3,4}-\\d{7,8}");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");

    /**
     * 审查词表（职级词、教育行词）——是随语言/市场增长的数据而非代码，
     * 从 classpath:lexicons/screening-lexicon.json 加载；缺失或空组直接失败，
     * 不静默降级（空教育词表会让时间线粗查把教育年限算成空窗）。
     */
    private static final ScreeningLexicon LEXICON = ScreeningLexicon.load();
    private static final List<String> SENIOR_MARKERS = LEXICON.seniorTitleMarkers();

    /** 教育行关键词——文本粗查无法语义分流，按行级词汇排除教育经历。 */
    private static final List<String> EDUCATION_LINE_MARKERS = LEXICON.educationMarkers();

    /** 空窗阈值（月）：超过为 MEDIUM，超过此值为 HIGH。 */
    private static final int GAP_MEDIUM_MONTHS = 2;
    private static final int GAP_HIGH_MONTHS = 6;
    /** 短任期阈值（月）：非应届视角下少于它视为短工。 */
    private static final int SHORT_TENURE_MONTHS = 8;
    private static final int AVG_TENURE_FLAG_MONTHS = 12;
    /** 时间重叠容忍（月）：并行项目/交接期常见，超过才标记。 */
    private static final int OVERLAP_TOLERANCE_MONTHS = 3;
    /** 高级头衔但总经验低于此年限（月）视为不匹配。 */
    private static final int SENIOR_MIN_TOTAL_MONTHS = 24;
    private static final int NEW_GRAD_SHORT_TENURE_MONTHS = 6;

    /**
     * 空窗类红旗的可操作建议：代码无法判断空窗期做了什么，给出两条真实可执行的路径——
     * 有可查证经历就补一行（时间线连续后红旗自然消失），没有就备面试口径，不发明事实。
     */
    private static final String GAP_ACTIONABLE_ADVICE =
            "——空窗期有可查证的工作/全职活动（社保、个税可查）就在经历中补一行「时间｜行业｜岗位」，"
                    + "时间线连续后此红旗消除；没有可查证经历则备一句面试口径，不写无法证明的内容";
    private static final String COVERED_TRAILING_ADVICE =
            "；若期间另有可查证的工作经历（社保、个税可查），补一行「时间｜行业｜岗位」更稳";

    /**
     * 执行全部红旗检查。
     *
     * @param entities    规范化后的简历实体
     * @param resumeText  简历全文（联系方式等需要原文的检查使用）
     * @param persona     人群预设（调节阈值）
     */
    public List<RedFlag> check(ResumeEntities entities, String resumeText, Persona persona) {
        List<RedFlag> flags = new ArrayList<>();
        List<LocalDate[]> workPeriods = parseWorkPeriods(entities);

        flags.addAll(checkTimelineGaps(workPeriods));
        flags.addAll(checkJobHopping(workPeriods, persona));
        flags.addAll(checkOverlap(workPeriods));
        addIfPresent(flags, checkTrailingEmployment(entities));
        addIfPresent(flags, checkTenureTitleMismatch(entities, workPeriods));
        addIfPresent(flags, checkContact(resumeText));
        flags.addAll(checkMissingSections(entities, persona));
        addIfPresent(flags, checkQuantification(entities));
        return flags;
    }

    /**
     * 文本级兜底——实体抽取降级时直接对原文做日期扫描粗查。
     * <p>降级不级联原则：抽取失败只损失"实体级精度"（教育/工作分流、跳槽、头衔），
     * 硬伤检测（时间线空窗、联系方式）退化为文本粗查而不是静默消失。
     * <p>口径三重过滤（线上缺陷回归：旧实现扫全文所有单点日期逐对算间隔，
     * 把出生日期、教育年限算成 231/52 个月的垃圾红旗）：
     * 只认区间（出生等单点日期永不入时间线）、排除教育行、只在区间之间算空窗。
     * 粗查不分流，严重度封顶 MEDIUM 并在文案中注明不确定性。
     */
    public List<RedFlag> checkFromText(String resumeText) {
        List<RedFlag> flags = new ArrayList<>();
        if (resumeText == null || resumeText.isBlank()) {
            return flags;
        }
        List<LocalDate[]> ranges = scanWorkRanges(resumeText);
        // 端点取滚动最大：经历行乱序/重复时，不拿较早区间的端点误报后续空窗
        LocalDate prevEnd = null;
        for (LocalDate[] range : ranges) {
            if (prevEnd != null) {
                // 含当月修正：空窗从上一段结束月的次月算起（同实体路径口径）
                long gap = ChronoUnit.MONTHS.between(prevEnd.plusMonths(1), range[0]);
                if (gap > GAP_MEDIUM_MONTHS) {
                    RedFlag.Severity severity = gap > GAP_HIGH_MONTHS
                            ? RedFlag.Severity.MEDIUM : RedFlag.Severity.LOW;
                    YearMonth nextStart = YearMonth.from(range[0]);
                    flags.add(new RedFlag(RedFlag.TIMELINE_GAP, severity,
                            String.format("时间线疑似有 %d 个月空窗（段间：%s 至 %s，%s 已入职下一段，粗查）",
                                    gap, YearMonth.from(prevEnd.plusMonths(1)),
                                    nextStart.minusMonths(1), nextStart)
                                    + GAP_ACTIONABLE_ADVICE));
                }
            }
            if (prevEnd == null || range[1].isAfter(prevEnd)) {
                prevEnd = range[1];
            }
        }
        // 尾部粗查：最后一行工作区间若非开放（无"至今"），距离现在超过阈值也提示
        if (!ranges.isEmpty()) {
            LocalDate[] last = ranges.get(ranges.size() - 1);
            boolean lastLineOpen = ResumeDateParser.isOpenEnded(
                    lastLineContaining(resumeText, last));
            if (!lastLineOpen) {
                long months = ChronoUnit.MONTHS.between(last[1].plusMonths(1), LocalDate.now().withDayOfMonth(1));
                if (months > GAP_MEDIUM_MONTHS) {
                    boolean covered = ranges.stream().anyMatch(r -> r != last
                            && ResumeDateParser.isOpenEnded(lastLineContaining(resumeText, r)));
                    if (covered) {
                        flags.add(new RedFlag(RedFlag.EMPLOYMENT_GAP_COVERED, RedFlag.Severity.LOW,
                                String.format("最后一段雇佣 %s 结束，至今约 %d 个月（尾部：有项目覆盖），粗查显示有开放经历覆盖"
                                        + "——面试必问，备好口径", last[1], months)
                                        + COVERED_TRAILING_ADVICE));
                    } else {
                        flags.add(new RedFlag(RedFlag.TRAILING_GAP, RedFlag.Severity.MEDIUM,
                                String.format("最后一段经历 %s 结束至今约 %d 个月（尾部：无覆盖，粗查）——初筛必问",
                                        last[1], months)
                                        + GAP_ACTIONABLE_ADVICE));
                    }
                }
            }
        }
        addIfPresent(flags, checkContact(resumeText));
        return flags;
    }

    /** 找到包含该区间起点的原文行（用于开放区间判定）。 */
    private static String lastLineContaining(String text, LocalDate[] range) {
        String target = range[0].getYear() + "." + String.format("%02d", range[0].getMonthValue());
        for (String line : text.split("\\R")) {
            if (line.contains(target)) {
                return line;
            }
        }
        return "";
    }
    
    /**
     * 逐行扫描工作式日期区间：只收含起止的区间（"至今"开放区间由 parseRange 处理），
     * 排除教育行与单点区间（start==end，如孤立日期）。
     */
    private List<LocalDate[]> scanWorkRanges(String resumeText) {
        List<LocalDate[]> ranges = new ArrayList<>();
        for (String line : resumeText.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || isEducationLine(trimmed)) {
                continue;
            }
            LocalDate[] range = ResumeDateParser.parseRange(trimmed);
            if (range != null && range[0].isBefore(range[1])) {
                ranges.add(range);
            }
        }
        ranges.sort(Comparator.comparing(a -> a[0]));
        return ranges;
    }
    
    private static boolean isEducationLine(String line) {
        String lower = line.toLowerCase();
        return EDUCATION_LINE_MARKERS.stream().anyMatch(lower::contains);
    }

    private static void addIfPresent(List<RedFlag> flags, RedFlag flag) {
        if (flag != null) {
            flags.add(flag);
        }
    }

    /**
     * 解析参与时间线检查的雇佣段为 [start, end]，按开始时间排序。
     * <p>project 段排除（线上缺陷回归：在职期内的项目段混入时间线，其端点与下一段
     * 雇佣之间的正常间隔被算成空窗、与雇佣段的嵌套被算成重叠——项目归属雇佣期是常态）；
     * 教育段排除（kind=education 或漏标兜底）。尾部雇佣检查单独用全量实体。
     */
    private List<LocalDate[]> parseWorkPeriods(ResumeEntities entities) {
        return entities.getByType(ResumeEntity.EntityType.TIME_PERIOD).stream()
                .filter(p -> !"project".equals(p.kind()) && !isEducationEntity(p))
                .map(p -> ResumeDateParser.parseRange(p.value()))
                .filter(r -> r != null)
                .sorted(Comparator.comparing(a -> a[0]))
                .toList();
    }

    /** 教育段判定：kind=education，或 kind 缺失但上下文/值含教育词（LLM 漏标兜底）。 */
    private static boolean isEducationEntity(ResumeEntity p) {
        if ("education".equals(p.kind())) {
            return true;
        }
        if (p.kind() != null && !p.kind().isBlank()) {
            return false;
        }
        String haystack = ((p.context() == null ? "" : p.context()) + " " + p.value()).toLowerCase();
        return EDUCATION_LINE_MARKERS.stream().anyMatch(haystack::contains);
    }

    /**
     * 1. 时间线空窗：相邻经历间隔 > 2 月（真实口径）。
     * <p>含当月修正：简历惯例"2020.01-2020.03"的 3 月是干满的，空窗从结束月的次月
     * 起算（prevEnd+1）——否则把最后一个在职月算成空窗，真实的 2 个月交接期
     * 会被误报成 3 个月 MEDIUM（用户实测抓出的口径偏差）。
     */
    private List<RedFlag> checkTimelineGaps(List<LocalDate[]> periods) {
        List<RedFlag> flags = new ArrayList<>();
        for (int i = 1; i < periods.size(); i++) {
            LocalDate prevEndExclusive = periods.get(i - 1)[1].plusMonths(1);
            long gap = ChronoUnit.MONTHS.between(prevEndExclusive, periods.get(i)[0]);
            if (gap > GAP_MEDIUM_MONTHS) {
                RedFlag.Severity severity = gap > GAP_HIGH_MONTHS ? RedFlag.Severity.HIGH : RedFlag.Severity.MEDIUM;
                // 区间展示空窗月闭区间（次月→下段开始的前一月）并注明入职月——
                // 下段开始日不是空窗，写进区间会被读成"空窗持续到入职月"（线上被用户判错）
                YearMonth nextStart = YearMonth.from(periods.get(i)[0]);
                flags.add(new RedFlag(RedFlag.TIMELINE_GAP, severity,
                        String.format("时间线有 %d 个月空窗（段间：%s 至 %s，%s 已入职下一段）", gap,
                                YearMonth.from(prevEndExclusive), nextStart.minusMonths(1), nextStart)
                                + GAP_ACTIONABLE_ADVICE));
            }
        }
        return flags;
    }

    /**
     * 1b. 尾部雇佣检查：最后一段<b>雇佣</b>（kind=work/未标注）结束至今的时长。
     * <p>"至今"的项目经历不算在职——HR 视角下"从上份工作离职多久了"是初筛第一问。
     * 有项目覆盖时不算空窗（有活动有产出），输出面试准备信号而非否决红旗；
     * 无覆盖且超阈值才是真尾部空窗。注意：基准是"现在"，简历写就时间未知，
     * 间隔会随分析时间虚增——文案不装精确。
     */
    private RedFlag checkTrailingEmployment(ResumeEntities entities) {
        List<ResumeEntity> periods = entities.getByType(ResumeEntity.EntityType.TIME_PERIOD).stream()
                .filter(p -> !isEducationEntity(p))
                .toList();
        if (periods.isEmpty()) {
            return null;
        }
        // 最后一段雇佣：kind=project 的不算雇佣
        ResumeEntity lastEmploymentRef = null;
        LocalDate lastEmpEndRef = null;
        for (ResumeEntity p : periods) {
            if ("project".equals(p.kind())) {
                continue;
            }
            LocalDate[] r = ResumeDateParser.parseRange(p.value());
            if (r == null) {
                continue;
            }
            if (lastEmpEndRef == null || r[1].isAfter(lastEmpEndRef)) {
                lastEmpEndRef = r[1];
                lastEmploymentRef = p;
            }
        }
        final ResumeEntity lastEmployment = lastEmploymentRef;
        final LocalDate lastEmpEnd = lastEmpEndRef;
        if (lastEmployment == null || ResumeDateParser.isOpenEnded(lastEmployment.value())) {
            return null;
        }
        LocalDate now = LocalDate.now().withDayOfMonth(1);
        long months = ChronoUnit.MONTHS.between(lastEmpEnd.plusMonths(1), now);
        if (months <= GAP_MEDIUM_MONTHS) {
            return null;
        }
        // 覆盖判定：离职后 3 个月内开始、持续至今（或近期）的非教育经历
        boolean covered = periods.stream().anyMatch(p -> p != lastEmployment
                && ResumeDateParser.isOpenEnded(p.value())
                && coversLeaving(p, lastEmpEnd));
        if (covered) {
            return new RedFlag(RedFlag.EMPLOYMENT_GAP_COVERED, RedFlag.Severity.LOW,
                    String.format("最后一段雇佣 %s 结束，至今约 %d 个月（尾部：有项目覆盖），期间有项目/独立经历覆盖——不是空窗，"
                            + "但面试必问：备好项目成果数据与回归就业的口径", lastEmpEnd, months)
                            + COVERED_TRAILING_ADVICE);
        }
        RedFlag.Severity severity = months > GAP_HIGH_MONTHS ? RedFlag.Severity.HIGH : RedFlag.Severity.MEDIUM;
        return new RedFlag(RedFlag.TRAILING_GAP, severity,
                String.format("最后一段雇佣 %s 结束至今已约 %d 个月（尾部：无覆盖）——初筛必问，简历或面试需备口径",
                        lastEmpEnd, months)
                        + GAP_ACTIONABLE_ADVICE);
    }

    private static boolean coversLeaving(ResumeEntity p, LocalDate leavingEnd) {
        LocalDate[] r = ResumeDateParser.parseRange(p.value());
        return r != null && !r[0].isAfter(leavingEnd.plusMonths(3));
    }

    /**
     * 2. 跳槽频率：短工数量多或平均任期过短。
     * 应届/初级按实习口径——实习天然短任期，跳过平均任期检查，只盯极短期多次辗转。
     */
    private List<RedFlag> checkJobHopping(List<LocalDate[]> periods, Persona persona) {
        if (periods.size() < 3) {
            return List.of();
        }
        boolean newGrad = persona == Persona.NEW_GRAD;
        int shortThreshold = newGrad ? NEW_GRAD_SHORT_TENURE_MONTHS : SHORT_TENURE_MONTHS;
        long shortJobs = periods.stream()
                .filter(p -> ChronoUnit.MONTHS.between(p[0], p[1]) < shortThreshold)
                .count();
        long totalMonths = periods.stream()
                .mapToLong(p -> ChronoUnit.MONTHS.between(p[0], p[1]))
                .sum();
        long avgMonths = totalMonths / periods.size();

        List<RedFlag> flags = new ArrayList<>();
        if (!newGrad && avgMonths < AVG_TENURE_FLAG_MONTHS) {
            flags.add(new RedFlag(RedFlag.JOB_HOPPING, RedFlag.Severity.HIGH,
                    String.format("%d 段经历平均任期仅 %d 个月，会被质疑稳定性", periods.size(), avgMonths)));
        } else if (shortJobs >= (newGrad ? 3 : 2)) {
            flags.add(new RedFlag(RedFlag.JOB_HOPPING, RedFlag.Severity.MEDIUM,
                    String.format("%d 段经历任期不足 %d 个月，建议在简历中说明原因（如实习/项目制）", shortJobs, shortThreshold)));
        }
        return flags;
    }

    /** 3. 时间重叠：两段非教育经历重叠超过容忍期。 */
    private List<RedFlag> checkOverlap(List<LocalDate[]> periods) {
        List<RedFlag> flags = new ArrayList<>();
        for (int i = 1; i < periods.size(); i++) {
            LocalDate[] prev = periods.get(i - 1);
            LocalDate[] curr = periods.get(i);
            long overlap = ChronoUnit.MONTHS.between(curr[0], prev[1]);
            if (overlap > OVERLAP_TOLERANCE_MONTHS) {
                flags.add(new RedFlag(RedFlag.OVERLAP, RedFlag.Severity.MEDIUM,
                        String.format("两段经历时间重叠 %d 个月（%s 与 %s），如非兼职/并行项目会被质疑",
                                overlap, prev[0], curr[0])));
            }
        }
        return flags;
    }

    /** 4. 头衔与经验不匹配：高级头衔但总经验不足两年。 */
    private RedFlag checkTenureTitleMismatch(ResumeEntities entities, List<LocalDate[]> periods) {
        boolean seniorTitle = entities.getByType(ResumeEntity.EntityType.ROLE).stream()
                .anyMatch(r -> SENIOR_MARKERS.stream().anyMatch(m -> r.value().toLowerCase().contains(m)));
        if (!seniorTitle || periods.isEmpty()) {
            return null;
        }
        long totalMonths = periods.stream()
                .mapToLong(p -> ChronoUnit.MONTHS.between(p[0], p[1]))
                .sum();
        if (totalMonths < SENIOR_MIN_TOTAL_MONTHS) {
            return new RedFlag(RedFlag.TENURE_TITLE_MISMATCH, RedFlag.Severity.MEDIUM,
                    String.format("高级头衔但可识别的工作经历仅 %d 个月，头衔可信度会被挑战", totalMonths));
        }
        return null;
    }

    /** 5. 联系方式缺失：电话与邮箱均无。 */
    private RedFlag checkContact(String resumeText) {
        if (resumeText == null || resumeText.isBlank()) {
            return null;
        }
        if (PHONE.matcher(resumeText).find() || EMAIL.matcher(resumeText).find()) {
            return null;
        }
        return new RedFlag(RedFlag.CONTACT_MISSING, RedFlag.Severity.LOW,
                "未识别到电话或邮箱，HR 无法联系");
    }

    /** 6. 关键信息缺失：提示性质，不扣分（P11 的 completeness 存在性扣分改为警告）。 */
    private List<RedFlag> checkMissingSections(ResumeEntities entities, Persona persona) {
        List<RedFlag> flags = new ArrayList<>();
        if (entities.getByType(ResumeEntity.EntityType.EDUCATION).isEmpty()
                && persona != Persona.SENIOR) {
            flags.add(new RedFlag(RedFlag.SECTION_MISSING, RedFlag.Severity.LOW,
                    "未识别到教育经历信息（资深者可忽略，其他人群建议补充）"));
        }
        if (entities.getByType(ResumeEntity.EntityType.SKILL).isEmpty()) {
            flags.add(new RedFlag(RedFlag.SECTION_MISSING, RedFlag.Severity.LOW,
                    "未识别到明确的技术技能罗列，关键词检索难以命中"));
        }
        return flags;
    }

    /** 7. 量化比例过低：降级为提示（P11 中是 25% 权重的评分维度，数字密度可被刷分）。 */
    private RedFlag checkQuantification(ResumeEntities entities) {
        List<ResumeEntity> claims = entities.getByType(ResumeEntity.EntityType.CLAIM);
        if (claims.isEmpty()) {
            return null;
        }
        double ratio = (double) entities.getMetrics().size() / claims.size();
        if (ratio < 0.3) {
            return new RedFlag(RedFlag.LOW_QUANTIFICATION, RedFlag.Severity.LOW,
                    String.format("量化比例仅 %.0f%%（%d/%d 条声明有数字支撑）",
                            ratio * 100, entities.getMetrics().size(), claims.size()));
        }
        return null;
    }

    /** 红旗词表资源（lexicons/screening-lexicon.json），字段名与 JSON 键一致。 */
    record ScreeningLexicon(List<String> seniorTitleMarkers, List<String> educationMarkers) {

        static ScreeningLexicon load() {
            try {
                String json = new ClassPathResource("lexicons/screening-lexicon.json")
                        .getContentAsString(StandardCharsets.UTF_8);
                ScreeningLexicon lexicon = new ObjectMapper().readValue(json, ScreeningLexicon.class);
                if (lexicon == null || lexicon.seniorTitleMarkers().isEmpty()
                        || lexicon.educationMarkers().isEmpty()) {
                    throw new IllegalStateException("screening lexicon groups must not be empty");
                }
                return lexicon;
            } catch (IOException ex) {
                throw new IllegalStateException("screening lexicon missing or unreadable", ex);
            }
        }
    }
}
