package com.gcll.ticketagent.langchain4j;

/**
 * ReAct 上下文持有者——ThreadLocal 传递动态系统提示词。
 *
 * <p>LangChain4j AiService 的 systemMessageProvider 在调用时回调，
 * 但它只接收方法参数（无法直接传递工单上下文）。
 * 用 ThreadLocal 把系统提示词从 ReActInvestigationStrategy 传递到 AiServices 的 systemMessageProvider。
 *
 * <p>生命周期：调用 investigate 前 set，调用后 clear（防止内存泄漏）。
 */
public final class ReActContextHolder {

    private static final ThreadLocal<String> SYSTEM_PROMPT = new ThreadLocal<>();

    private ReActContextHolder() {}

    public static void setSystemPrompt(String prompt) {
        SYSTEM_PROMPT.set(prompt);
    }

    public static String getSystemPrompt() {
        return SYSTEM_PROMPT.get();
    }

    public static void clear() {
        SYSTEM_PROMPT.remove();
    }
}
