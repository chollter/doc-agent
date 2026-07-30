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
    void interpretsNotificationSignals() {
        ToolResult notification = ToolResult.success(
                ToolType.WRITE_FUNCTION,
                "notifyOncall",
                "oncall@example.com",
                "邮件已发送给值班人 oncall@example.com",
                95
        );

        EvidenceBundle bundle = service.interpret(List.of(notification));

        assertThat(bundle.riskSignals()).contains("NOTIFY_SENT");
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
