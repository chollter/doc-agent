package com.gcll.docagent.analysis;

import com.gcll.docagent.observability.trace.TraceRecorder;
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.parsing.ParsedDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 引文回锚校验——反幻觉确定性关卡：LLM 产出的每条引用必须能在原文对应节里逐字回锚，
 * 否则丢弃。sectionId 与 quote 必须同时命中；仅有 sectionId 不能让编造的 quote 通过。
 * <p>sectionId 缺失时退化为全节唯一匹配（quote 只在恰好一节出现才保留），避免误锚。
 * 空白规整后比较，兼容 PDF 断行/多余空格导致的字面差异。
 */
@Component
public class CitationVerifier {

    /** 引用校验：sectionId 与 quote 必须同时回锚；仅 sectionId 存在不能让编造 quote 通过。 */
    public AnalysisResult verify(AnalysisResult result, ParsedDocument doc, TraceRecorder tracer) {
        String stepId = tracer.begin("CITATION_VERIFY", null);
        List<AnalysisResult.Citation> kept = new ArrayList<>();
        int dropped = 0;
        for (AnalysisResult.Citation c : result.citations()) {
            if (c == null || c.sectionId() == null || c.quote() == null || c.quote().isBlank()) {
                dropped++;
                continue;
            }
            String normalized = c.sectionId().trim();
            var section = doc.findSection(normalized);
            if (section.isPresent() && sectionContains(section.get(), c.quote())) {
                kept.add(new AnalysisResult.Citation(normalized, c.quote().trim()));
            } else if (section.isEmpty()) {
                List<DocSection> matches = doc.sections().stream()
                        .filter(s -> sectionContains(s, c.quote()))
                        .toList();
                if (matches.size() == 1) {
                    kept.add(new AnalysisResult.Citation(matches.get(0).id(), c.quote().trim()));
                } else {
                    dropped++;
                }
            } else {
                dropped++;
            }
        }
        tracer.end(stepId, "kept=" + kept.size() + ", dropped=" + dropped, null);
        return result.withCitations(List.copyOf(kept));
    }

    private static boolean sectionContains(DocSection section, String quote) {
        if (section == null || quote == null || quote.isBlank()) return false;
        String content = (section.heading() == null ? "" : section.heading() + "\n")
                + (section.text() == null ? "" : section.text());
        return normalizeForAnchor(content).contains(normalizeForAnchor(quote));
    }

    private static String normalizeForAnchor(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }
}
