package com.gcll.docagent.analysis;

import com.gcll.docagent.observability.trace.TraceRecorder;
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.parsing.ParsedDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Adds narrowly scoped, source-anchored result evidence to experiences that are missing it. */
@Component
public class ExperienceEvidenceEnricher {

    private static final int MAX_EXPLORATIONS = 3;

    private final EvidenceExplorer evidenceExplorer;
    private final boolean enabled;
    private final AnalysisExecutionMode executionMode;

    public ExperienceEvidenceEnricher(
            EvidenceExplorer evidenceExplorer,
            @Value("${docagent.analysis.evidence-explore-enabled:false}") boolean enabled,
            @Value("${docagent.analysis.execution-mode:MOCK}") String executionMode) {
        this.evidenceExplorer = evidenceExplorer;
        this.enabled = enabled;
        this.executionMode = AnalysisExecutionMode.parse(executionMode, AnalysisExecutionMode.MOCK);
    }

    public List<EvidenceAssessment> enrich(String runId, ParsedDocument document, TraceRecorder tracer,
                                           List<EvidenceAssessment> existing,
                                           List<ExperienceStrength> strengths) {
        List<EvidenceAssessment> assessments = new ArrayList<>(existing == null ? List.of() : existing);
        addMissingResultAssessments(assessments, strengths, document);
        if (!enabled || executionMode != AnalysisExecutionMode.REAL) return List.copyOf(assessments);

        int explored = 0;
        for (int i = 0; i < assessments.size() && explored < MAX_EXPLORATIONS; i++) {
            EvidenceAssessment assessment = assessments.get(i);
            if (!needsResultEvidence(assessment)) continue;
            explored++;
            EvidenceExplorer.ExploreResult result = evidenceExplorer.explore(
                    runId, buildQuestion(assessment), document, tracer, null);
            for (AnalysisResult.Citation citation : result.evidence()) {
                if (EvidenceResultQualification.isQuantifiedResult(citation.quote())) {
                    assessments.set(i, assessment.withExploredResult(citation.sectionId(), citation.quote()));
                    break;
                }
            }
        }
        return List.copyOf(assessments);
    }

    private static void addMissingResultAssessments(List<EvidenceAssessment> assessments,
                                                    List<ExperienceStrength> strengths,
                                                    ParsedDocument document) {
        if (strengths == null) return;
        for (ExperienceStrength strength : strengths) {
            if (strength == null || strength.resultQuality() != ExperienceStrength.ResultQuality.NONE
                    || strength.sectionId() == null || strength.sectionId().isBlank()) continue;
            if (assessments.stream().anyMatch(a -> strength.sectionId().equals(a.sectionId()))) continue;
            DocSection section = document.findSection(strength.sectionId()).orElse(null);
            if (section == null) continue;
            String claim = strength.entryRef() == null || strength.entryRef().isBlank()
                    ? (section.heading() == null ? "这段经历" : section.heading()) : strength.entryRef();
            assessments.add(new EvidenceAssessment(claim, section.id(), "", EvidenceLevel.L1_ACTIVITY,
                    List.of(), List.of("可核验的规模、约束、结果或指标（只能补充真实数据）"),
                    List.of("这段经历是否在其他章节记录了可验证的结果或量化指标？"),
                    List.of("如确有结果，请提供统计口径和对照基线"), true));
        }
    }

    private static boolean needsResultEvidence(EvidenceAssessment assessment) {
        return assessment.evidenceLevel().ordinal() < EvidenceLevel.L3_RESULT.ordinal()
                && assessment.missingFacts().stream().anyMatch(fact -> fact.contains("结果或指标"));
    }

    private static String buildQuestion(EvidenceAssessment assessment) {
        String rawClaim = assessment.claim() == null ? "这段经历" : assessment.claim();
        String claim = rawClaim.substring(0, Math.min(80, rawClaim.length()));
        return "只为经历“" + claim
                + "”查找其他章节中的量化结果或业务影响。优先寻找带数字/单位且描述提升、降低、增长、耗时、规模或故障率的连续原文。"
                + "当前归属章节：" + assessment.sectionId()
                + "。找到时只返回该证据；没有就返回 insufficient，不要把年限、日期或无关数字当结果。";
    }
}
