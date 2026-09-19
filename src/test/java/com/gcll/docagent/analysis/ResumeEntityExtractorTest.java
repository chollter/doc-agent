package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.resilience.LlmResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResumeEntityExtractorTest {

    @Test
    @SuppressWarnings("unchecked")
    void malformedOrEmptyLlmPayloadIsMarkedAsFallback() {
        ObjectProvider<LlmGateway> provider = mock(ObjectProvider.class);
        LlmGateway gateway = mock(LlmGateway.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        when(gateway.invoke(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(LlmResponse.of("{}", 10, 5, "test-model"));

        ResumeEntityExtractor extractor = new ResumeEntityExtractor(
                provider, new ObjectMapper(), new EntityNormalizer());

        ExtractionOutcome outcome = extractor.extract("负责订单系统开发", "resume.md", "run-1");

        assertThat(outcome.degraded()).isTrue();
        assertThat(outcome.entities().isEmpty()).isTrue();
        verify(gateway, times(2)).invoke(anyString(), anyString(), anyString(), anyString());
    }

    /** 事故复现（log.txt 18:19:28）：LLM 把 results 输出为数组，旧代码整体解析失败 → 空实体降级。 */
    @Test
    @SuppressWarnings("unchecked")
    void resultsFieldAsArrayIsParsedLeniently() {
        ObjectProvider<LlmGateway> provider = mock(ObjectProvider.class);
        LlmGateway gateway = mock(LlmGateway.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        String json = """
                {"entities":[{"type":"SKILL","value":"Java","context":"技能"}],
                 "projects":[{"projectId":"project-1","sectionId":"sec-5",
                   "context":{"value":"DocAgent","status":"explicit","sourceQuote":"DocAgent"},
                   "results":[{"value":"140+ 测试","status":"explicit","sourceQuote":"140+ 个自动化测试"}]}]}
                """;
        when(gateway.invoke(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(LlmResponse.of(json, 100, 50, "qwen-plus"));

        ResumeEntityExtractor extractor = new ResumeEntityExtractor(
                provider, new ObjectMapper(), new EntityNormalizer());

        ExtractionOutcome outcome = extractor.extract("DocAgent 140+ 个自动化测试", "resume.md", "run-1");

        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.entities().getProjects()).hasSize(1);
        assertThat(outcome.entities().getProjects().get(0).results().value()).isEqualTo("140+ 测试");
    }

    /** 单个项目畸形只丢弃该项目，其余项目与实体幸存（逐项目 salvage）。 */
    @Test
    @SuppressWarnings("unchecked")
    void singleMalformedProjectDoesNotKillExtraction() {
        ObjectProvider<LlmGateway> provider = mock(ObjectProvider.class);
        LlmGateway gateway = mock(LlmGateway.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        String json = """
                {"entities":[{"type":"SKILL","value":"Java","context":"技能"}],
                 "projects":[
                   {"projectId":"bad","sectionId":"sec-1","responsibilities":"not-an-array"},
                   {"projectId":"good","sectionId":"sec-2",
                    "context":{"value":"Redmine","status":"explicit","sourceQuote":"Redmine"}}]}
                """;
        when(gateway.invoke(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(LlmResponse.of(json, 100, 50, "qwen-plus"));

        ResumeEntityExtractor extractor = new ResumeEntityExtractor(
                provider, new ObjectMapper(), new EntityNormalizer());

        ExtractionOutcome outcome = extractor.extract("Redmine 项目经验", "resume.md", "run-1");

        assertThat(outcome.degraded()).isFalse();
        assertThat(outcome.entities().getProjects()).hasSize(1);
        assertThat(outcome.entities().getProjects().get(0).projectId()).isEqualTo("good");
    }
}
