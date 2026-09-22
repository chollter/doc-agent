package com.gcll.docagent.analysis;

/**
 * 分析运行模式。三种模式共享同一份结果协议，但调用策略和统计口径隔离。
 */
public enum AnalysisExecutionMode {
    /** 前端联调/演示：使用预置结果，不消耗 LLM token。 */
    MOCK,
    /** 真实分析：调用配置的 LLM 和分析流水线。 */
    REAL,
    /** 自动化评测：使用 Fake/Replay LLM，保证可重复且不消耗线上 token。 */
    EVAL;

    public static AnalysisExecutionMode parse(String value, AnalysisExecutionMode fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
