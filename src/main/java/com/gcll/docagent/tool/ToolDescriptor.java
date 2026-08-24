package com.gcll.docagent.tool;

public record ToolDescriptor(
        String name,
        ToolType type,
        String description,
        ToolRiskLevel riskLevel
) {
    /**
     * 向后兼容：旧代码不传 riskLevel 时，从 ToolType 推导。
     */
    public ToolDescriptor(String name, ToolType type, String description) {
        this(name, type, description, type.riskLevel());
    }
}
