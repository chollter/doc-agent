package com.gcll.ticketagent.understanding.followup;

import com.gcll.ticketagent.extract.TicketExtractResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

@Service
public class FollowUpQuestionService {

    private static final int MAX_QUESTIONS = 6;

    private final TemplateFollowUpProvider templateFollowUpProvider;
    private final LlmFollowUpProvider llmFollowUpProvider;

    public FollowUpQuestionService(
            TemplateFollowUpProvider templateFollowUpProvider,
            LlmFollowUpProvider llmFollowUpProvider
    ) {
        this.templateFollowUpProvider = templateFollowUpProvider;
        this.llmFollowUpProvider = llmFollowUpProvider;
    }

    public List<String> generate(
            String userContent,
            TicketExtractResult extract,
            List<String> missingSchemaFields,
            List<String> semanticGaps,
            List<String> gapSuggestedQuestions,
            String runId
    ) {
        List<String> templateQuestions = templateFollowUpProvider.questionsForMissingFields(missingSchemaFields);
        List<String> llmQuestions = llmFollowUpProvider.generate(
                userContent,
                extract,
                templateQuestions,
                semanticGaps,
                gapSuggestedQuestions,
                runId
        );

        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addAll(merged, gapSuggestedQuestions);
        addAll(merged, semanticGapFallbackQuestions(semanticGaps));
        addAll(merged, llmQuestions);
        addAll(merged, templateQuestions);

        List<String> result = new ArrayList<>();
        for (String question : merged) {
            if (question == null || question.isBlank()) {
                continue;
            }
            result.add(question.trim());
            if (result.size() >= MAX_QUESTIONS) {
                break;
            }
        }
        return result;
    }

    private List<String> semanticGapFallbackQuestions(List<String> semanticGaps) {
        if (semanticGaps == null || semanticGaps.isEmpty()) {
            return List.of();
        }
        List<String> questions = new ArrayList<>();
        for (String gap : semanticGaps) {
            if (gap == null || gap.isBlank()) {
                continue;
            }
            String normalized = gap.toLowerCase(Locale.ROOT);
            if (containsAny(normalized, "偶发", "必现", "频率")) {
                questions.add("问题是偶发还是必现？若偶发，大概多久出现一次？");
            }
            if (containsAny(normalized, "读接口", "写接口", "交易接口")) {
                questions.add("影响的是查询类接口，还是下单/支付等写接口？");
            }
            if (containsAny(normalized, "job", "任务名称", "任务名")) {
                questions.add("具体是哪个批处理 Job 或任务名称？");
            }
            if (containsAny(normalized, "结算窗口", "资金处理")) {
                questions.add("是否影响当日结算窗口或资金入账？");
            }
        }
        return questions;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void addAll(LinkedHashSet<String> target, List<String> questions) {
        if (questions == null) {
            return;
        }
        for (String question : questions) {
            if (question == null || question.isBlank()) {
                continue;
            }
            String normalized = question.trim();
            if (target.stream().noneMatch(existing -> existing.equalsIgnoreCase(normalized))) {
                target.add(normalized);
            }
        }
    }
}
