package com.gcll.docagent.tool;

/**
 * 工具 SPI——所有 Agent 工具实现此接口并注册为 Spring Bean，
 * 由 {@link ToolRegistry} 自动发现。
 */
public interface ToolGateway {

    ToolType toolType();

    String toolName();

    /**
     * 工具风险级别，默认从 ToolType 推导。
     * 新工具声明 WRITE/DANGER 级别时覆盖此方法即可。
     */
    default ToolRiskLevel riskLevel() {
        return toolType().riskLevel();
    }

    ToolResult execute(ToolInvocation invocation);

    default ToolDescriptor descriptor() {
        return new ToolDescriptor(toolName(), toolType(), toolName(), riskLevel());
    }
}
