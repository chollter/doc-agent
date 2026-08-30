package com.gcll.docagent.analysis;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历模式检查：基于提取的语义实体进行确定性检查。
 * 不依赖 LLM，100% 稳定。
 */
@Component
public class ResumePatternChecker {

    /**
     * 检查简历模式，返回发现的问题列表。
     */
    public List<String> check(ResumeEntities entities) {
        List<String> findings = new ArrayList<>();

        // 1. 时间线空窗检查
        findings.addAll(entities.detectTimelineGaps());

        // 2. 量化比例检查
        String quantification = entities.checkQuantification();
        if (quantification != null) {
            findings.add(quantification);
        }

        // 3. 高级岗位领导力信号检查
        String seniorSignals = entities.checkSeniorSignals();
        if (seniorSignals != null) {
            findings.add(seniorSignals);
        }

        return findings;
    }

    /**
     * 检查简历中是否包含日期模式。
     */
    public boolean hasDatePattern(String text) {
        if (text == null) return false;
        Pattern pattern = Pattern.compile("\\d{4}[./-]\\d{1,2}");
        return pattern.matcher(text).find();
    }
}
