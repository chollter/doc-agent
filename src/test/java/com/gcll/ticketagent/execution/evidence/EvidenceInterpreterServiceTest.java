package com.gcll.ticketagent.execution.evidence;

import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.ToolType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceInterpreterServiceTest {

    private final EvidenceInterpreterService service = new EvidenceInterpreterService();

    @Test
    void interpretsLogTimeoutAndServerErrorSignals() {
        ToolResult logs = ToolResult.success(
                ToolType.MCP,
                "query_logs",
                "payment-service",
                "2026-07-19 ERROR /pay/callback HTTP 500 read timed out exception",
                120
        );

        EvidenceBundle bundle = service.interpret(List.of(logs));

        assertThat(bundle.logSignals()).anyMatch(signal -> signal.contains("超时"));
        assertThat(bundle.logSignals()).anyMatch(signal -> signal.contains("500"));
        assertThat(bundle.riskSignals()).contains("LOG_TIMEOUT", "LOG_ERROR");
    }

    @Test
    void interpretsMetricLatencyAndErrorRateSignals() {
        ToolResult metrics = ToolResult.success(
                ToolType.MCP,
                "query_metric",
                "payment-service",
                "p95 latency high, error rate 5xx increased, cpu usage normal",
                95
        );

        EvidenceBundle bundle = service.interpret(List.of(metrics));

        assertThat(bundle.metricSignals()).anyMatch(signal -> signal.contains("延迟异常"));
        assertThat(bundle.metricSignals()).anyMatch(signal -> signal.contains("错误率异常"));
        assertThat(bundle.riskSignals()).contains("METRIC_LATENCY_SPIKE", "METRIC_ERROR_RATE_SPIKE");
    }

    @Test
    void failedToolBecomesEvidenceGapNotRootCauseSignal() {
        ToolResult failed = ToolResult.failure(
                ToolType.MCP,
                "query_logs",
                "payment-service",
                "circuit open",
                10
        );

        EvidenceBundle bundle = service.interpret(List.of(failed));

        assertThat(bundle.logSignals()).isEmpty();
        assertThat(bundle.riskSignals()).isEmpty();
        assertThat(bundle.unknowns()).anyMatch(unknown -> unknown.contains("调用失败"));
    }
}
