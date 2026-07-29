package com.gcll.ticketagent.understanding.followup;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;

class FollowUpQuestionServiceTest {

    @Test
    void semanticGapFallbackKeepsReproducibilityKeywordsBeforeLlmRewrite() {
        LlmFollowUpProvider llm = Mockito.mock(LlmFollowUpProvider.class);
        Mockito.when(llm.generate(anyString(), any(), anyList(), anyList(), anyList(), isNull()))
                .thenReturn(List.of("问题发生是否有规律？例如固定时间段、特定订单类型或并发量下出现？"));
        FollowUpQuestionService service = new FollowUpQuestionService(new TemplateFollowUpProvider(), llm);
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.INCIDENT,
                "支付系统", null, "支付接口", null, null,
                "生产", null, null, "生产支付接口偶尔慢",
                List.of(), 0.6
        );

        List<String> questions = service.generate(
                "生产支付接口偶尔慢",
                extract,
                List.of("timeRange", "errorDetail"),
                List.of("未说明偶发还是必现（原文‘偶发慢’‘偶尔慢’属模糊表述，需明确是否可复现、频率、触发条件）"),
                List.of(),
                null
        );

        assertThat(questions).anyMatch(question -> question.contains("偶发") && question.contains("必现"));
        assertThat(questions).hasSizeLessThanOrEqualTo(6);
    }

    @Test
    void semanticGapFallbackKeepsBatchJobAndSettlementWindowQuestions() {
        LlmFollowUpProvider llm = Mockito.mock(LlmFollowUpProvider.class);
        Mockito.when(llm.generate(anyString(), any(), anyList(), anyList(), anyList(), isNull()))
                .thenReturn(List.of());
        FollowUpQuestionService service = new FollowUpQuestionService(new TemplateFollowUpProvider(), llm);
        TicketExtractResult extract = new TicketExtractResult(
                IssueType.CONSULT,
                "结算系统", null, "结算批处理", null, null,
                null, null, null, "结算批处理有时跑不完",
                List.of(), 0.6
        );

        List<String> questions = service.generate(
                "结算批处理有时跑不完",
                extract,
                List.of("environment", "timeRange"),
                List.of("未提供具体批处理 Job 名", "未说明结算窗口是否受影响"),
                List.of(),
                null
        );

        assertThat(questions).anyMatch(question -> question.contains("Job") || question.contains("任务名称"));
        assertThat(questions).anyMatch(question -> question.contains("结算窗口"));
    }
}
