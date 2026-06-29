package com.example.opsmcp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.opsmcp.entity.OpsLogSampleEntity;
import com.example.opsmcp.entity.OpsMetricSampleEntity;
import com.example.opsmcp.mapper.OpsLogSampleMapper;
import com.example.opsmcp.mapper.OpsMetricSampleMapper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 运维日志/指标查询服务（从 tech-support-agent 主项目迁移而来）。
 * <p>供 MCP tool 调用，背后查 PostgreSQL 的 ops_log_sample / ops_metric_sample 表。
 * 查询逻辑与原 {@code MyBatisOpsSampleRepository} 保持一致，保证迁移后行为不变。
 */
@Service
public class OpsQueryService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final OpsLogSampleMapper logMapper;
    private final OpsMetricSampleMapper metricMapper;

    public OpsQueryService(OpsLogSampleMapper logMapper, OpsMetricSampleMapper metricMapper) {
        this.logMapper = logMapper;
        this.metricMapper = metricMapper;
    }

    /**
     * 查询日志，返回格式化后的文本（供 MCP tool 直接回传给 LLM）。
     */
    public String queryLogs(String systemName, String moduleName, String query, int limit) {
        LambdaQueryWrapper<OpsLogSampleEntity> wrapper = new LambdaQueryWrapper<>();
        if (hasText(systemName)) {
            // systemName 变体展开：工单抽取可能是 "Payment service"(空格)，
            // 库里可能是 "payment-service"(连字符) 或 "支付系统"(中文)，需多变体 OR 匹配
            applySystemMatchLog(wrapper, systemName);
        }
        if (hasText(moduleName)) {
            applySystemMatchLog(wrapper, moduleName);
        }
        applyLogKeyword(wrapper, query);
        wrapper.orderByDesc(OpsLogSampleEntity::getOccurredAt).last("LIMIT " + Math.max(1, limit));
        List<OpsLogSampleEntity> logs = logMapper.selectList(wrapper);
        return logs.isEmpty() ? "no log evidence found" : formatLogs(logs);
    }

    /**
     * 查询指标，返回格式化后的文本（供 MCP tool 直接回传给 LLM）。
     */
    public String queryMetrics(String systemName, String moduleName, String query, int limit) {
        LambdaQueryWrapper<OpsMetricSampleEntity> wrapper = new LambdaQueryWrapper<>();
        if (hasText(systemName)) {
            applySystemMatchMetric(wrapper, systemName);
        }
        applyMetricKeyword(wrapper, firstText(moduleName, query));
        wrapper.orderByDesc(OpsMetricSampleEntity::getOccurredAt).last("LIMIT " + Math.max(1, limit));
        List<OpsMetricSampleEntity> metrics = metricMapper.selectList(wrapper);
        return metrics.isEmpty() ? "no metric evidence found" : formatMetrics(metrics);
    }

    /**
     * 日志表的 system/service 宽松匹配：对查询词做变体展开（空格/连字符/下划线互换），
     * OR 匹配 system_name 和 service_name，解决 "Payment service" vs "payment-service" 不命中问题。
     */
    private void applySystemMatchLog(LambdaQueryWrapper<OpsLogSampleEntity> wrapper, String keyword) {
        List<String> variants = nameVariants(keyword);
        wrapper.and(w -> {
            boolean first = true;
            for (String v : variants) {
                if (first) {
                    w.like(OpsLogSampleEntity::getSystemName, v)
                            .or().like(OpsLogSampleEntity::getServiceName, v);
                    first = false;
                } else {
                    w.or().like(OpsLogSampleEntity::getSystemName, v)
                            .or().like(OpsLogSampleEntity::getServiceName, v);
                }
            }
        });
    }

    /** 指标表的 system/service 宽松匹配（同 applySystemMatchLog）。 */
    private void applySystemMatchMetric(LambdaQueryWrapper<OpsMetricSampleEntity> wrapper, String keyword) {
        List<String> variants = nameVariants(keyword);
        wrapper.and(w -> {
            boolean first = true;
            for (String v : variants) {
                if (first) {
                    w.like(OpsMetricSampleEntity::getSystemName, v)
                            .or().like(OpsMetricSampleEntity::getServiceName, v);
                    first = false;
                } else {
                    w.or().like(OpsMetricSampleEntity::getSystemName, v)
                            .or().like(OpsMetricSampleEntity::getServiceName, v);
                }
            }
        });
    }

    /**
     * 生成服务名变体：归一化分隔符（空格/连字符/下划线互换）+ 原值。
     * 如 "Payment service" → ["Payment service", "payment-service", "payment_service", "payment service"]
     * 让 SQL LIKE 能命中不同分隔符写法的 service 名。
     */
    private List<String> nameVariants(String name) {
        if (!hasText(name)) {
            return List.of();
        }
        String trimmed = name.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        // 用正则把空格/连字符/下划线统一替换，生成三种分隔符变体
        String noSep = lower.replaceAll("[\\s\\-_]+", " ");        // 空格
        String dashSep = lower.replaceAll("[\\s\\-_]+", "-");      // 连字符
        String underscoreSep = lower.replaceAll("[\\s\\-_]+", "_"); // 下划线
        // 去重保留：原值 + 三种变体
        return java.util.stream.Stream.of(trimmed, lower, noSep, dashSep, underscoreSep)
                .filter(s -> hasText(s) && s.length() >= 2)
                .distinct()
                .toList();
    }

    private void applyLogKeyword(LambdaQueryWrapper<OpsLogSampleEntity> wrapper, String query) {
        String keyword = normalizeKeyword(query);
        if (!hasText(keyword)) {
            return;
        }
        wrapper.and(w -> w.like(OpsLogSampleEntity::getMessage, keyword)
                .or()
                .like(OpsLogSampleEntity::getTags, keyword)
                .or()
                .like(OpsLogSampleEntity::getTraceId, keyword));
    }

    private void applyMetricKeyword(LambdaQueryWrapper<OpsMetricSampleEntity> wrapper, String query) {
        String keyword = normalizeKeyword(query);
        if (!hasText(keyword)) {
            return;
        }
        wrapper.and(w -> w.like(OpsMetricSampleEntity::getMetricName, keyword)
                .or()
                .like(OpsMetricSampleEntity::getLabels, keyword)
                .or()
                .like(OpsMetricSampleEntity::getStatus, keyword)
                .or()
                .like(OpsMetricSampleEntity::getServiceName, keyword));
    }

    private String normalizeKeyword(String query) {
        if (!hasText(query)) {
            return null;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        if (lower.contains("oom") || lower.contains("memory") || lower.contains("heap") || query.contains("内存")) {
            return "memory";
        }
        if (lower.contains("timeout") || lower.contains("connection") || query.contains("连接池")) {
            return "connection";
        }
        if (lower.contains("500") || query.contains("回调")) {
            return "callback";
        }
        if (query.contains("结算") || query.contains("幂等")) {
            return "settlement";
        }
        return query.length() > 32 ? query.substring(0, 32) : query;
    }

    private String firstText(String first, String second) {
        return hasText(first) ? first : second;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String formatLogs(List<OpsLogSampleEntity> logs) {
        StringBuilder sb = new StringBuilder();
        for (OpsLogSampleEntity log : logs) {
            sb.append('[').append(FORMATTER.format(log.getOccurredAt())).append("] ")
                    .append(log.getLevel()).append(' ')
                    .append(log.getSystemName()).append('/')
                    .append(log.getServiceName()).append(' ')
                    .append("traceId=").append(log.getTraceId()).append(' ')
                    .append(log.getMessage()).append('\n');
        }
        return sb.toString().trim();
    }

    private String formatMetrics(List<OpsMetricSampleEntity> metrics) {
        StringBuilder sb = new StringBuilder();
        for (OpsMetricSampleEntity metric : metrics) {
            sb.append('[').append(FORMATTER.format(metric.getOccurredAt())).append("] ")
                    .append(metric.getSystemName()).append('/')
                    .append(metric.getServiceName()).append(' ')
                    .append(metric.getMetricName()).append('=')
                    .append(metric.getMetricValue())
                    .append(metric.getUnit() == null ? "" : metric.getUnit())
                    .append(" status=").append(metric.getStatus())
                    .append(" labels=").append(metric.getLabels())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    // ==================== 日志文件查看（view_logs）====================

    /** 允许查询的日志根目录（白名单，防任意文件读取）。通过配置可覆盖。 */
    private static final String DEFAULT_LOG_DIR = System.getProperty("user.dir") + "/logs";
    private static final int MAX_OUTPUT_CHARS = 4000;  // 输出截断阈值，防撑爆 LLM 上下文

    /**
     * 查看服务的日志文件：按关键字 grep 过滤 + tail 截断行数。
     *
     * <p>模拟真实运维排障——日志在文件里，通过 grep/tail 命令检索。
     * 安全：①服务名只允许字母数字连字符（防路径穿越）②路径必须在日志根目录下（白名单）
     *      ③命令用固定模板拼接，不执行 LLM 传的任意命令 ④输出超长截断。
     *
     * @param serviceName 服务名（对应 logs/ 下的 .log 文件名，如 payment-service）
     * @param keyword     过滤关键字（如 OOM、OutOfMemoryError）；为空则取最近 N 行
     * @param tailLines   返回最近多少行（默认 50）
     * @return 日志文本；文件不存在/路径越界返回提示
     */
    public String viewLogFiles(String serviceName, String keyword, int tailLines) {
        // 1. 服务名白名单校验：只允许字母/数字/连字符（防路径穿越 ../）
        if (!hasText(serviceName) || !serviceName.matches("[a-zA-Z0-9._-]+")) {
            return "无效的服务名（仅允许字母数字连字符）：" + serviceName;
        }
        int maxLines = Math.max(1, Math.min(tailLines <= 0 ? 50 : tailLines, 200));  // 上限200行

        // 2. 定位日志文件（必须在允许的目录下）
        String logDir = System.getProperty("opsmind.log.dir", DEFAULT_LOG_DIR);
        java.nio.file.Path logFile = java.nio.file.Paths.get(logDir, serviceName + ".log").toAbsolutePath().normalize();
        java.nio.file.Path allowedRoot = java.nio.file.Paths.get(logDir).toAbsolutePath().normalize();
        if (!logFile.startsWith(allowedRoot)) {
            return "拒绝访问：路径越界，仅允许查询日志目录";
        }
        if (!java.nio.file.Files.exists(logFile)) {
            // 模糊匹配：服务名变体（payment-service → payment_service 等）
            java.nio.file.Path matched = findLogVariants(allowedRoot, serviceName);
            if (matched == null) {
                return "未找到服务 " + serviceName + " 的日志文件（在 " + logDir + " 下）";
            }
            logFile = matched;
        }

        // 3. 执行命令：有关键字 → grep 过滤后 tail；无关键字 → tail 最近 N 行
        String cmd = buildViewCommand(logFile, keyword, maxLines);
        try {
            ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "查询超时（10s），日志文件可能过大，建议缩小关键字范围";
            }

            // 4. 输出截断：超 MAX_OUTPUT_CHARS 字符时保留首尾
            if (output.length() > MAX_OUTPUT_CHARS) {
                output = truncateOutput(output);
            }
            return output.isBlank() ? "未匹配到关键字 [" + keyword + "] 的日志" : output;
        } catch (Exception ex) {
            return "查询日志失败：" + ex.getClass().getSimpleName() + " " + ex.getMessage()
                    + "（提示：当前环境可能不支持 shell 命令，Windows 需用 findstr）";
        }
    }

    /** 拼接查询命令（固定模板，不执行任意命令）。 */
    private String buildViewCommand(java.nio.file.Path logFile, String keyword, int maxLines) {
        String escapedPath = escapeShell(logFile.toString());
        if (hasText(keyword)) {
            String escapedKeyword = escapeShell(keyword);
            // grep 过滤 + tail 截断：先 grep 出含关键字的行，再取最近 maxLines 行
            return "grep -i " + escapedKeyword + " " + escapedPath + " | tail -" + maxLines;
        }
        // 无关键字：直接取最近 maxLines 行
        return "tail -" + maxLines + " " + escapedPath;
    }

    /** shell 转义：只允许字母数字和少量安全字符，其余用单引号包裹防注入。 */
    private String escapeShell(String input) {
        if (input.matches("[a-zA-Z0-9_./=-]+")) {
            return input;
        }
        // 用单引号包裹，内部单引号用 '"'"' 转义
        return "'" + input.replace("'", "'\"'\"'") + "'";
    }

    /** 模糊匹配服务名变体（payment-service → payment_service / payment_service.log）。 */
    private java.nio.file.Path findLogVariants(java.nio.file.Path dir, String serviceName) {
        for (String variant : nameVariants(serviceName)) {
            java.nio.file.Path candidate = dir.resolve(variant + ".log").normalize();
            if (java.nio.file.Files.exists(candidate) && candidate.startsWith(dir)) {
                return candidate;
            }
        }
        return null;
    }

    /** 输出截断：超长时保留头部+尾部，中间折叠。 */
    private String truncateOutput(String output) {
        int keep = MAX_OUTPUT_CHARS / 2;
        return output.substring(0, keep)
                + "\n...[已截断，原 " + output.length() + " 字符]...\n"
                + output.substring(output.length() - keep);
    }
}
