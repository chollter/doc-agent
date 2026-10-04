package com.gcll.docagent.analysis;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 语义实体聚合：从任意格式简历中提取的所有实体。
 * 提供便捷查询和模式检查方法。
 */
public class ResumeEntities {
    private final List<ResumeEntity> entities;
    private final List<ResumeProjectFact> projects;

    public ResumeEntities(List<ResumeEntity> entities) {
        this(entities, List.of());
    }

    public ResumeEntities(List<ResumeEntity> entities, List<ResumeProjectFact> projects) {
        this.entities = entities != null ? new ArrayList<>(entities) : new ArrayList<>();
        this.projects = projects != null ? new ArrayList<>(projects) : new ArrayList<>();
    }

    public List<ResumeEntity> getAll() {
        return entities;
    }

    public boolean isEmpty() {
        return entities.isEmpty();
    }

    public List<ResumeProjectFact> getProjects() {
        return List.copyOf(projects);
    }

    public List<ResumeEntity> getByType(ResumeEntity.EntityType type) {
        return ResumeEntity.filterByType(entities, type);
    }

    public List<String> getSkillNames() {
        return getByType(ResumeEntity.EntityType.SKILL).stream()
                .map(ResumeEntity::value).toList();
    }

    public List<ResumeEntity> getMetrics() {
        return getByType(ResumeEntity.EntityType.METRIC);
    }

    /**
     * 检测时间线空窗：解析工作时间段，计算相邻时间段的间隔。
     * <p>P12：教育时间段（kind=education）不参与职业空窗计算——
     * P11 把教育与工作时间混排求间隔，升学间隔会被误报为职业空窗。
     */
    public List<String> detectTimelineGaps() {
        List<ResumeEntity> periods = getByType(ResumeEntity.EntityType.TIME_PERIOD).stream()
                .filter(p -> !"education".equals(p.kind()))
                .toList();
        if (periods.size() < 2) {
            return List.of();
        }

        List<String> gaps = new ArrayList<>();
        List<LocalDate[]> parsed = new ArrayList<>();

        for (ResumeEntity period : periods) {
            LocalDate[] dates = ResumeDateParser.parseRange(period.value());
            if (dates != null) {
                parsed.add(dates);
            }
        }

        if (parsed.size() < 2) {
            return List.of();
        }

        parsed.sort((a, b) -> a[0].compareTo(b[0]));

        for (int i = 1; i < parsed.size(); i++) {
            LocalDate prevEnd = parsed.get(i - 1)[1];
            LocalDate currStart = parsed.get(i)[0];
            long monthsGap = java.time.temporal.ChronoUnit.MONTHS.between(prevEnd, currStart);
            if (monthsGap > 2) {
                gaps.add(String.format("时间线有 %d 个月空窗（%s 至 %s）",
                        monthsGap, prevEnd, currStart));
            }
        }

        return gaps;
    }

    /**
     * 检查量化比例：有数字支撑的声明占比。
     */
    public String checkQuantification() {
        List<ResumeEntity> claims = getByType(ResumeEntity.EntityType.CLAIM);
        List<ResumeEntity> metrics = getByType(ResumeEntity.EntityType.METRIC);

        if (claims.isEmpty()) {
            return null;
        }

        double ratio = (double) metrics.size() / claims.size();
        if (ratio < 0.3) {
            return String.format("量化比例仅 %.0f%%（%d/%d 条声明有数字支撑），建议增加具体指标",
                    ratio * 100, metrics.size(), claims.size());
        }

        return null;
    }

    /**
     * 检查高级岗位是否缺少领导力信号。
     */
    public String checkSeniorSignals() {
        List<ResumeEntity> roles = getByType(ResumeEntity.EntityType.ROLE);
        boolean hasSeniorRole = roles.stream()
                .anyMatch(r -> r.value().contains("高级") || r.value().contains("资深")
                        || r.value().contains("principal") || r.value().contains("senior"));

        if (!hasSeniorRole) {
            return null;
        }

        List<String> leadershipKeywords = List.of(
                "主导", "负责", "设计", "架构", "带领", "管理", "leader", "lead"
        );

        boolean hasLeadership = entities.stream()
                .filter(e -> e.type() == ResumeEntity.EntityType.CLAIM
                        || e.type() == ResumeEntity.EntityType.ACHIEVEMENT)
                .anyMatch(e -> leadershipKeywords.stream()
                        .anyMatch(kw -> e.value().toLowerCase().contains(kw)));

        if (!hasLeadership) {
            return "有高级职位但缺少领导力信号（主导/设计/架构/带领等）";
        }

        return null;
    }
}
