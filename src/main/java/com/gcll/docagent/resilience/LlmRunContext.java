package com.gcll.docagent.resilience;

public final class LlmRunContext {

    private static final ThreadLocal<String> CURRENT_RUN_ID = new ThreadLocal<>();

    private LlmRunContext() {
    }

    public static void bind(String runId) {
        CURRENT_RUN_ID.set(runId);
    }

    public static String currentRunId() {
        return CURRENT_RUN_ID.get();
    }

    public static void clear() {
        CURRENT_RUN_ID.remove();
    }
}
