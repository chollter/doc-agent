package com.gcll.ticketagent.tool;

import java.util.Map;

/**
 * 单次工具调用的完整上下文——平台层传递给 {@link ToolGateway} 的统一入参。
 *
 * @param runId       当前 Agent 运行 ID（用于 DANGER 门控、审计日志）
 * @param parameters  调用方（LLM 或编排层）显式传入的参数，如 sectionId / keyword
 * @param attributes  环境上下文，由编排层注入（如文档句柄、原文摘要等），工具按需读取
 */
public record ToolInvocation(String runId, Map<String, String> parameters, Map<String, Object> attributes) {

    public String param(String name) {
        return parameters == null ? null : parameters.get(name);
    }

    @SuppressWarnings("unchecked")
    public <T> T attribute(String name) {
        return attributes == null ? null : (T) attributes.get(name);
    }
}
