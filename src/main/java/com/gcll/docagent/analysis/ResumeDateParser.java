package com.gcll.docagent.analysis;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历日期解析——全项目唯一的时间解析入口。
 * <p>P12 前日期解析在 ResumeEntities / ResumeProfileBuilder / ResumePatternChecker 三处重复实现，
 * 且均不支持中文格式（"2020年3月"），导致中文日期简历的时间线检查静默失效。
 * P12 起统一收口：支持 2020.03 / 2020/3 / 2020-03 / 2020年3月；
 * "至今/现在/present" 出现在日期之后时解析为开放区间（终点=当前月）。
 */
public final class ResumeDateParser {

    /** 日期片段：年 + 分隔符(. / - 年，`-` 置尾避免被解析为范围运算符) + 月，月可带"月"后缀。 */
    private static final Pattern DATE_PART =
            Pattern.compile("(\\d{4})\\s*[./年-]\\s*(\\d{1,2})(?:\\s*月)?");

    /** 开放端点词：出现在日期之后表示"至今"。 */
    private static final Pattern OPEN_ENDED =
            Pattern.compile("至今|现在|目前|present|current|now", Pattern.CASE_INSENSITIVE);

    private static final int MIN_YEAR = 1950;
    private static final int MAX_YEAR = 2100;

    private ResumeDateParser() {
    }

    /** 文本中是否包含可识别的日期模式。 */
    public static boolean hasDatePattern(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return DATE_PART.matcher(text).find();
    }

    /**
     * 解析时间段字符串为 [start, end]（均取当月 1 日）。
     * <p>多个日期取最小/最大——容忍 "2020.01 - 2021.02（其间 2020.06 转岗）" 这类内嵌噪声日期，
     * 也容忍起止倒序的笔误。仅一个日期时视为单点区间，其后紧跟开放端点词则终点=当前月；
     * 日期片段出现多次但有无效值（如月份 13）时拒绝解析，避免产出错误的单点区间。
     */
    public static LocalDate[] parseRange(String period) {
        if (period == null || period.isBlank()) {
            return null;
        }
        Matcher matcher = DATE_PART.matcher(period);
        List<LocalDate> dates = new ArrayList<>();
        int matchCount = 0;
        int lastMatchStart = -1;
        while (matcher.find()) {
            matchCount++;
            LocalDate d = safeDate(matcher.group(1), matcher.group(2));
            if (d != null) {
                dates.add(d);
                lastMatchStart = matcher.start();
            }
        }
        if (dates.isEmpty() || (matchCount >= 2 && dates.size() < 2)) {
            return null;
        }
        LocalDate start = dates.stream().min(LocalDate::compareTo).orElseThrow();
        LocalDate end = dates.stream().max(LocalDate::compareTo).orElseThrow();
        if (dates.size() == 1 && lastMatchStart >= 0
                && OPEN_ENDED.matcher(period).find(lastMatchStart)) {
            end = LocalDate.now().withDayOfMonth(1);
        }
        return new LocalDate[]{start, end};
    }

    /**
     * 规范化时间段为 "yyyy.MM" 形式（"2020年3月-2021年5月" → "2020.03-2021.05"）。
     * 逐段原位替换，保留"至今"等非日期内容；无法解析时原样返回。
     */
    public static String normalize(String period) {
        if (period == null || period.isBlank()) {
            return period;
        }
        Matcher matcher = DATE_PART.matcher(period);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            int month = Integer.parseInt(matcher.group(2));
            matcher.appendReplacement(sb, matcher.group(1) + String.format(".%02d", month));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static LocalDate safeDate(String yearText, String monthText) {
        int year = Integer.parseInt(yearText);
        int month = Integer.parseInt(monthText);
        if (year < MIN_YEAR || year > MAX_YEAR || month < 1 || month > 12) {
            return null;
        }
        return YearMonth.of(year, month).atDay(1);
    }

    /**
     * 扫描全文中出现的所有日期（升序去重）——实体抽取降级时的文本级兜底：
     * 不区分教育/工作流，只保证"时间线空窗"这类硬伤检测不因抽取失败而静默缺失。
     */
    public static List<LocalDate> scanDates(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<LocalDate> dates = new ArrayList<>();
        Matcher matcher = DATE_PART.matcher(text);
        while (matcher.find()) {
            LocalDate d = safeDate(matcher.group(1), matcher.group(2));
            if (d != null) {
                dates.add(d);
            }
        }
        return dates.stream().distinct().sorted().toList();
    }
}
