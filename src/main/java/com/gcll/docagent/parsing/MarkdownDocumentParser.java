package com.gcll.docagent.parsing;

import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Markdown 解析：按 ATX 标题（# ~ ######）分节；首个标题之前的内容作为"文档开头"节。
 * 超长节（> 6000 字）按空行边界拆分为多个子节，保证 read_section 粒度可用。
 */
@Component
public class MarkdownDocumentParser implements DocumentParser {

    private static final int MAX_SECTION_CHARS = 6000;
    private static final Set<String> EXTENSIONS = Set.of(".md", ".markdown");

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(InputStream input, String fileName) throws DocumentParseException {
        String text = readText(input);
        List<DocSection> sections = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger();

        String heading = null;
        StringBuilder buffer = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("#")) {
                flush(sections, seq, heading, buffer);
                heading = line.replaceFirst("^#+\\s*", "").trim();
            } else {
                buffer.append(line).append('\n');
            }
        }
        flush(sections, seq, heading, buffer);
        if (sections.isEmpty()) {
            throw DocumentParser.fail("文档内容为空");
        }
        return new ParsedDocument(fileName, "markdown", sections);
    }

    /** 把缓冲落成一节；超长节按空行边界拆分（子节标题加 (续1)/(续2) 后缀）。 */
    private void flush(List<DocSection> sections, AtomicInteger seq, String heading, StringBuilder buffer) {
        String body = buffer.toString().trim();
        buffer.setLength(0);
        if (body.isEmpty() && heading == null) {
            return;
        }
        if (body.length() <= MAX_SECTION_CHARS) {
            sections.add(new DocSection(nextId(seq), heading, body, null));
            return;
        }
        int continuation = 0;
        for (String chunk : splitByBlankLine(body)) {
            if (chunk.isEmpty()) continue;
            String h = heading == null ? null
                    : (continuation == 0 ? heading : heading + "（续" + continuation + "）");
            sections.add(new DocSection(nextId(seq), h, chunk, null));
            continuation++;
        }
    }

    private static List<String> splitByBlankLine(String body) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String para : body.split("\n\\s*\n")) {
            if (cur.length() + para.length() > MAX_SECTION_CHARS && cur.length() > 0) {
                parts.add(cur.toString().trim());
                cur.setLength(0);
            }
            cur.append(para).append("\n\n");
        }
        if (cur.length() > 0) {
            parts.add(cur.toString().trim());
        }
        return parts;
    }

    static String readText(InputStream input) throws DocumentParseException {
        try {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("\r\n", "\n").replace('\r', '\n').trim();
        } catch (Exception ex) {
            throw new DocumentParseException("读取文件失败: " + ex.getMessage(), ex);
        }
    }

    static String nextId(AtomicInteger seq) {
        return "sec-" + seq.incrementAndGet();
    }
}
