package com.gcll.docagent.parsing;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PDF 解析：每页一节（heading 为"第 N 页"，page 记录页码）。
 * 扫描版 PDF（无文本层）抛出明确的业务错误，提示用户换文本型文件。
 */
@Component
public class PdfDocumentParser implements DocumentParser {

    private static final Set<String> EXTENSIONS = Set.of(".pdf");
    private static final int PAGE_SECTION_MAX_CHARS = 6000;

    @Override
    public Set<String> supportedExtensions() {
        return EXTENSIONS;
    }

    @Override
    public ParsedDocument parse(InputStream input, String fileName) throws DocumentParseException {
        try (PDDocument doc = Loader.loadPDF(input.readAllBytes())) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                throw DocumentParser.fail("PDF 没有任何页面");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            AtomicInteger seq = new AtomicInteger();
            List<DocSection> sections = new ArrayList<>();
            int totalPagesWithText = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = normalize(stripper.getText(doc));
                if (pageText.isBlank()) {
                    continue;
                }
                totalPagesWithText++;
                String heading = "第 " + page + " 页";
                if (pageText.length() <= PAGE_SECTION_MAX_CHARS) {
                    sections.add(new DocSection(MarkdownDocumentParser.nextId(seq), heading, pageText, page));
                } else {
                    for (String chunk : splitChunks(pageText)) {
                        sections.add(new DocSection(MarkdownDocumentParser.nextId(seq), heading, chunk, page));
                    }
                }
            }
            if (totalPagesWithText == 0) {
                throw DocumentParser.fail("该 PDF 没有可提取的文本层（可能是扫描件/图片型 PDF），请上传文本型 PDF 或其他格式文件");
            }
            return new ParsedDocument(fileName, "pdf", sections);
        } catch (DocumentParseException de) {
            throw de;
        } catch (Exception ex) {
            throw new DocumentParseException("PDF 解析失败: " + ex.getMessage(), ex);
        }
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static List<String> splitChunks(String pageText) {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < pageText.length(); i += PAGE_SECTION_MAX_CHARS) {
            chunks.add(pageText.substring(i, Math.min(pageText.length(), i + PAGE_SECTION_MAX_CHARS)));
        }
        return chunks;
    }
}
