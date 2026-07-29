package com.gcll.ticketagent.resilience;

/**
 * 日志脱敏工具：防止 API Key / Token 等敏感信息进入日志。
 */
public final class LogSanitizer {

    private static final String[] SENSITIVE_PATTERNS = {
            "sk-[a-zA-Z0-9]+",          // DashScope API Key 格式
            "Bearer [a-zA-Z0-9\\-_.]+",  // Authorization header
    };

    private LogSanitizer() {}

    /**
     * 脱敏字符串：将可能的 API Key / Token 替换为 ***。
     */
    public static String sanitize(String input) {
        if (input == null) return null;
        String result = input;
        for (String pattern : SENSITIVE_PATTERNS) {
            result = result.replaceAll(pattern, "***");
        }
        return result;
    }
}
