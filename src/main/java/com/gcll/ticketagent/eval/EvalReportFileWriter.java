package com.gcll.ticketagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class EvalReportFileWriter {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper objectMapper;
    private final Path defaultOutputDir;

    public EvalReportFileWriter(
            ObjectMapper objectMapper,
            @Value("${opsmind.eval.report-output-dir:data/eval/reports}") String defaultOutputDir
    ) {
        this.objectMapper = objectMapper;
        this.defaultOutputDir = Path.of(defaultOutputDir);
    }

    public EvalReport write(EvalReport report, String outputPath) {
        Path target = resolveTarget(report, outputPath);
        EvalReport reportWithFile = report.withOutputFile(target.toAbsolutePath().toString());
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), reportWithFile);
            return reportWithFile;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to write eval report to " + target.toAbsolutePath(), ex);
        }
    }

    private Path resolveTarget(EvalReport report, String outputPath) {
        if (outputPath != null && !outputPath.isBlank()) {
            return Path.of(outputPath);
        }
        String fileName = sanitize(report.suiteName()) + "-" + FILE_TIME.format(LocalDateTime.now()) + ".json";
        return defaultOutputDir.resolve(fileName);
    }

    private String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "eval";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]+", "-");
    }
}
