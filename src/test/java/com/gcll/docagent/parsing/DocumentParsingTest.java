package com.gcll.docagent.parsing;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentParsingTest {

    @TempDir
    Path tempDir;

    private final MarkdownDocumentParser mdParser = new MarkdownDocumentParser();
    private final PlainTextDocumentParser txtParser = new PlainTextDocumentParser();
    private final PdfDocumentParser pdfParser = new PdfDocumentParser();
    private final DocxDocumentParser docxParser = new DocxDocumentParser();
    private final DocumentParsingService service = new DocumentParsingService(
            List.of(mdParser, txtParser, pdfParser, docxParser));

    @Test
    void markdownSplitsByHeadings() {
        String md = """
                # 项目背景

                这是项目背景介绍。

                ## 技术方案

                方案细节 A。

                ## 风险

                风险描述。
                """;
        ParsedDocument doc = mdParser.parse(stream(md), "demo.md");

        assertThat(doc.fileType()).isEqualTo("markdown");
        assertThat(doc.sections()).hasSize(3);
        assertThat(doc.sections().get(0).heading()).isEqualTo("项目背景");
        assertThat(doc.sections().get(1).id()).isEqualTo("sec-2");
        assertThat(doc.sections().get(2).heading()).isEqualTo("风险");
        assertThat(doc.fullText()).contains("方案细节 A");
        assertThat(doc.outline()).contains("sec-1 | 项目背景");
    }

    @Test
    void markdownMergesPrefaceBeforeFirstHeading() {
        String md = "开头没有标题的引言内容。\n\n# 第一节\n\n正文。";
        ParsedDocument doc = mdParser.parse(stream(md), "demo.md");

        assertThat(doc.sections()).hasSize(2);
        assertThat(doc.sections().get(0).heading()).isNull();
        assertThat(doc.sections().get(0).text()).contains("引言");
    }

    @Test
    void textChunksByBlankLines() {
        String txt = "第一段内容。\n\n第二段内容。\n\n第三段内容。";
        ParsedDocument doc = txtParser.parse(stream(txt), "demo.txt");

        assertThat(doc.sections()).isNotEmpty();
        assertThat(doc.totalChars()).isGreaterThan(0);
        assertThat(doc.sections().get(0).heading()).isEqualTo("第一段内容。");
    }

    @Test
    void pdfParsesPerPage() throws IOException {
        byte[] pdf = buildPdf(3);
        ParsedDocument doc = pdfParser.parse(new ByteArrayInputStream(pdf), "demo.pdf");

        assertThat(doc.fileType()).isEqualTo("pdf");
        assertThat(doc.sections()).hasSize(3);
        assertThat(doc.sections().get(0).page()).isEqualTo(1);
        assertThat(doc.sections().get(2).heading()).isEqualTo("第 3 页");
        assertThat(doc.sections().get(1).text()).contains("Page 2 content");
    }

    @Test
    void blankPdfRejectedAsScanned() throws IOException {
        byte[] pdf = buildBlankPdf();
        assertThatThrownBy(() -> pdfParser.parse(new ByteArrayInputStream(pdf), "scan.pdf"))
                .isInstanceOf(DocumentParser.DocumentParseException.class)
                .hasMessageContaining("文本层");
    }

    @Test
    void docxSplitsByHeadingStyle() throws IOException {
        byte[] docx = buildDocx(true);
        ParsedDocument doc = docxParser.parse(new ByteArrayInputStream(docx), "demo.docx");

        assertThat(doc.fileType()).isEqualTo("docx");
        assertThat(doc.sections()).hasSize(2);
        assertThat(doc.sections().get(0).heading()).isEqualTo("Summary");
        assertThat(doc.sections().get(0).text()).contains("Five years");
        assertThat(doc.sections().get(1).heading()).isEqualTo("Experience");
        assertThat(doc.sections().get(1).text()).contains("Backend engineer");
    }

    @Test
    void docxWithoutHeadingsStillChunks() throws IOException {
        byte[] docx = buildDocx(false);
        ParsedDocument doc = docxParser.parse(new ByteArrayInputStream(docx), "demo.docx");

        assertThat(doc.sections()).isNotEmpty();
        assertThat(doc.totalChars()).isGreaterThan(0);
    }

    @Test
    void serviceRoutesByExtensionAndRejectsUnknown() {
        ParsedDocument doc = service.parse("a.md", 100, stream("# T\n\ntext"));
        assertThat(doc.fileType()).isEqualTo("markdown");

        assertThatThrownBy(() -> service.parse("a.doc", 100, stream("x")))
                .isInstanceOf(com.gcll.docagent.api.BusinessException.class)
                .hasMessageContaining("不支持的文件类型");

        assertThatThrownBy(() -> service.parse("a.xls", 0, stream("x")))
                .isInstanceOf(com.gcll.docagent.api.BusinessException.class)
                .hasMessageContaining("不能为空");
    }

    // --- fixtures ---

    private static InputStream stream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    /** 生成一页但完全无文本的 PDF（模拟扫描件）。 */
    private static byte[] buildBlankPdf() throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage());
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 生成 N 页的英文 PDF（PDType1Font 不支持中文，用 ASCII 内容验证解析逻辑）。 */
    private static byte[] buildPdf(int pages) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText("Page " + i + " content with some sample text.");
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** 生成 DOCX：withHeadings=true 用 Heading1 样式分节，否则纯正文段落。 */
    private static byte[] buildDocx(boolean withHeadings) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (withHeadings) {
                heading(doc, "Summary");
                paragraph(doc, "Five years of backend development.");
                heading(doc, "Experience");
                paragraph(doc, "Backend engineer working on distributed systems.");
            } else {
                paragraph(doc, "Plain paragraph without heading styles, ".repeat(20));
            }
            doc.write(out);
            return out.toByteArray();
        }
    }

    private static void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading1");
        XWPFRun run = p.createRun();
        run.setText(text);
    }

    private static void paragraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
    }
}
