package com.gcll.docagent.parsing;

import java.util.List;
import java.util.Optional;

/**
 * 解析后的文档——统一的分节视图，与格式无关。
 */
public record ParsedDocument(String fileName, String fileType, List<DocSection> sections) {

    public int totalChars() {
        return sections.stream().mapToInt(DocSection::charCount).sum();
    }

    public Optional<DocSection> findSection(String id) {
        return sections.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** 全文（节之间以空行分隔）；超大文档由调用方按上下文窗口截断。 */
    public String fullText() {
        StringBuilder sb = new StringBuilder();
        for (DocSection s : sections) {
            if (s.heading() != null) {
                sb.append("## ").append(s.heading()).append('\n');
            }
            sb.append(s.text()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** 大纲（每节一行），供 get_document_outline 工具与前端目录面板使用。 */
    public String outline() {
        StringBuilder sb = new StringBuilder();
        sb.append("文档：").append(fileName).append("（").append(sections.size()).append(" 节，共 ")
          .append(totalChars()).append(" 字）\n");
        for (DocSection s : sections) {
            sb.append(s.outlineEntry()).append('\n');
        }
        return sb.toString().trim();
    }
}
