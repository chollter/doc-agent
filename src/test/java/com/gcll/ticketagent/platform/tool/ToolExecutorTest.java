package com.gcll.ticketagent.platform.tool;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.resilience.CallMetrics;
import com.gcll.ticketagent.resilience.CallRegistry;
import com.gcll.ticketagent.resilience.ExternalCallGateway;
import com.gcll.ticketagent.tool.ToolExecutionHolder;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.ToolType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ToolExecutorTest {

    @Test
    void exposesRunIdDuringToolExecutionAndClearsItAfterwards() {
        ToolExecutor executor = new ToolExecutor(plainGateway());

        ToolResult result = executor.execute(new RunIdEchoTool(), null, "content", "run-123");

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("run-123");
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
        public ToolResult execute(TicketExtractResult extract, String originalContent) {
            return ToolResult.success(toolType(), toolName(), originalContent, ToolExecutionHolder.getRunId(), 1);
        }
    }
}
