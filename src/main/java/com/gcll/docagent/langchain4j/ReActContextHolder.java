package com.gcll.docagent.langchain4j;

import com.gcll.docagent.observability.trace.TraceRecorder;

/**
 * ReAct 循环的 ThreadLocal 上下文：系统提示词 + Trace 记录器。
 * <p>LangChain4j 的 @Tool 方法不支持上下文注入，AiService 又是单例 Bean，
 * 因此每次调用前由编排层 set、调用后 clear（同一线程内生效）。
 */
public final class ReActContextHolder {

    private static final ThreadLocal<Context> CONTEXT = new ThreadLocal<>();

    private ReActContextHolder() {
    }

    public static void set(String systemPrompt, TraceRecorder tracer, String parentStepId) {
        CONTEXT.set(new Context(systemPrompt, tracer, parentStepId));
    }

    public static void clear() {
        CONTEXT.remove();
    }

    public static String getSystemPrompt() {
        Context ctx = CONTEXT.get();
        return ctx == null ? null : ctx.systemPrompt();
    }

    public static TraceRecorder getTracer() {
        Context ctx = CONTEXT.get();
        return ctx == null ? null : ctx.tracer();
    }

    public static String getParentStepId() {
        Context ctx = CONTEXT.get();
        return ctx == null ? null : ctx.parentStepId();
    }

    record Context(String systemPrompt, TraceRecorder tracer, String parentStepId) {
    }
}
