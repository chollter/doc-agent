package com.gcll.docagent.parsing;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 纯文本解析：按空行分段，合并相邻段为约 1200 字的节（无标题结构，heading 为段首行摘要）。
 */
@Component
public class PlainTextDocumentParser implements DocumentParser {

    private static final int TARGET_SECTION_CHARS = 1200;
    private static final Set<String> EXTENSIONS = Set.of(".txt");

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(InputStream input, String fileName) throws DocumentParseException {
        String text = MarkdownDocumentParser.readText(input);
        AtomicInteger seq = new AtomicInteger();
        List<DocSection> sections = new ArrayList<>();

        StringBuilder buffer = new StringBuilder();
        for (String para : text.split("\n\\s*\n")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            if (buffer.length() + p.length() > TARGET_SECTION_CHARS && buffer.length() > 0) {
                sections.add(toSection(seq, buffer.toString().trim()));
                buffer.setLength(0);
            }
            buffer.append(p).append("\n\n");
        }
        if (buffer.length() > 0) {
            sections.add(toSection(seq, buffer.toString().trim()));
        }
        if (sections.isEmpty()) {
            throw DocumentParser.fail("文档内容为空");
        }
        return new ParsedDocument(fileName, "txt", sections);
    }

    private DocSection toSection(AtomicInteger seq, String body) {
        String firstLine = body.lines().findFirst().orElse("");
        String heading = firstLine.length() > 30 ? firstLine.substring(0, 30) + "…" : firstLine;
        return new DocSection(MarkdownDocumentParser.nextId(seq), heading, body, null);
    }
}
