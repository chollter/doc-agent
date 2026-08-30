package com.gcll.docagent.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 ResumeEntities 聚合出结构化 ResumeProfile（纯代码，不依赖 LLM）。
 * <p>把扁平的实体列表组装成面试官/HR 想看的结构化视图：
 * 候选人姓名、工作年限、当前角色、工作经历时间线、技能矩阵、教育背景。
 */
@Component
public class ResumeProfileBuilder {

    private static final Logger log = LoggerFactory.getLogger(ResumeProfileBuilder.class);
    private static final Pattern DATE_PATTERN = Pattern.compile("(\\d{4})[./-](\\d{1,2})");

    /**
     * 从实体列表构建候选人画像。
     * 实体不足时优雅降级（字段为 null 或空列表）。
     */
    public ResumeProfile build(ResumeEntities entities) {
        if (entities == null || entities.isEmpty()) {
            return null;
        }

        String name = extractName(entities);
        List<LocalDate[]> periods = parseAllPeriods(entities);
        int yearsOfExperience = computeYears(periods);
        String currentRole = extractCurrentRole(entities);
        List<ResumeProfile.WorkEntry> workTimeline = buildWorkTimeline(entities, periods);
        SkillMatrix skillMatrix = SkillMatrix.fromSkillNames(entities.getSkillNames());
        List<ResumeProfile.EducationEntry> education = buildEducation(entities);

        return new ResumeProfile(name, yearsOfExperience, currentRole, workTimeline, skillMatrix, education);
    }

    /**
     * 从 ROLE 或 ORGANIZATION 实体中推断姓名。
     * 优先取第一个 ROLE 的 context（通常包含姓名），否则取 ORGANIZATION context。
     */
    private String extractName(ResumeEntities entities) {
        // 尝试从 ROLE 的 context 中提取（如 "张某 · 高级工程师" → "张某"）
        List<ResumeEntity> roles = entities.getByType(ResumeEntity.EntityType.ROLE);
        for (ResumeEntity role : roles) {
            if (role.context() != null && !role.context().isBlank()) {
                String ctx = role.context().trim();
                // 取第一个 · 或 - 之前的部分
                int sep = Math.min(
                        ctx.indexOf('·') >= 0 ? ctx.indexOf('·') : Integer.MAX_VALUE,
                        ctx.indexOf('-') >= 0 ? ctx.indexOf('-') : Integer.MAX_VALUE
                );
                if (sep > 0 && sep < 20) {
                    return ctx.substring(0, sep).trim();
                }
                if (ctx.length() < 30) {
                    return ctx;
                }
            }
        }
        return null;
    }

    /** 解析所有 TIME_PERIOD 实体为日期范围。 */
    private List<LocalDate[]> parseAllPeriods(ResumeEntities entities) {
        List<LocalDate[]> periods = new ArrayList<>();
        for (ResumeEntity period : entities.getByType(ResumeEntity.EntityType.TIME_PERIOD)) {
            LocalDate[] dates = parseDateRange(period.value());
            if (dates != null) {
                periods.add(dates);
            }
        }
        return periods;
    }

    /** 计算总工作年限（从最早开始到最晚结束的跨度）。 */
    private int computeYears(List<LocalDate[]> periods) {
        if (periods.isEmpty()) return 0;
        LocalDate earliest = periods.stream().map(p -> p[0]).min(Comparator.naturalOrder()).orElse(null);
        LocalDate latest = periods.stream().map(p -> p[1]).max(Comparator.naturalOrder()).orElse(null);
        if (earliest == null || latest == null) return 0;
        long months = ChronoUnit.MONTHS.between(earliest, latest);
        return Math.max(1, (int) Math.round(months / 12.0));
    }

    /** 取最近的 ROLE 实体作为当前角色。 */
    private String extractCurrentRole(ResumeEntities entities) {
        List<ResumeEntity> roles = entities.getByType(ResumeEntity.EntityType.ROLE);
        if (roles.isEmpty()) return null;
        // 返回最后一个角色（通常简历按时间倒序，最后一个是最近的）
        return roles.get(roles.size() - 1).value();
    }

    /**
     * 构建工作经历时间线。
     * 尝试将 ORGANIZATION + ROLE + TIME_PERIOD + CLAIM 按顺序关联。
     * 简化策略：按 ORGANIZATION 实体分段，每段关联其后的 ROLE、CLAIM。
     */
    private List<ResumeProfile.WorkEntry> buildWorkTimeline(ResumeEntities entities, List<LocalDate[]> periods) {
        List<ResumeProfile.WorkEntry> timeline = new ArrayList<>();
        List<ResumeEntity> orgs = entities.getByType(ResumeEntity.EntityType.ORGANIZATION);
        List<ResumeEntity> roles = entities.getByType(ResumeEntity.EntityType.ROLE);
        List<ResumeEntity> claims = entities.getByType(ResumeEntity.EntityType.CLAIM);

        // 策略：每个 ORGANIZATION 对应一段工作经历
        for (int i = 0; i < orgs.size(); i++) {
            ResumeEntity org = orgs.get(i);
            String company = org.value();

            // 角色：取与组织位置相近的 role
            String role = null;
            if (i < roles.size()) {
                role = roles.get(i).value();
            }

            // 时间段：取对应的时间
            String period = null;
            int durationMonths = 0;
            List<ResumeEntity> timePeriods = entities.getByType(ResumeEntity.EntityType.TIME_PERIOD);
            if (i < timePeriods.size()) {
                period = timePeriods.get(i).value();
                LocalDate[] dates = parseDateRange(period);
                if (dates != null) {
                    durationMonths = (int) ChronoUnit.MONTHS.between(dates[0], dates[1]);
                }
            }

            // 亮点：取属于这段经历的 claims（简化：按索引均分）
            List<String> highlights = new ArrayList<>();
            if (!claims.isEmpty()) {
                int claimsPerOrg = Math.max(1, claims.size() / Math.max(1, orgs.size()));
                int start = i * claimsPerOrg;
                int end = Math.min(start + claimsPerOrg, claims.size());
                for (int j = start; j < end; j++) {
                    highlights.add(claims.get(j).value());
                }
            }

            timeline.add(new ResumeProfile.WorkEntry(company, role, period, durationMonths, highlights));
        }

        return timeline;
    }

    /** 从 EDUCATION 实体构建教育经历。 */
    private List<ResumeProfile.EducationEntry> buildEducation(ResumeEntities entities) {
        List<ResumeProfile.EducationEntry> education = new ArrayList<>();
        for (ResumeEntity edu : entities.getByType(ResumeEntity.EntityType.EDUCATION)) {
            Map<String, String> attrs = edu.attributes();
            education.add(new ResumeProfile.EducationEntry(
                    attrs.getOrDefault("school", edu.value()),
                    attrs.get("degree"),
                    attrs.get("major"),
                    attrs.get("year")
            ));
        }
        return education;
    }

    private LocalDate[] parseDateRange(String dateRange) {
        if (dateRange == null) return null;
        Matcher matcher = DATE_PATTERN.matcher(dateRange);
        List<LocalDate> dates = new ArrayList<>();
        while (matcher.find()) {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            try {
                dates.add(LocalDate.of(year, month, 1));
            } catch (Exception e) {
                // ignore invalid dates
            }
        }
        if (dates.size() >= 2) {
            return new LocalDate[]{dates.get(0), dates.get(1)};
        } else if (dates.size() == 1) {
            return new LocalDate[]{dates.get(0), dates.get(0)};
        }
        return null;
    }
}
