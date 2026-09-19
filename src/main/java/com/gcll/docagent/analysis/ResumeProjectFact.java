package com.gcll.docagent.analysis;

import java.util.List;

/** Project-level resume facts with provenance for evidence matching. */
public record ResumeProjectFact(
        String projectId,
        String sectionId,
        Fact context,
        Fact problem,
        List<Fact> responsibilities,
        List<Fact> technologies,
        Fact aiPipeline,
        List<Fact> decisions,
        Fact results,
        Fact scale,
        Fact deployment
) {
    public ResumeProjectFact {
        responsibilities = responsibilities == null ? List.of() : List.copyOf(responsibilities);
        technologies = technologies == null ? List.of() : List.copyOf(technologies);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
    }

    public record Fact(String value, String status, String sourceQuote) {
        public boolean isExplicit() {
            return "explicit".equalsIgnoreCase(status);
        }

        public static Fact missing() {
            return new Fact(null, "missing", null);
        }
    }
}
