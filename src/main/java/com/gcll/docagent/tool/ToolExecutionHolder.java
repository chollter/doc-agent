package com.gcll.docagent.tool;

/**
 * 工具执行上下文持有器——通过 ThreadLocal 携带当前工单的 runId。
 *
 * <p>ReAct 工具适配层（ReActToolProvider / ReActToolAdapter）在调用 @Tool 方法前 set runId，
 * DANGER 级工具（如 ExecuteRemediationTool）在 execute() 中读取 runId，
 * 用于创建 PendingAction 并请求人工确认。
 *
 * <p>设计原因：{@link ToolGateway#execute(TicketExtractResult, String)} 签名不含 runId，
 * 避免为单个 DANGER 工具修改全接口签名。ThreadLocal 生命周期由调用方管理（set/clear 配对）。
 */
public final class ToolExecutionHolder {

    private static final ThreadLocal<String> RUN_ID = new ThreadLocal<>();

    private ToolExecutionHolder() {
    }

    public static void setRunId(String runId) {
        RUN_ID.set(runId);
    }

    public static String getRunId() {
        return RUN_ID.get();
    }

    public static void clear() {
        RUN_ID.remove();
    }
}
