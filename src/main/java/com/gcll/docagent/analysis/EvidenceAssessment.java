package com.gcll.docagent.analysis;

import java.util.List;

/** Deterministic, source-grounded assessment of one resume claim. */
public record EvidenceAssessment(
        String claim,
        String sectionId,
        String sourceQuote,
        EvidenceLevel evidenceLevel,
        List<String> evidenceFound,
        List<String> missingFacts,
        List<String> likelyInterviewQuestions,
        List<String> preparationAdvice,
        boolean hasOriginalBasis,
        String evidenceSource
) {
    public EvidenceAssessment(String claim, String sectionId, String sourceQuote,
                              EvidenceLevel evidenceLevel, List<String> evidenceFound,
                              List<String> missingFacts, List<String> likelyInterviewQuestions,
                              List<String> preparationAdvice, boolean hasOriginalBasis) {
        this(claim, sectionId, sourceQuote, evidenceLevel, evidenceFound, missingFacts,
                likelyInterviewQuestions, preparationAdvice, hasOriginalBasis, "RULE");
    }

    public EvidenceAssessment {
        evidenceFound = evidenceFound == null ? List.of() : List.copyOf(evidenceFound);
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
        likelyInterviewQuestions = likelyInterviewQuestions == null ? List.of() : List.copyOf(likelyInterviewQuestions);
        preparationAdvice = preparationAdvice == null ? List.of() : List.copyOf(preparationAdvice);
        evidenceSource = evidenceSource == null || evidenceSource.isBlank() ? "RULE" : evidenceSource;
    }

    public EvidenceAssessment withExploredResult(String foundSectionId, String quote) {
        if (!EvidenceResultQualification.isQuantifiedResult(quote)) return this;
        List<String> updatedEvidence = new java.util.ArrayList<>(evidenceFound);
        if (!updatedEvidence.contains(quote)) updatedEvidence.add(quote);
        List<String> updatedMissing = missingFacts.stream()
                .filter(fact -> !fact.contains("规模、约束、结果或指标"))
                .toList();
        return new EvidenceAssessment(claim, foundSectionId, quote, EvidenceLevel.L3_RESULT,
                updatedEvidence, updatedMissing, likelyInterviewQuestions,
                List.of("准备解释该结果的统计口径、对照基线和验证方式"), true, "REACT");
    }
}
