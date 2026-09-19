package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 将一份具体 JD 提取为可审计的岗位标准，不在这里评价候选人。 */
@Service
public class JobProfileExtractor {
    private static final Logger log = LoggerFactory.getLogger(JobProfileExtractor.class);
    private static final String CALL_NAME = "llm.jd-profile";
    private static final String PROMPT_FILE = "resume-jd-profile.txt";

    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final ObjectMapper objectMapper;

    public JobProfileExtractor(ObjectProvider<LlmGateway> llmGatewayProvider, ObjectMapper objectMapper) {
        this.llmGatewayProvider = llmGatewayProvider;
        this.objectMapper = objectMapper;
    }

    public TargetProfileOutcome extract(String jobDescription, String runId) {
        if (jobDescription == null || jobDescription.isBlank()) return TargetProfileOutcome.fallback();
        LlmGateway gateway = llmGatewayProvider.getIfAvailable();
        if (gateway == null) return TargetProfileOutcome.fallback();
        try {
            LlmResponse response = gateway.invoke(CALL_NAME, PROMPT_FILE, jobDescription, runId);
            TargetProfile profile = parse(response.content());
            return profile.requirements().isEmpty() ? TargetProfileOutcome.fallback()
                    : new TargetProfileOutcome(profile, false);
        } catch (Exception ex) {
            log.warn("JD profile extraction failed, using raw JD fallback: {}", ex.getMessage());
            return TargetProfileOutcome.fallback();
        }
    }

    // Package-visible for contract tests. Invalid/missing requirements are dropped rather than guessed.
    TargetProfile parse(String content) throws Exception {
        String json = content == null ? "" : content.trim()
                .replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalArgumentException("JD profile response has no JSON object");
        RawProfile raw = objectMapper.readValue(json.substring(start, end + 1), RawProfile.class);
        List<TargetProfile.Requirement> requirements = new ArrayList<>();
        if (raw.requirements != null) for (RawRequirement r : raw.requirements) {
            if (r != null && hasText(r.id) && hasText(r.requirement)) {
                requirements.add(new TargetProfile.Requirement(r.id.trim(), r.requirement.trim(),
                        priority(r.priority), strings(r.evidenceExpected), strings(r.keywords), r.disqualifier));
            }
        }
        List<TargetProfile.Variant> variants = raw.variants == null ? List.of() : raw.variants.stream()
                .filter(v -> v != null && hasText(v.id) && hasText(v.name))
                .map(v -> new TargetProfile.Variant(v.id.trim(), v.name.trim(), strings(v.differentiators))).toList();
        return new TargetProfile(TargetProfile.MODE_JD, hasText(raw.profileId) ? raw.profileId.trim() : null,
                hasText(raw.title) ? raw.title.trim() : "具体 JD", raw.summary,
                requirements, variants, strings(raw.screeningQuestions), false);
    }

    private static String priority(String value) {
        return "MUST".equalsIgnoreCase(value) || "IMPORTANT".equalsIgnoreCase(value)
                || "NICE_TO_HAVE".equalsIgnoreCase(value) ? value.toUpperCase() : "IMPORTANT";
    }
    private static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static List<String> strings(List<String> values) {
        return values == null ? List.of() : values.stream().filter(JobProfileExtractor::hasText).map(String::trim).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawProfile { public String profileId; public String title; public String summary; public List<RawRequirement> requirements; public List<RawVariant> variants; public List<String> screeningQuestions; }
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawRequirement { public String id; public String requirement; public String priority; public List<String> evidenceExpected; public List<String> keywords; public boolean disqualifier; }
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawVariant { public String id; public String name; public List<String> differentiators; }
}
