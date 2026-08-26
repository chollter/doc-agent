package com.gcll.docagent.loop;

/**
 * 循环预算——轮次/工具调用/token 三重硬顶。
 * <p>这是自研循环相对框架 AiService 的核心增益之一：失控的代价有上界，
 * 超限不是异常而是可预期的停止原因（stopReason），随后走降级链。
 */
public record LoopBudget(int maxRounds, int maxToolCalls, long maxTotalTokens) {

    /** 返回超限原因；未超限返回 null。 */
    public String violation(LoopState state) {
        if (state.round() >= maxRounds) {
            return "BUDGET_ROUNDS";
        }
        if (state.toolCalls() >= maxToolCalls) {
            return "BUDGET_TOOL_CALLS";
        }
        if (state.tokensUsed() >= maxTotalTokens) {
            return "BUDGET_TOKENS";
        }
        return null;
    }
}
