package com.gcll.docagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 评测报告存储——每次全量运行落盘为 JSON（data/eval-reports/），支持两次报告的用例级对比。
 * <p>这是"语料回归"的持久层：改动前后的两次报告对比 = 这次的修改让哪些用例变好/变坏，
 * 出现新增失败即回归信号——问题在到达用户之前被拦截。
 */
@Component
public class EvalReportStore {

    private static final Logger log = LoggerFactory.getLogger(EvalReportStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(java.time.ZoneId.systemDefault());

    private final ObjectMapper objectMapper;
    private final Path dir;

    public EvalReportStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.dir = Path.of("data", "eval-reports");
    }

    /** 报告落盘，返回报告 ID（文件名去扩展名）。 */
    public String save(EvalRunner.EvalReport report) {
        try {
            Files.createDirectories(dir);
            String id = "eval-" + TS.format(Instant.now());
            Files.writeString(dir.resolve(id + ".json"), objectMapper.writeValueAsString(report));
            return id;
        } catch (IOException ex) {
            log.warn("Failed to persist eval report: {}", ex.getMessage());
            return null;
        }
    }

    /** 稳定性报告落盘（stability- 前缀，与 golden 报告同目录、list 可见）。 */
    public String saveStability(StabilityMeasurer.StabilityReport report) {
        try {
            Files.createDirectories(dir);
            String id = "stability-" + TS.format(Instant.now());
            Files.writeString(dir.resolve(id + ".json"), objectMapper.writeValueAsString(report));
            return id;
        } catch (IOException ex) {
            log.warn("Failed to persist stability report: {}", ex.getMessage());
            return null;
        }
    }

    public List<String> list() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString().replaceFirst("\\.json$", ""))
                    .sorted().toList();
        } catch (IOException ex) {
            return List.of();
        }
    }

    /** 两次报告的用例级对比：状态翻转（通过↔失败）逐条列出。 */
    public CompareResult compare(String olderId, String newerId) throws IOException {
        EvalRunner.EvalReport older = read(olderId);
        EvalRunner.EvalReport newer = read(newerId);
        Map<String, EvalRunner.CaseResult> oldCases = older.cases().stream()
                .collect(Collectors.toMap(EvalRunner.CaseResult::name, Function.identity()));
        List<CaseDelta> deltas = new ArrayList<>();
        for (EvalRunner.CaseResult n : newer.cases()) {
            EvalRunner.CaseResult o = oldCases.get(n.name());
            if (o == null) {
                deltas.add(new CaseDelta(n.name(), null, n.pass(), n.pass() ? null : n.failures()));
            } else if (o.pass() != n.pass()) {
                deltas.add(new CaseDelta(n.name(), o.pass(), n.pass(), n.failures()));
            }
        }
        return new CompareResult(olderId, newerId,
                older.passed(), newer.passed(), older.total(), newer.total(), deltas);
    }

    private EvalRunner.EvalReport read(String id) throws IOException {
        if (id == null || !id.matches("[\\w.-]+")) {
            throw new IOException("非法报告 ID: " + id);
        }
        Path file = dir.resolve(id + ".json");
        if (!Files.exists(file)) {
            throw new IOException("报告不存在: " + id);
        }
        return objectMapper.readValue(file.toFile(), EvalRunner.EvalReport.class);
    }

    /** 单用例前后状态。olderPass=null 表示旧报告没有该用例（新增）。 */
    public record CaseDelta(String name, Boolean olderPass, boolean newerPass, List<String> failures) {
    }

    public record CompareResult(String olderId, String newerId,
                                int olderPassed, int newerPassed, int olderTotal, int newerTotal,
                                List<CaseDelta> deltas) {
    }
}
