package com.gcll.docagent.analysis;

import com.gcll.docagent.observability.trace.TraceRecorder;
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.parsing.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExperienceEvidenceEnricherTest {

    private static final ParsedDocument DOCUMENT = new ParsedDocument("resume.md", "markdown", List.of(
            new DocSection("sec-1", "工作经历", "负责支付平台重构", null),
            new DocSection("sec-2", "项目结果", "故障率从 2% 降低到 0.5%", null)));

    @Test
    void exploresOnlyMissingResultAndUpgradesOnAnchoredQuantifiedImpact() {
        EvidenceExplorer explorer = mock(EvidenceExplorer.class);
        when(explorer.explore(anyString(), anyString(), any(), any(), any())).thenReturn(
                new EvidenceExplorer.ExploreResult(true,
                        List.of(new AnalysisResult.Citation("sec-2", "故障率从 2% 降低到 0.5%")), ""));
        ExperienceEvidenceEnricher enricher = new ExperienceEvidenceEnricher(explorer, true, "REAL");

        List<EvidenceAssessment> result = enricher.enrich("run-1", DOCUMENT, mock(TraceRecorder.class),
                List.of(), List.of(missingResultStrength("sec-1")));

        assertThat(result).singleElement().satisfies(assessment -> {
            assertThat(assessment.evidenceSource()).isEqualTo("REACT");
            assertThat(assessment.evidenceLevel()).isEqualTo(EvidenceLevel.L3_RESULT);
            assertThat(assessment.sectionId()).isEqualTo("sec-2");
        });
        verify(explorer).explore(anyString(), anyString(), any(), any(), any());
    }

    @Test
    void keepsOriginalAssessmentWhenExplorationHasNoQualifiedResult() {
        EvidenceExplorer explorer = mock(EvidenceExplorer.class);
        when(explorer.explore(anyString(), anyString(), any(), any(), any())).thenReturn(
                new EvidenceExplorer.ExploreResult(true,
                        List.of(new AnalysisResult.Citation("sec-2", "参与 2023 年项目开发")), ""));
        ExperienceEvidenceEnricher enricher = new ExperienceEvidenceEnricher(explorer, true, "REAL");

        List<EvidenceAssessment> result = enricher.enrich("run-1", DOCUMENT, mock(TraceRecorder.class),
                List.of(), List.of(missingResultStrength("sec-1")));

        assertThat(result).singleElement().satisfies(assessment -> {
            assertThat(assessment.evidenceSource()).isEqualTo("RULE");
            assertThat(assessment.evidenceLevel()).isEqualTo(EvidenceLevel.L1_ACTIVITY);
            assertThat(assessment.sectionId()).isEqualTo("sec-1");
        });
    }

    @Test
    void doesNotExploreWhenResultAlreadyExistsOrExecutionIsNotReal() {
        EvidenceExplorer explorer = mock(EvidenceExplorer.class);
        ExperienceEvidenceEnricher realEnricher = new ExperienceEvidenceEnricher(explorer, true, "REAL");
        ExperienceEvidenceEnricher mockEnricher = new ExperienceEvidenceEnricher(explorer, true, "MOCK");
        EvidenceAssessment existingResult = new EvidenceAssessment("支付平台", "sec-1", "故障率降到 0.5%",
                EvidenceLevel.L3_RESULT, List.of("故障率降到 0.5%"), List.of(), List.of(), List.of(), true);

        realEnricher.enrich("run-1", DOCUMENT, mock(TraceRecorder.class), List.of(existingResult), List.of());
        mockEnricher.enrich("run-1", DOCUMENT, mock(TraceRecorder.class), List.of(),
                List.of(missingResultStrength("sec-1")));

        verify(explorer, never()).explore(anyString(), anyString(), any(), any(), any());
    }

    private static ExperienceStrength missingResultStrength(String sectionId) {
        return new ExperienceStrength(sectionId, "支付平台", Map.of("result", false),
                ExperienceStrength.ResultQuality.NONE, ExperienceStrength.Attribution.PARTICIPANT,
                "缺少结果");
    }
}
