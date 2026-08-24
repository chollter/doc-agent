package com.gcll.docagent.parsing;

import java.io.InputStream;
import java.util.Set;

/**
 * 文档解析器 SPI。实现类注册为 Spring Bean，由 {@link DocumentParsingService} 按扩展名路由。
 */
public interface DocumentParser {

    /** 支持的扩展名（小写、含点，如 ".pdf"）。 */
    Set<String> supportedExtensions();

    /**
     * 解析文档为分节视图。
     *
     * @param input    原始字节流
     * @param fileName 原始文件名（扩展名路由已由外层完成）
     * @throws DocumentParseException 解析失败（含"扫描版 PDF 无文本层"等业务性错误）
     */
    ParsedDocument parse(InputStream input, String fileName) throws DocumentParseException;

    static DocumentParseException fail(String message) {
        return new DocumentParseException(message);
    }

    class DocumentParseException extends RuntimeException {
        public DocumentParseException(String message) {
            super(message);
        }

        public DocumentParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
