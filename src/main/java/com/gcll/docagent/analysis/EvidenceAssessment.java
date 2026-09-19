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
        boolean hasOriginalBasis
) {
    public EvidenceAssessment {
        evidenceFound = evidenceFound == null ? List.of() : List.copyOf(evidenceFound);
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
        likelyInterviewQuestions = likelyInterviewQuestions == null ? List.of() : List.copyOf(likelyInterviewQuestions);
        preparationAdvice = preparationAdvice == null ? List.of() : List.copyOf(preparationAdvice);
    }
}
