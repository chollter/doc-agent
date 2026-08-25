package com.gcll.docagent.tool.document;

import com.gcll.docagent.analysis.DocumentStore;
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.parsing.ParsedDocument;
import com.gcll.docagent.tool.ToolInvocation;
import com.gcll.docagent.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentToolsTest {

    private final DocumentStore store = new DocumentStore();
    private final GetDocumentOutlineTool outlineTool = new GetDocumentOutlineTool(store);
    private final ReadSectionTool readTool = new ReadSectionTool(store);
    private final SearchDocumentTool searchTool = new SearchDocumentTool(store);

    private final ParsedDocument doc = new ParsedDocument("demo.md", "markdown", List.of(
            new DocSection("sec-1", "项目背景", "本项目是一个文档分析 Agent，支持 ReAct 循环阅读。", null),
            new DocSection("sec-2", "技术方案", "采用 LangChain4j 驱动 ReAct，工具经 ToolRuntime 统一治理与审计。", null),
            new DocSection("sec-3", "风险", "LLM 输出可能不稳定，需要降级链路兜底。", null)
    ));

    DocumentToolsTest() {
        store.put("run-1", doc);
    }

    private ToolInvocation invocation(Map<String, String> params) {
        return new ToolInvocation("run-1", params, Map.of());
    }

    @Test
    void outlineListsAllSections() {
        ToolResult result = outlineTool.execute(invocation(Map.of()));
        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("sec-1 | 项目背景");
        assertThat(result.output()).contains("3 节");
    }

    @Test
    void readSectionReturnsTextAndRejectsUnknownId() {
        ToolResult ok = readTool.execute(invocation(Map.of("sectionId", "sec-2")));
        assertThat(ok.success()).isTrue();
        assertThat(ok.output()).contains("【技术方案】");
        assertThat(ok.output()).contains("LangChain4j");

        ToolResult miss = readTool.execute(invocation(Map.of("sectionId", "sec-99")));
        assertThat(miss.success()).isFalse();
        assertThat(miss.errorMessage()).contains("节不存在").contains("sec-1");
    }

    @Test
    void searchFindsKeywordAcrossSections() {
        ToolResult hit = searchTool.execute(invocation(Map.of("keyword", "ReAct")));
        assertThat(hit.success()).isTrue();
        assertThat(hit.output()).contains("sec-1").contains("sec-2");

        ToolResult none = searchTool.execute(invocation(Map.of("keyword", "不存在词")));
        assertThat(none.success()).isTrue();
        assertThat(none.output()).contains("未找到");
    }

    @Test
    void missingRunReturnsFailure() {
        ToolInvocation orphan = new ToolInvocation("run-x", Map.of(), Map.of());
        assertThat(outlineTool.execute(orphan).success()).isFalse();
        assertThat(readTool.execute(orphan).success()).isFalse();
    }
}
