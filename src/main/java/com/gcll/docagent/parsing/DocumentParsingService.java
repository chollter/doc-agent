package com.gcll.docagent.parsing;

import com.gcll.docagent.api.BusinessException;
import com.gcll.docagent.api.ErrorCode;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 解析路由：按文件扩展名选择解析器；不支持的类型给出明确业务错误。
 */
@Service
public class DocumentParsingService {

    private static final int MAX_BYTES = 10 * 1024 * 1024;

    private final List<DocumentParser> parsers;

    public DocumentParsingService(List<DocumentParser> parsers) {
        this.parsers = parsers;
    }

    public ParsedDocument parse(String fileName, long size, InputStream input) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不能为空");
        }
        if (size <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        if (size > MAX_BYTES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能超过 10MB");
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        Optional<DocumentParser> parser = parsers.stream()
                .filter(p -> p.supportedExtensions().stream().anyMatch(lower::endsWith))
                .findFirst();
        if (parser.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "不支持的文件类型：仅支持 PDF / DOCX / MD / TXT");
        }
        try {
            return parser.get().parse(input, fileName);
        } catch (DocumentParser.DocumentParseException de) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, de.getMessage());
        }
    }
}
