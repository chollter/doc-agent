package com.gcll.docagent.langchain4j;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 文档类 @Tool Provider 集——每个类只暴露一个工具方法，
 * 供 {@link SkillAssistantFactory} 按技能的 toolNames 挑选装配。
 * 全部经 {@link LangChainToolDelegator} 委派执行（治理 + 审计 + 步级 Trace）。
 */
public final class DocumentToolProviders {

    private DocumentToolProviders() {
    }

    @Component
    public static class Outline {
        private final LangChainToolDelegator delegator;

        public Outline(LangChainToolDelegator delegator) {
            this.delegator = delegator;
        }

        @Tool("获取文档大纲：每节一行的结构清单（节ID | 标题 | 字数 | 页码）。分析开始时先调用它了解文档全貌，再按需阅读具体节。")
        public String getDocumentOutline() {
            return delegator.delegate("get_document_outline", Map.of());
        }
    }

    @Component
    public static class ReadSection {
        private final LangChainToolDelegator delegator;

        public ReadSection(LangChainToolDelegator delegator) {
            this.delegator = delegator;
        }

        @Tool("按节ID阅读文档正文。sectionId 来自 get_document_outline 返回的节ID（如 sec-1）。")
        public String readSection(@P("节ID，如 sec-1") String sectionId) {
            return delegator.delegate("read_section", Map.of("sectionId", sectionId));
        }
    }

    @Component
    public static class Search {
        private final LangChainToolDelegator delegator;

        public Search(LangChainToolDelegator delegator) {
            this.delegator = delegator;
        }

        @Tool("在全文中按关键词检索，返回命中节ID和上下文片段。用于快速定位与要求相关的内容。")
        public String searchDocument(@P("检索关键词") String keyword) {
            return delegator.delegate("search_document", Map.of("keyword", keyword));
        }
    }

    @Component
    public static class ExportReport {
        private final LangChainToolDelegator delegator;

        public ExportReport(LangChainToolDelegator delegator) {
            this.delegator = delegator;
        }

        @Tool("将最终分析报告导出为 Markdown 文件。高危操作：调用后会被拦截，等待人工确认才真正写入。分析完成后的最后一步调用它，传入 filename（不含路径）和 content（完整 Markdown 报告）。")
        public String exportReport(
                @P("导出文件名，如 report.md，不含路径") String filename,
                @P("完整报告内容（Markdown）") String content) {
            return delegator.delegate("export_report", Map.of("filename", filename, "content", content));
        }
    }
}
