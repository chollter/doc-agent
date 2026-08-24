package com.gcll.ticketagent.execution.evidence;

import com.gcll.ticketagent.execution.tool.ToolSelection;
import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.metrics.AgentMetrics;
import com.gcll.ticketagent.platform.tool.ToolExecutor;
import com.gcll.ticketagent.platform.tool.ToolRuntime;
import com.gcll.ticketagent.persistence.repository.ToolExecutionLogRepository;
import com.gcll.ticketagent.resilience.CallMetrics;
import com.gcll.ticketagent.resilience.CallRegistry;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolRegistry;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.react.ToolArgMerger;
import com.gcll.ticketagent.tool.ToolType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceCollectionServiceTest {

    private final ToolExecutionLogRepository logRepository = mock(ToolExecutionLogRepository.class);
    private final AgentMetrics agentMetrics = new AgentMetrics(new SimpleMeterRegistry());

    /** 空 CallRegistry（无 callMappings）→ 所有调用走 plain 路径（不治理），保留工具收集的测试语义。 */
    private static ExternalCallGateway plainGateway() {
        return new ExternalCallGateway(
                new CallRegistry(RetryRegistry.ofDefaults(),
                        TimeLimiterRegistry.ofDefaults(),
                        CircuitBreakerRegistry.ofDefaults(),
                        RateLimiterRegistry.ofDefaults()),
                new CallMetrics(new SimpleMeterRegistry()),
                Executors.newScheduledThreadPool(1));
    }

    @Test
    void collectsOnlySelectedTools() {
        ToolGateway tool1 = mock(ToolGateway.class);
        when(tool1.toolType()).thenReturn(ToolType.FUNCTION);
        when(tool1.toolName()).thenReturn("searchSimilarCases");
        when(tool1.execute(any(), anyString())).thenReturn(
                ToolResult.success(ToolType.FUNCTION, "searchSimilarCases", "input", "output", 50)
        );

        ToolGateway tool2 = mock(ToolGateway.class);
        when(tool2.toolType()).thenReturn(ToolType.MCP);
        when(tool2.toolName()).thenReturn("query_logs");
        when(tool2.execute(any(), anyString())).thenReturn(
                ToolResult.success(ToolType.MCP, "query_logs", "input", "log-output", 30)
        );

        ToolRegistry registry = new ToolRegistry(List.of(tool1, tool2));
        EvidenceCollectionService service = service(registry);

        TicketExtractResult extract = sampleExtract();
        ToolSelection selection = new ToolSelection(List.of("query_logs"), java.util.Map.of(), "test", false);

        List<ToolResult> results = service.collect("run-001", extract, "test content", selection);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).toolName()).isEqualTo("query_logs");
        verify(tool1, never()).execute(any(), anyString());
        verify(logRepository).save(eq("run-001"), eq("EVIDENCE_COLLECTION"), any(ToolResult.class));
    }

    @Test
    void degradesGracefullyOnToolFailure() {
        ToolGateway failingTool = mock(ToolGateway.class);
        when(failingTool.toolType()).thenReturn(ToolType.MCP);
        when(failingTool.toolName()).thenReturn("query_logs");
        when(failingTool.execute(any(), anyString())).thenReturn(
                ToolResult.failure(ToolType.MCP, "query_logs", "input", "connection refused", 10)
        );

        ToolRegistry registry = new ToolRegistry(List.of(failingTool));
        EvidenceCollectionService service = service(registry);

        List<ToolResult> results = service.collect(
                "run-002",
                sampleExtract(),
                "timeout issue",
                new ToolSelection(List.of("query_logs"), java.util.Map.of(), "test", false)
        );

        assertThat(results).hasSize(1);
        assertThat(results.get(0).success()).isFalse();
        assertThat(results.get(0).errorMessage()).isEqualTo("connection refused");
    }

    @Test
    void returnsEmptyWhenNoToolsSelected() {
        ToolRegistry registry = new ToolRegistry(List.of());
        EvidenceCollectionService service = service(registry);

        List<ToolResult> results = service.collect(
                "run-003",
                sampleExtract(),
                "minimal",
                new ToolSelection(List.of(), java.util.Map.of(), "none", false)
        );

        assertThat(results).isEmpty();
    }

    private EvidenceCollectionService service(ToolRegistry registry) {
        ExternalCallGateway gateway = plainGateway();
        ToolRuntime runtime = new ToolRuntime(
                registry,
                new ToolExecutor(gateway),
                new ToolArgMerger(),
                logRepository,
                agentMetrics
        );
        return new EvidenceCollectionService(runtime);
    }

    private TicketExtractResult sampleExtract() {
        return new TicketExtractResult(
                IssueType.INCIDENT, "payment", "callback", "/pay/callback",
                "500", "Internal Server Error", "production", "multiple users",
                "10:00-10:30", "payment failures", List.of("500", "callback"), 0.9
        );
    }
}
