package com.gcll.docagent.analysis;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 落地性校验器——把"整类问题"变成结构性不可能，而不是逐个修 bug 实例。
 * <p>不变量 1（反编造）：改写建议 after 中出现的每个数字，要么能在简历原文
 * （或 before 引文）中找到出处，要么必须位于【…】占位符内。LLM 无中生有的
 * "200+实例 / 12%→0.3%"在这条规则下必然被标记——编造数据这一类问题直接灭绝。
 * <p>不变量 2（引文真实）：before 声称是"简历原文引用"，必须是原文的连续片段
 * （空白规整后匹配），否则标记为失锚。
 * <p>校验是确定性字符串匹配，不依赖 LLM，可单测、可进评测断言。
 */
@Component
public class GroundingValidator {

    /** 阿拉伯数字（含小数）。中文数字（三期/五年）不校验——LLM 转写中文数词不构成编造。 */
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");

    /** 占位符区间内的数字豁免——【补充真实数据：QPS】是给用户的填空指引。 */
    private static final Pattern PLACEHOLDER = Pattern.compile("【[^】]*】");

    public record Finding(String type, String ref, String detail) {
        public static final String FABRICATED_NUMBER = "FABRICATED_NUMBER";
        public static final String UNGROUNDED_BEFORE = "UNGROUNDED_BEFORE";
    }

    /**
     * 校验全部改写建议的落地性。
     *
     * @param suggestions LLM 产出的建议列表
     * @param resumeText  简历全文（数字出处与 before 锚定的比对基准）
     * @return 违规清单（空列表 = 全部落地）
     */
    public List<Finding> validate(List<ActionableSuggestion> suggestions, String resumeText) {
        List<Finding> findings = new ArrayList<>();
        if (suggestions == null || suggestions.isEmpty()) {
            return findings;
        }
        String normalizedResume = normalize(resumeText == null ? "" : resumeText);
        for (int i = 0; i < suggestions.size(); i++) {
            ActionableSuggestion s = suggestions.get(i);
            if (s == null) {
                continue;
            }
            String ref = "actionableSuggestions[" + i + "]";
            if (s.after() != null) {
                for (String num : extractGroundableNumbers(s.after())) {
                    boolean grounded = normalizedResume.contains(num) || (s.before() != null && s.before().contains(num));
                    if (!grounded) {
                        findings.add(new Finding(Finding.FABRICATED_NUMBER, ref,
                                "数字「" + num + "」在简历原文与 before 引文中均无出处——应使用【补充真实数据】占位"));
                    }
                }
            }
            if (s.before() != null && !s.before().isBlank()
                    && !normalizedResume.contains(normalize(s.before()))) {
                findings.add(new Finding(Finding.UNGROUNDED_BEFORE, ref,
                        "before 引文不是简历原文的连续片段：" + truncate(s.before())));
            }
        }
        return findings;
    }

    /** 提取需要落地的数字：排除占位符区间内的。 */
    static List<String> extractGroundableNumbers(String text) {
        List<String> numbers = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return numbers;
        }
        // 先把占位符区间替换为等长#，使区间内数字不被匹配
        String masked = PLACEHOLDER.matcher(text).replaceAll(m -> "#".repeat(m.group().length()));
        Matcher matcher = NUMBER.matcher(masked);
        while (matcher.find()) {
            numbers.add(matcher.group());
        }
        return numbers;
    }

    /** 空白规整：PDF 抽取的换行/多空格会打断连续片段匹配。 */
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String truncate(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > 30 ? t.substring(0, 30) + "…" : t;
    }
}
