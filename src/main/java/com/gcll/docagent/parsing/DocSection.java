package com.gcll.docagent.parsing;

/**
 * 文档分节——Agent 工具（read_section / search_document）与引用定位的最小单元。
 *
 * @param id      节 ID（如 sec-3），LLM 引用与前端定位的锚点
 * @param heading 标题（无标题的节为 null，如 TXT 块/PDF 页）
 * @param text    节正文（已归一化换行）
 * @param page    页码（PDF 每节一页；其余格式为 null）
 */
public record DocSection(String id, String heading, String text, Integer page) {

    public int charCount() {
        return text == null ? 0 : text.length();
    }

    /** 大纲条目：id + 标题（无标题时用正文首行截断）+ 字数。 */
    public String outlineEntry() {
        String title = heading != null ? heading
                : (text == null || text.isBlank() ? "(空)" : text.lines().findFirst().orElse("(空)"));
        if (title.length() > 50) {
            title = title.substring(0, 50) + "…";
        }
        return id + " | " + title + " | " + charCount() + "字" + (page != null ? " | 第" + page + "页" : "");
    }
}
