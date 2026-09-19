package com.gcll.docagent.analysis;

import java.util.List;

/** Business diagnosis of one weak resume claim and how to strengthen it with real facts. */
public record ResumeDiagnosis(
        String severity,
        String target,
        String sectionId,
        String claim,
        String problemType,
        String whyItHurts,
        List<String> missingFacts,
        String strengtheningDirection,
        String interviewQuestion,
        EvidenceLevel evidenceLevel
) {
    public ResumeDiagnosis {
        missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
    }
}
