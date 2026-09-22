package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.loop.AgentLoop;
import com.gcll.docagent.observability.trace.TraceRecorder;
import com.gcll.docagent.parsing.ParsedDocument;
import com.gcll.docagent.tool.ToolExecutionHolder;
import com.gcll.docagent.langchain4j.ReActContextHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 受限的证据探索器：只读查找原文证据，不负责生成分析报告。
 */
@Component
public class EvidenceExplorer {

    private static final List<String> READ_ONLY_TOOLS = List.of(
            "get_document_outline", "read_section", "search_document");

    private final AgentLoop agentLoop;
    private final ObjectMapper objectMapper;

    public EvidenceExplorer(AgentLoop agentLoop, ObjectMapper objectMapper) {
        this.agentLoop = agentLoop;
        this.objectMapper = objectMapper;
    }

    public ExploreResult explore(String runId, String question, ParsedDocument document,
                                 TraceRecorder tracer, String parentStepId) {
        String prompt = loadPrompt();
        String stepId = tracer.begin("EVIDENCE_EXPLORE", parentStepId);
        tracer.recordMeta(stepId, true, "read-only-tools");
        try {
            ToolExecutionHolder.setRunId(runId);
            ReActContextHolder.set(prompt, tracer, stepId);
            AgentLoop.LoopResult result = agentLoop.run(new AgentLoop.LoopContext(
                    runId, "resume-evidence", READ_ONLY_TOOLS, prompt,
                    question == null ? "请寻找能够支持当前分析的原文证据。" : question,
                    document, tracer, stepId, null));
            if (!result.success()) {
                tracer.end(stepId, "exploration stopped: " + result.stopReason(), result.stopReason());
                return ExploreResult.insufficient(result.stopReason());
            }
            ExploreResult parsed = parse(result.finalAnswer(), document);
            tracer.end(stepId, "evidence=" + parsed.evidence().size(), null);
            return parsed;
        } catch (Exception ex) {
            tracer.end(stepId, "exploration failed: " + ex.getMessage(), ex.getMessage());
            return ExploreResult.insufficient("EXPLORER_ERROR");
        } finally {
            ReActContextHolder.clear();
            ToolExecutionHolder.clear();
        }
    }

    // package-private：允许在不启动模型和 Trace 基础设施的情况下验证证据不变量。
    ExploreResult parse(String content, ParsedDocument document) {
        try {
            JsonNode root = objectMapper.readTree(stripFences(content));
            List<AnalysisResult.Citation> evidence = new ArrayList<>();
            JsonNode items = root.path("evidence");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String sectionId = item.path("sectionId").asText(null);
                    String quote = item.path("quote").asText(null);
                    if (sectionId == null || quote == null || quote.isBlank()) continue;
                    document.findSection(sectionId).ifPresent(section -> {
                        String text = (section.heading() == null ? "" : section.heading() + "\n") + section.text();
                        if (normalize(text).contains(normalize(quote))) {
                            evidence.add(new AnalysisResult.Citation(sectionId, quote.trim()));
                        }
                    });
                }
            }
            return new ExploreResult(root.path("sufficient").asBoolean(!evidence.isEmpty()),
                    List.copyOf(evidence), root.path("reason").asText(""));
        } catch (Exception ignored) {
            return ExploreResult.insufficient("INVALID_EXPLORER_OUTPUT");
        }
    }

    private String loadPrompt() {
        try {
            return new ClassPathResource("prompts/resume-evidence-explore.txt")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("证据探索提示词缺失", ex);
        }
    }

    private static String stripFences(String value) {
        if (value == null) return "{}";
        return value.trim().replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    public record ExploreResult(boolean sufficient, List<AnalysisResult.Citation> evidence, String reason) {
        public static ExploreResult insufficient(String reason) {
            return new ExploreResult(false, List.of(), reason);
        }
    }
}
