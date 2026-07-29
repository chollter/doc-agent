package com.gcll.ticketagent.execution.evidence;

import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class EvidenceInterpreterService {

    public EvidenceBundle interpret(List<ToolResult> toolResults) {
        if (toolResults == null || toolResults.isEmpty()) {
            return new EvidenceBundle(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of("未执行日志/指标工具，缺少运行时证据"),
                    "无工具取证结果"
            );
        }

        List<String> logSignals = new ArrayList<>();
        List<String> metricSignals = new ArrayList<>();
        List<String> riskSignals = new ArrayList<>();
        List<String> unknowns = new ArrayList<>();

        for (ToolResult result : toolResults) {
            if (!result.success()) {
                unknowns.add(result.toolName() + " 调用失败，缺少该类证据: " + safe(result.errorMessage()));
                continue;
            }
            String output = safe(result.output());
            if (output.isBlank()) {
                unknowns.add(result.toolName() + " 调用成功但未返回有效数据");
                continue;
            }
            String normalized = output.toLowerCase(Locale.ROOT);
            if ("query_logs".equals(result.toolName())) {
                interpretLogs(output, normalized, logSignals, riskSignals);
            } else if ("query_metric".equals(result.toolName())) {
                interpretMetrics(output, normalized, metricSignals, riskSignals);
            }
        }

        if (logSignals.isEmpty() && metricSignals.isEmpty() && riskSignals.isEmpty()) {
            unknowns.add("工具返回数据中未识别到明确异常信号");
        }

        return new EvidenceBundle(
                List.copyOf(logSignals),
                List.copyOf(metricSignals),
                List.copyOf(riskSignals),
                List.copyOf(unknowns),
                buildSummary(logSignals, metricSignals, riskSignals, unknowns)
        );
    }

    private void interpretLogs(String output, String normalized, List<String> logSignals, List<String> riskSignals) {
        if (containsAny(normalized, "timeout", "timed out", "read timed out", "connect timed out")) {
            logSignals.add("日志出现超时信号: " + sample(output));
            riskSignals.add("LOG_TIMEOUT");
        }
        if (containsAny(normalized, "http 500", " status=500", " 500 ", "exception")) {
            logSignals.add("日志出现服务异常/500 信号: " + sample(output));
            riskSignals.add("LOG_ERROR");
        }
        if (containsAny(normalized, "outofmemory", "oom", "heap space", "oomkilled")) {
            logSignals.add("日志出现内存/OOM 信号: " + sample(output));
            riskSignals.add("LOG_OOM");
        }
        if (containsAny(normalized, "consumer lag", "dead letter", "dlq")) {
            logSignals.add("日志出现消息积压/死信信号: " + sample(output));
            riskSignals.add("LOG_MQ_BACKLOG");
        }
    }

    private void interpretMetrics(String output, String normalized, List<String> metricSignals, List<String> riskSignals) {
        if (containsAny(normalized, "latency high", "p95", "p99", "响应变慢", "延迟")) {
            metricSignals.add("指标显示延迟异常: " + sample(output));
            riskSignals.add("METRIC_LATENCY_SPIKE");
        }
        if (containsAny(normalized, "error rate", "5xx", "错误率", "失败率")) {
            metricSignals.add("指标显示错误率异常: " + sample(output));
            riskSignals.add("METRIC_ERROR_RATE_SPIKE");
        }
        if (containsAny(normalized, "cpu high", "cpu>", "cpu 使用率", "cpu usage")) {
            metricSignals.add("指标显示 CPU 压力异常: " + sample(output));
            riskSignals.add("METRIC_CPU_HIGH");
        }
        if (containsAny(normalized, "memory high", "内存", "heap", "oom")) {
            metricSignals.add("指标显示内存压力异常: " + sample(output));
            riskSignals.add("METRIC_MEMORY_HIGH");
        }
        if (containsAny(normalized, "connection pool", "hikari", "active connections", "连接池")) {
            metricSignals.add("指标显示连接池压力异常: " + sample(output));
            riskSignals.add("METRIC_CONNECTION_POOL");
        }
        if (containsAny(normalized, "consumer lag", "lag high", "积压")) {
            metricSignals.add("指标显示 MQ 积压异常: " + sample(output));
            riskSignals.add("METRIC_MQ_BACKLOG");
        }
    }

    private String buildSummary(List<String> logSignals, List<String> metricSignals,
                                List<String> riskSignals, List<String> unknowns) {
        return "logs=" + logSignals.size()
                + ",metrics=" + metricSignals.size()
                + ",risks=" + riskSignals
                + ",unknowns=" + unknowns;
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String sample(String value) {
        String singleLine = safe(value).replace('\n', ' ').trim();
        return singleLine.length() <= 180 ? singleLine : singleLine.substring(0, 180) + "...";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
