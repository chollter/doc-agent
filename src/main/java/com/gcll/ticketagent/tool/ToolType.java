package com.gcll.ticketagent.tool;

/**
 * 工具类型 + 权限分级。
 * <p>
 * 传输层类型（FUNCTION / MCP）区分工具调用机制；
 * 权限风险级别（READ / WRITE / DANGER）区分操作危害程度，决定可靠性策略：
 * <ul>
 *   <li>READ：只读操作（查日志/查指标/查案例），可安全重试</li>
 *   <li>WRITE：写入操作（创建工单/修改状态），不重试，但无需人工确认</li>
 *   <li>DANGER：高危操作（重启服务/删除数据），不重试，执行前需人工确认</li>
 * </ul>
 */
public enum ToolType {

    // --- 传输层类型 ---
    FUNCTION(ToolRiskLevel.READ),
    MCP(ToolRiskLevel.READ),

    // --- 新增：按风险级别直接声明的类型 ---
    READ_FUNCTION(ToolRiskLevel.READ),
    WRITE_FUNCTION(ToolRiskLevel.WRITE),
    DANGER_FUNCTION(ToolRiskLevel.DANGER),
    READ_MCP(ToolRiskLevel.READ),
    WRITE_MCP(ToolRiskLevel.WRITE),
    DANGER_MCP(ToolRiskLevel.DANGER);

    private final ToolRiskLevel riskLevel;

    ToolType(ToolRiskLevel riskLevel) {
        this.riskLevel = riskLevel;
    }

    public ToolRiskLevel riskLevel() {
        return riskLevel;
    }

    /**
     * 向后兼容：旧代码 FUNCTION/MCP 默认 READ 级别。
     * 新代码应使用带风险级别的类型。
     */
    public boolean isRetryable() {
        return riskLevel == ToolRiskLevel.READ;
    }

    public boolean requiresHumanConfirm() {
        return riskLevel == ToolRiskLevel.DANGER;
    }
}
