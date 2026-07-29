package com.gcll.ticketagent.api;

import com.gcll.ticketagent.api.dto.LogFileUploadResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/log-files")
public class LogFileController {

    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024;
    private static final int MAX_FILES = 10;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LogFileUploadResponse upload(@RequestPart("files") List<MultipartFile> files) throws IOException {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择日志文件");
        }
        if (files.size() > MAX_FILES) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "一次最多上传 " + MAX_FILES + " 个日志文件");
        }

        Path logRoot = Path.of(System.getProperty("opsmind.log.dir", "logs")).toAbsolutePath().normalize();
        Files.createDirectories(logRoot);

        List<String> saved = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            if (file.getSize() > MAX_FILE_SIZE) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "日志文件超过 2MB: " + file.getOriginalFilename());
            }
            String filename = sanitizeFilename(file.getOriginalFilename());
            Path target = logRoot.resolve(filename).normalize();
            if (!target.startsWith(logRoot)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "日志文件名非法: " + file.getOriginalFilename());
            }
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            saved.add(filename);
        }
        return new LogFileUploadResponse(saved.size(), saved);
    }

    private String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "日志文件名不能为空");
        }
        String filename = Path.of(original).getFileName().toString();
        if (!filename.matches("[a-zA-Z0-9._-]+\\.(log|txt)")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "日志文件名只能包含字母数字、点、下划线、连字符，并以 .log/.txt 结尾");
        }
        if (filename.toLowerCase().endsWith(".txt")) {
            filename = filename.substring(0, filename.length() - 4) + ".log";
        }
        return filename;
    }
}
