package com.gcll.docagent.parsing;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DOCX 解析：按标题样式（Heading1-9 / 中文 Word 的数字样式 ID）分节；
 * 无样式标题的文档退化为按空段分块（约 1500 字/节），保证仍可分节阅读。
 */
@Component
public class DocxDocumentParser implements DocumentParser {

    private static final int TARGET_SECTION_CHARS = 1500;
    private static final Set<String> EXTENSIONS = Set.of(".docx");

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(InputStream input, String fileName) throws DocumentParseException {
        try (XWPFDocument doc = new XWPFDocument(input)) {
            AtomicInteger seq = new AtomicInteger();
            List<DocSection> sections = new ArrayList<>();

            String heading = null;
            StringBuilder buffer = new StringBuilder();
            boolean sawHeading = false;

            for (XWPFParagraph p : doc.getParagraphs()) {
                String text = normalize(p.getText());
                if (text.isBlank()) {
                    buffer.append('\n');
                    continue;
                }
                String title = headingText(p, text);
                if (title != null) {
                    flush(sections, seq, heading, buffer);
                    heading = title;
                    sawHeading = true;
                } else {
                    buffer.append(text).append('\n');
                }
            }
            flush(sections, seq, heading, buffer);

            if (sections.isEmpty()) {
                throw DocumentParser.fail("文档内容为空（可能正文在表格/文本框中，暂不支持）");
            }
            // 全文无任何标题样式时，重按长度分块，避免单节过大
            if (!sawHeading && sections.size() == 1 && sections.get(0).charCount() > TARGET_SECTION_CHARS * 2) {
                return rechunk(fileName, sections.get(0).text(), seq);
            }
            return new ParsedDocument(fileName, "docx", sections);
        } catch (DocumentParseException de) {
            throw de;
        } catch (Exception ex) {
            throw new DocumentParseException("DOCX 解析失败: " + ex.getMessage(), ex);
        }
    }

    /** 判定段落是否为标题：样式 ID 含 "heading"（英文 Word）或 1-9 数字样式且文本较短（中文 Word）。 */
    private String headingText(XWPFParagraph p, String text) {
        String styleId = p.getStyleID();
        if (styleId == null) {
            return null;
        }
        String lower = styleId.toLowerCase();
        if (lower.contains("heading") || lower.contains("标题")) {
            return text.length() > 80 ? text.substring(0, 80) + "…" : text;
        }
        // 中文 Word 的内置标题样式 ID 是 "1"~"9"；短文本才算标题，防把长正文误判进来
        if (styleId.matches("^[1-9]$") && text.length() <= 40) {
            return text;
        }
        return null;
    }

    private void flush(List<DocSection> sections, AtomicInteger seq, String heading, StringBuilder buffer) {
        String body = buffer.toString().trim();
        buffer.setLength(0);
        if (body.isEmpty() && heading == null) {
            return;
        }
        if (body.length() <= TARGET_SECTION_CHARS * 2) {
            sections.add(new DocSection(MarkdownDocumentParser.nextId(seq), heading, body, null));
            return;
        }
        int continuation = 0;
        for (String chunk : splitByLength(body)) {
            String h = heading == null ? null
                    : (continuation == 0 ? heading : heading + "（续" + continuation + "）");
            sections.add(new DocSection(MarkdownDocumentParser.nextId(seq), h, chunk, null));
            continuation++;
        }
    }

    private static List<String> splitByLength(String body) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < body.length(); i += TARGET_SECTION_CHARS) {
            parts.add(body.substring(i, Math.min(body.length(), i + TARGET_SECTION_CHARS)));
        }
        return parts;
    }

    private ParsedDocument rechunk(String fileName, String text, AtomicInteger seq) {
        List<DocSection> sections = new ArrayList<>();
        for (String chunk : splitByLength(text)) {
            String firstLine = chunk.lines().findFirst().orElse("");
            String heading = firstLine.length() > 30 ? firstLine.substring(0, 30) + "…" : firstLine;
            sections.add(new DocSection(MarkdownDocumentParser.nextId(seq), heading, chunk, null));
        }
        return new ParsedDocument(fileName, "docx", sections);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }
}
