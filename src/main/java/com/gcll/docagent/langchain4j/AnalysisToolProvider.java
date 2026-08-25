package com.gcll.docagent.langchain4j;

import com.gcll.docagent.observability.trace.TraceRecorder;
import com.gcll.docagent.platform.tool.ToolCallRequest;
import com.gcll.docagent.platform.tool.ToolExecutionContext;
import com.gcll.docagent.platform.tool.ToolRuntime;
import com.gcll.docagent.tool.ToolResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LangChain4j @Tool 方法集——把文档分析工具暴露给 ReAct 循环。
 * <p>每次调用经 {@link ToolRuntime} 统一执行（治理包裹 + tool_execution_log 落库 + 指标），
 * 并在其下记录步级 Trace（REACT_TOOL_CALL: 工具名 + 参数 + 返回摘要），
 * 与 {@code LangChain4jConfig} 的两个 Listener 互补形成完整审计。
 */
@Component
public class AnalysisToolProvider {

    private static final Logger log = LoggerFactory.getLogger(AnalysisToolProvider.class);

    private final ToolRuntime toolRuntime;

    public AnalysisToolProvider(ToolRuntime toolRuntime) {
        this.toolRuntime = toolRuntime;
    }

    @Tool("获取文档大纲：每节一行的结构清单（节ID | 标题 | 字数 | 页码）。分析开始时先调用它了解文档全貌，再按需阅读具体节。")
    public String getDocumentOutline() {
        return delegate("get_document_outline", Map.of());
    }

    @Tool("按节ID阅读文档正文。sectionId 来自 get_document_outline 返回的节ID（如 sec-1）。")
    public String readSection(
            @P("节ID，如 sec-1") String sectionId
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sectionId", sectionId);
        return delegate("read_section", params);
    }

    @Tool("在全文中按关键词检索，返回命中节ID和上下文片段。用于快速定位与要求相关的内容。")
    public String searchDocument(
            @P("检索关键词") String keyword
    ) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("keyword", keyword);
        return delegate("search_document", params);
    }

    private String delegate(String toolName, Map<String, String> params) {
        TraceRecorder tracer = ReActContextHolder.getTracer();
        String parentStepId = ReActContextHolder.getParentStepId();
        String runId = com.gcll.docagent.tool.ToolExecutionHolder.getRunId();

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
