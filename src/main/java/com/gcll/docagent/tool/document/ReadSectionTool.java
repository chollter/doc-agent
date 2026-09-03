package com.gcll.docagent.tool.document;

import com.gcll.docagent.analysis.DocumentStore;
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.tool.ToolGateway;
import com.gcll.docagent.tool.ToolInvocation;
import com.gcll.docagent.tool.ToolResult;
import com.gcll.docagent.tool.ToolType;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * READ 工具：按节 ID 读取文档正文。超长节截断到 4000 字并提示续读方式。
 */
@Component
public class ReadSectionTool implements ToolGateway {

    private static final int MAX_RETURN_CHARS = 4000;
    private static final String SECTION_ID = "sectionId";

    private final DocumentStore documentStore;

    public ReadSectionTool(DocumentStore documentStore) {
        this.documentStore = documentStore;
    }

    @Override
    public ToolType toolType() {
        return ToolType.READ_FUNCTION;
    }

    @Override
    public String toolName() {
        return "read_section";
    }

    @Override
    public ToolResult execute(ToolInvocation invocation) {
        String sectionId = invocation.param(SECTION_ID);
        if (sectionId == null || sectionId.isBlank()) {
            return failure(invocation, "缺少参数 sectionId（如 sec-1）");
        }
        return documentStore.get(invocation.runId())
                .flatMap(doc -> doc.findSection(sectionId.trim()))
                .map(section -> ToolResult.success(toolType(), toolName(),
                        "sectionId=" + sectionId,
                        render(section),
                        0))
                .orElseGet(() -> failure(invocation,
                        "节不存在：" + sectionId + "。可用节：" + availableIds(invocation)));
    }

    private String render(DocSection section) {
        String header = (section.heading() != null ? "【" + section.heading() + "】" : "")
                + (section.page() != null ? "（第" + section.page() + "页）" : "");
        String text = section.text();
        if (text.length() <= MAX_RETURN_CHARS) {
            return header + "\n" + text;
        }
        return header + "\n" + text.substring(0, MAX_RETURN_CHARS)
                + "\n…（本节共 " + text.length() + " 字，已截断，可基于大纲定位相邻节继续阅读）";
    }

    private String availableIds(ToolInvocation invocation) {
        return documentStore.get(invocation.runId())
                .map(doc -> doc.sections().stream()
                        .map(DocSection::id)
                        .limit(20)
                        .collect(Collectors.joining(", ")))
                .orElse("(文档未加载)");
    }

    private ToolResult failure(ToolInvocation invocation, String message) {
        return ToolResult.failure(toolType(), toolName(),
                "sectionId=" + invocation.param(SECTION_ID), message, 0);
    }
}
