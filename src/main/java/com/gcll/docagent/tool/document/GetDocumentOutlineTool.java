package com.gcll.docagent.tool.document;

import com.gcll.docagent.analysis.DocumentStore;
import com.gcll.docagent.parsing.ParsedDocument;
import com.gcll.docagent.tool.ToolGateway;
import com.gcll.docagent.tool.ToolInvocation;
import com.gcll.docagent.tool.ToolResult;
import com.gcll.docagent.tool.ToolType;
import org.springframework.stereotype.Component;

/**
 * READ 工具：返回文档大纲（每节一行：id | 标题 | 字数 | 页码）。
 * ReAct 循环的第一步——模型先看大纲，再决定读哪些节。
 */
@Component
public class GetDocumentOutlineTool implements ToolGateway {

    private final DocumentStore documentStore;

    public GetDocumentOutlineTool(DocumentStore documentStore) {
        this.documentStore = documentStore;
    }

    @Override
    public ToolType toolType() {
        return ToolType.READ_FUNCTION;
    }

    @Override
    public String toolName() {
        return "get_document_outline";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        return documentStore.get(invocation.runId())
                .map(doc -> ToolResult.success(toolType(), toolName(),
                        "runId=" + invocation.runId(),
                        doc.outline(),
                        0))
                .orElseGet(() -> ToolResult.failure(toolType(), toolName(),
                        "runId=" + invocation.runId(),
                        "文档未加载（runId 无效或已过期）", 0));
    }
}
