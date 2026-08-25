package com.gcll.docagent.tool.document;

import com.gcll.docagent.analysis.DocumentStore;
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.parsing.ParsedDocument;
import com.gcll.docagent.tool.ToolGateway;
import com.gcll.docagent.tool.ToolInvocation;
import com.gcll.docagent.tool.ToolResult;
import com.gcll.docagent.tool.ToolType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * READ 工具：关键词检索文档，返回命中节的 id + 上下文片段（每处 ±60 字，最多 8 处）。
 */
@Component
public class SearchDocumentTool implements ToolGateway {

    private static final int MAX_HITS = 8;
    private static final int SNIPPET_RADIUS = 60;
    private static final String KEYWORD = "keyword";

    private final DocumentStore documentStore;

    public SearchDocumentTool(DocumentStore documentStore) {
        this.documentStore = documentStore;
    }

    @Override
    public ToolType toolType() {
        return ToolType.READ_FUNCTION;
    }

    @Override
    public String toolName() {
        return "search_document";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String keyword = invocation.param(KEYWORD);
        if (keyword == null || keyword.isBlank()) {
            return ToolResult.failure(toolType(), toolName(), null, "缺少参数 keyword", 0);
        }
        return documentStore.get(invocation.runId())
                .map(doc -> search(invocation, doc, keyword.trim()))
                .orElseGet(() -> ToolResult.failure(toolType(), toolName(),
                        "keyword=" + keyword, "文档未加载（runId 无效或已过期）", 0));
    }

    private ToolResult search(ToolInvocation invocation, ParsedDocument doc, String keyword) {
        String needle = keyword.toLowerCase(Locale.ROOT);
        List<String> hits = new ArrayList<>();
        for (DocSection section : doc.sections()) {
            String text = section.text() == null ? "" : section.text();
            String lower = text.toLowerCase(Locale.ROOT);
            int idx = 0;
            while (idx != -1 && hits.size() < MAX_HITS) {
                idx = lower.indexOf(needle, idx);
                if (idx == -1) {
                    break;
                }
                int from = Math.max(0, idx - SNIPPET_RADIUS);
                int to = Math.min(text.length(), idx + needle.length() + SNIPPET_RADIUS);
                hits.add(section.id()
                        + (section.heading() != null ? "（" + section.heading() + "）" : "")
                        + "：" + (from > 0 ? "…" : "") + text.substring(from, to).replace("\n", " ")
                        + (to < text.length() ? "…" : ""));
                idx += needle.length();
            }
            if (hits.size() >= MAX_HITS) {
                break;
            }
        }
        String input = "keyword=" + keyword;
        if (hits.isEmpty()) {
            return ToolResult.success(toolType(), toolName(), input,
                    "未找到关键词「" + keyword + "」的匹配。可换个说法或改用 get_document_outline 浏览结构。", 0);
        }
        return ToolResult.success(toolType(), toolName(), input,
                "命中 " + hits.size() + " 处（上限 " + MAX_HITS + "）：\n" + String.join("\n", hits), 0);
    }
}
