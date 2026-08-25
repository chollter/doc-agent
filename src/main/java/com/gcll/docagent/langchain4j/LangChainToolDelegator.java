package com.gcll.docagent.langchain4j;

import com.gcll.docagent.observability.trace.TraceRecorder;
import com.gcll.docagent.platform.tool.ToolCallRequest;
import com.gcll.docagent.platform.tool.ToolExecutionContext;
import com.gcll.docagent.platform.tool.ToolRuntime;
import com.gcll.docagent.tool.ToolExecutionHolder;
import com.gcll.docagent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * LangChain4j @Tool 方法的共享委派器——所有单工具 Provider 都经此执行：
 * ToolRuntime 统一治理（Resilience4j 包裹 + tool_execution_log 落库 + 指标）
 * 并记录步级 Trace（REACT_TOOL_CALL: 工具名 + 参数 + 返回摘要）。
 */
@Component
public class LangChainToolDelegator {

    private static final Logger log = LoggerFactory.getLogger(LangChainToolDelegator.class);

    private final ToolRuntime toolRuntime;

    public LangChainToolDelegator(ToolRuntime toolRuntime) {
        this.toolRuntime = toolRuntime;
    }

    public String delegate(String toolName, Map<String, String> params) {
        TraceRecorder tracer = ReActContextHolder.getTracer();
        String parentStepId = ReActContextHolder.getParentStepId();
        String runId = ToolExecutionHolder.getRunId();

        String toolStepId = null;
        if (tracer != null && parentStepId != null) {
            toolStepId = tracer.begin("REACT_TOOL_CALL: " + toolName, parentStepId);
            tracer.recordMeta(toolStepId, false, toolName);
            tracer.recordInput(toolStepId, TraceRecorder.fingerprint("args", params.toString()));
        }

        try {
            List<ToolResult> results = toolRuntime.executeBatch(
                    ToolExecutionContext.of(runId, "REACT_ANALYZE"),
                    List.of(new ToolCallRequest(toolName, params)));
            String observation;
            if (results.isEmpty()) {
                observation = "工具 " + toolName + " 未注册，无法执行。";
            } else {
                ToolResult result = results.get(0);
                observation = result.success()
                        ? "【工具返回】\n" + result.output()
                        : "【工具失败】" + result.errorMessage() + "（此结果不可作为分析依据）";
            }
            if (toolStepId != null) {
                tracer.end(toolStepId, TraceRecorder.fingerprint("observation", observation), null);
            }
            return observation;
        } catch (Exception ex) {
            log.warn("LangChain4j tool [{}] delegate failed: {}", toolName, ex.getMessage());
            if (toolStepId != null) {
                tracer.end(toolStepId, "exception", ex.getMessage());
            }
            return "【工具异常】" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + "（此结果不可作为分析依据）";
        }
    }
}
