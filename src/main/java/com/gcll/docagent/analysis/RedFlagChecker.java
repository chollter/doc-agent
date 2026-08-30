package com.gcll.docagent.analysis;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
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

    private static final List<String> SENIOR_MARKERS = List.of(
            "高级", "资深", "首席", "专家", "principal", "senior", "lead", "staff");

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
        addIfPresent(flags, checkTenureTitleMismatch(entities, workPeriods));
        addIfPresent(flags, checkContact(resumeText));
        flags.addAll(checkMissingSections(entities, persona));
        addIfPresent(flags, checkQuantification(entities));
        return flags;
    }

    private static void addIfPresent(List<RedFlag> flags, RedFlag flag) {
        if (flag != null) {
            flags.add(flag);
        }
    }

    /** 解析非教育时间段为 [start, end]，按开始时间排序。 */
    private List<LocalDate[]> parseWorkPeriods(ResumeEntities entities) {
        return entities.getByType(ResumeEntity.EntityType.TIME_PERIOD).stream()
                .filter(p -> !"education".equals(p.kind()))
                .map(p -> ResumeDateParser.parseRange(p.value()))
                .filter(r -> r != null)
                .sorted(Comparator.comparing(a -> a[0]))
                .toList();
    }

    /** 1. 时间线空窗：相邻工作时间段间隔 > 2 月。 */
    private List<RedFlag> checkTimelineGaps(List<LocalDate[]> periods) {
        List<RedFlag> flags = new ArrayList<>();
        for (int i = 1; i < periods.size(); i++) {
            long gap = ChronoUnit.MONTHS.between(periods.get(i - 1)[1], periods.get(i)[0]);
            if (gap > GAP_MEDIUM_MONTHS) {
                RedFlag.Severity severity = gap > GAP_HIGH_MONTHS ? RedFlag.Severity.HIGH : RedFlag.Severity.MEDIUM;
                flags.add(new RedFlag(RedFlag.TIMELINE_GAP, severity,
                        String.format("时间线有 %d 个月空窗（%s 至 %s）", gap,
                                periods.get(i - 1)[1], periods.get(i)[0])));
            }
        }
        return flags;
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
}
