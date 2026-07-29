package com.gcll.ticketagent.tool;

import com.gcll.ticketagent.extract.TicketExtractResult;

import java.util.List;

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

    ToolResult execute(TicketExtractResult extract, String originalContent);

    default ToolDescriptor descriptor() {
        return new ToolDescriptor(toolName(), toolType(), toolName(), riskLevel());
    }
}
