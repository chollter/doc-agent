package com.gcll.ticketagent.langchain4j;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.observability.trace.TraceRecorder;
import com.gcll.ticketagent.tool.ToolExecutionHolder;
import com.gcll.ticketagent.tool.ToolRegistry;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.ToolType;
import com.gcll.ticketagent.tool.react.ToolArgMerger;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LangChain4j ReAct 工具提供者——把现有 {@link com.gcll.ticketagent.tool.ToolGateway} 适配为 LangChain4j @Tool。
 *
 * <h3>设计：与 Spring AI 版 ReActToolAdapter 平行但独立</h3>
 * Spring AI 版的 ReActToolAdapter 用 {@code @Tool + ToolContext}，走 Spring AI 的工具调用协议；
 * 本类用 LangChain4j 的 {@code @Tool} 注解，走 LangChain4j AiService 的工具调用协议。
 * 两者底层都委托给 {@link ToolRegistry}，共享同一套工具实现，只是适配层不同。
 *
 * <h3>ToolContext 替代方案：ThreadLocal</h3>
 * LangChain4j 的 @Tool 方法不支持 ToolContext 注入，用 ThreadLocal 携带
 * extract + originalContent + TraceRecorder + parentStepId（由 ReActInvestigationStrategy
 * 在调用前 set、调用后 remove）。这比构造器注入更灵活——同一 Bean 实例在不同工单间切换上下文。
 *
 * <h3>步级 Trace</h3>
 * 每次 @Tool 方法被 LangChain4j AiService 调用时，在 delegate() 中记录一个子 Span：
 * <pre>
 *   REACT_TOOL_CALL: query_logs
 *     input:  system=payment-service, module=/pay/callback
 *     output: 证据摘要（指纹化，不存原文）
 *     costMs: 120ms
 * </pre>
 * TraceRecorder + parentStepId 也通过 ThreadLocal 传递（与 ToolContext 同生命周期）。
 */
@Component
public class ReActToolProvider {

    private static final Logger log = LoggerFactory.getLogger(ReActToolProvider.class);

    private final ToolRegistry toolRegistry;
    private final ToolArgMerger argMerger;

    /** ThreadLocal：携带当前工单的 extract + originalContent + TraceRecorder + parentStepId */
    private static final ThreadLocal<ToolContext> CONTEXT = new ThreadLocal<>();

    public ReActToolProvider(ToolRegistry toolRegistry, ToolArgMerger argMerger) {
        this.toolRegistry = toolRegistry;
        this.argMerger = argMerger;
    }

    /**
     * 设置当前线程的工具上下文（调用 ReActAssistant.investigate 之前 set）。
     *
     * @param extract         结构化抽取结果
     * @param originalContent 工单原文
     * @param tracer          Trace 记录器（用于步级 Trace）
     * @param parentStepId    父步骤 ID（REACT_LOOP 的 stepId）
     * @param runId           当前工单 runId（用于 DANGER 工具的 ToolConfirmGate）
     */
    public static void setContext(TicketExtractResult extract, String originalContent,
                                  TraceRecorder tracer, String parentStepId, String runId) {
        CONTEXT.set(new ToolContext(extract, originalContent, tracer, parentStepId));
        ToolExecutionHolder.setRunId(runId);
    }

    /**
     * 清除当前线程的工具上下文（调用 ReActAssistant.investigate 之后 remove）。
     */
    public static void clearContext() {
        CONTEXT.remove();
        ToolExecutionHolder.clear();
    }

    // --- LangChain4j @Tool 方法 ---

    @Tool("查询受影响系统/接口的实时运维日志，定位错误发生位置与堆栈。传入 system/module 缩小查询范围。")
    public String queryLogs(
            @dev.langchain4j.agent.tool.P("受影响的系统名（如 payment-service）") String system,
            @dev.langchain4j.agent.tool.P("受影响的模块或接口名（如 /pay/callback）") String module
    ) {
        return delegate("query_logs", argsMap(system, module));
    }

    @Tool("从历史案件知识库检索相似案例，提供可参考的根因与处置经验。")
    public String searchSimilarCases(
            @dev.langchain4j.agent.tool.P("工单原文关键词，用于语义检索") String query
    ) {
        return delegate("searchSimilarCases", Map.of());
    }

    @Tool("发送邮件通知值班人员，用于排查中需要人工介入时报警。传入 recipient 指定收件人，subject 和 body 描述通知内容。")
    public String notifyOncall(
            @dev.langchain4j.agent.tool.P("值班人邮箱地址，不传则用默认值班邮箱") String recipient,
            @dev.langchain4j.agent.tool.P("邮件主题") String subject,
            @dev.langchain4j.agent.tool.P("邮件正文") String body
    ) {
        return delegate("notifyOncall", Map.of());
    }

    @Tool("执行处置操作（如重启服务/清缓存/回滚版本）。高危操作，调用后会被拦截等待人工确认。传入 action 描述具体处置动作，target 指定目标系统。")
    public String executeRemediation(
            @dev.langchain4j.agent.tool.P("具体处置动作，如 restart-service/clear-cache/rollback") String action,
            @dev.langchain4j.agent.tool.P("目标系统或服务名") String target
    ) {
        return delegate("executeRemediation", Map.of());
    }

    // --- 内部方法 ---

    private String delegate(String toolName, Map<String, String> llmArgs) {
        ToolContext ctx = CONTEXT.get();
        if (ctx == null) {
            log.warn("LangChain4j tool [{}] called but ThreadLocal context missing", toolName);
            return "工具调用缺少必要上下文（extract/originalContent 未注入），无法执行。";
        }

        var toolOpt = toolRegistry.find(toolName);
        if (toolOpt.isEmpty()) {
            log.warn("LangChain4j tool [{}] not registered in ToolRegistry", toolName);
            return "工具 " + toolName + " 未注册，无法执行。";
        }

        // 步级 Trace：记录 Action（工具名 + 参数）
        TraceRecorder tracer = ctx.tracer;
        String parentStepId = ctx.parentStepId;
        String toolStepId = null;
        if (tracer != null && parentStepId != null) {
            toolStepId = tracer.begin("REACT_TOOL_CALL: " + toolName, parentStepId);
            tracer.recordMeta(toolStepId, false, toolName);
            tracer.recordInput(toolStepId, TraceRecorder.fingerprint("args", llmArgs.toString()));
        }

        try {
            TicketExtractResult mergedExtract = argMerger.merge(ctx.extract, llmArgs);
            long start = System.currentTimeMillis();
            ToolResult result = toolOpt.get().execute(mergedExtract, ctx.originalContent);
            long durationMs = System.currentTimeMillis() - start;

            String observation;
            if (result.success()) {
                observation = "【工具执行成功·" + durationMs + "ms】\n" + (result.output() == null ? "(无输出)" : result.output());
            } else {
                String err = result.errorMessage() == null ? "unknown error" : result.errorMessage();
                observation = "【工具执行失败·非工单证据】工具 " + toolName + " 调用失败：" + err
                        + "。此结果不可作为根因依据。";
            }

            // 步级 Trace：记录 Observation（指纹化摘要）
            if (toolStepId != null) {
                tracer.end(toolStepId, TraceRecorder.fingerprint("observation", observation), null);
            }

            return observation;
        } catch (Exception ex) {
            log.warn("LangChain4j tool [{}] delegate failed: {}", toolName, ex.getMessage());
            String errorResult = "【工具执行异常·非工单证据】" + ex.getClass().getSimpleName() + ": " + ex.getMessage()
                    + "。此结果不可作为根因依据。";
            if (toolStepId != null) {
                tracer.end(toolStepId, "exception", ex.getMessage());
            }
            return errorResult;
        }
    }

    private Map<String, String> argsMap(String system, String module) {
        Map<String, String> args = new LinkedHashMap<>();
        if (system != null && !system.isBlank()) {
            args.put(ToolArgMerger.PARAM_SYSTEM, system.trim());
        }
        if (module != null && !module.isBlank()) {
            args.put(ToolArgMerger.PARAM_MODULE, module.trim());
        }
        return args;
    }

    // --- 内部类型 ---

    /**
     * 工具上下文——通过 ThreadLocal 传递，包含工单信息和 Trace 记录器。
     *
     * @param extract         结构化抽取结果
     * @param originalContent 工单原文
     * @param tracer          Trace 记录器（用于步级 Trace，nullable——测试时可不传）
     * @param parentStepId    父步骤 ID（REACT_LOOP 的 stepId，nullable——测试时可不传）
     */
    record ToolContext(
            TicketExtractResult extract,
            String originalContent,
            TraceRecorder tracer,
            String parentStepId
    ) {}
}
