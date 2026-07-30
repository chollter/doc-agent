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
            } else if ("notifyOncall".equals(result.toolName())) {
                interpretNotification(output, normalized, riskSignals);
            } else if ("executeRemediation".equals(result.toolName())) {
                interpretRemediation(output, normalized, riskSignals);
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

    private void interpretNotification(String output, String normalized, List<String> riskSignals) {
        if (containsAny(normalized, "sent", "已发送", "通知成功")) {
            riskSignals.add("NOTIFY_SENT");
        }
        if (containsAny(normalized, "failed", "发送失败")) {
            riskSignals.add("NOTIFY_FAILED");
        }
    }

    private void interpretRemediation(String output, String normalized, List<String> riskSignals) {
        if (containsAny(normalized, "blocked", "等待确认", "pending")) {
            riskSignals.add("REMEDIATION_BLOCKED");
        }
        if (containsAny(normalized, "confirmed", "已确认", "执行成功")) {
            riskSignals.add("REMEDIATION_EXECUTED");
        }
        if (containsAny(normalized, "rejected", "已拒绝")) {
            riskSignals.add("REMEDIATION_REJECTED");
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
