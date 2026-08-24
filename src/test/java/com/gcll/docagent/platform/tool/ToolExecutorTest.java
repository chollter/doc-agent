package com.gcll.docagent.platform.tool;

import com.gcll.docagent.resilience.CallMetrics;
import com.gcll.docagent.resilience.CallRegistry;
import com.gcll.docagent.resilience.ExternalCallGateway;
import com.gcll.docagent.tool.ToolExecutionHolder;
import com.gcll.docagent.tool.ToolGateway;
import com.gcll.docagent.tool.ToolInvocation;
import com.gcll.docagent.tool.ToolResult;
import com.gcll.docagent.tool.ToolType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutorTest {

    @Test
    void exposesRunIdDuringToolExecutionAndClearsItAfterwards() {
        ToolExecutor executor = new ToolExecutor(plainGateway());

        ToolInvocation invocation = new ToolInvocation("run-123", Map.of("k", "echo"), Map.of());
        ToolResult result = executor.execute(new RunIdEchoTool(), invocation);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("run-123:echo");
        assertThat(ToolExecutionHolder.getRunId()).isNull();
    }

    private static ExternalCallGateway plainGateway() {
        return new ExternalCallGateway(
                new CallRegistry(RetryRegistry.ofDefaults(),
                        TimeLimiterRegistry.ofDefaults(),
                        CircuitBreakerRegistry.ofDefaults(),
                        RateLimiterRegistry.ofDefaults()),
                new CallMetrics(new SimpleMeterRegistry()),
                Executors.newScheduledThreadPool(1));
    }

    private static class RunIdEchoTool implements ToolGateway {

        @Override
        public ToolType toolType() {
            return ToolType.FUNCTION;
        }

        @Override
        public String toolName() {
            return "runIdEcho";
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            return ToolResult.success(toolType(), toolName(),
                    String.valueOf(invocation.parameters()),
                    invocation.runId() + ":" + invocation.param("k"), 1);
        }
    }
}
