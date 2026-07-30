package com.gcll.ticketagent.langchain4j;

import com.gcll.ticketagent.observability.trace.TraceRecorder;

/**
 * ReAct Trace 持有者——ThreadLocal 传递 TraceRecorder + parentStepId 给 AiService Listener。
 *
 * <p>LangChain4j 的 {@code AiServiceListener}（ToolExecutedEventListener / ResponseReceivedListener）
 * 在 ReAct 循环内部被回调，但 Listener 接口没有参数传递上下文。
 * 用 ThreadLocal 把 TraceRecorder 和 parentStepId 从 ReActInvestigationStrategy 传递到 Listener。
 *
 * <p>生命周期：调用 investigate 前 set，调用后 clear（防止内存泄漏）。
 * 与 {@link ReActContextHolder} 和 {@link ReActToolProvider#setContext} 同生命周期。
 */
public final class ReActTraceHolder {

    private static final ThreadLocal<TraceRecorder> TRACER = new ThreadLocal<>();
    private static final ThreadLocal<String> PARENT_STEP_ID = new ThreadLocal<>();

    private ReActTraceHolder() {}

    /**
     * 设置当前线程的 Trace 上下文（调用 ReActAssistant.investigate 之前 set）。
     *
     * @param tracer       Trace 记录器
     * @param parentStepId 父步骤 ID（REACT_LOOP 的 stepId）
     */
    public static void set(TraceRecorder tracer, String parentStepId) {
        TRACER.set(tracer);
        PARENT_STEP_ID.set(parentStepId);
    }

    public static TraceRecorder getTracer() {
        return TRACER.get();
    }

    public static String getParentStepId() {
        return PARENT_STEP_ID.get();
    }

    /**
     * 清除当前线程的 Trace 上下文（调用 ReActAssistant.investigate 之后 clear）。
     */
    public static void clear() {
        TRACER.remove();
        PARENT_STEP_ID.remove();
    }
}
