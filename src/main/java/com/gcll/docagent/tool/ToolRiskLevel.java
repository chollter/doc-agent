package com.gcll.docagent.tool;

/**
 * 工具操作的风险级别。
 * <p>
 * 决定可靠性策略：
 * <ul>
 *   <li>READ：可安全重试（查日志/查指标/查案例）</li>
 *   <li>WRITE：不重试，无需人工确认（创建工单/修改状态）</li>
 *   <li>DANGER：不重试，执行前需人工确认（重启服务/删除数据）</li>
 * </ul>
 */
public enum ToolRiskLevel {
    READ,
    WRITE,
    DANGER
}
