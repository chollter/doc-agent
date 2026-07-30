package com.gcll.ticketagent.tool.function;

import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.tool.ToolConfirmGate;
import com.gcll.ticketagent.tool.ToolDescriptor;
import com.gcll.ticketagent.tool.ToolExecutionHolder;
import com.gcll.ticketagent.tool.ToolGateway;
import com.gcll.ticketagent.tool.ToolResult;
import com.gcll.ticketagent.tool.ToolType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 处置执行工具（DANGER 级）——高危操作，执行前必须经人工确认。
 *
 * <p>场景：排查确认根因后，Agent 建议执行处置（如重启服务、清缓存、回滚版本）。
 * 此工具<b>不会直接执行</b>——它会：
 * <ol>
 *   <li>调用 {@link ToolConfirmGate#requestConfirm} 创建 PendingAction，写入 pending_action 表</li>
 *   <li>返回"等待人工确认"结果给 Agent，Agent 拿到 blocked 结果后继续输出结论</li>
 *   <li>人工确认后（PendingActionStatus.CONFIRMED），由独立调度逻辑放行执行</li>
 * </ol>
 *
 * <p>治理：
 * <ul>
 *   <li>风险级别：DANGER——不重试，执行前需人工确认</li>
 *   <li>人工确认门控：{@link ToolConfirmGate} 拦截，写 pending_action，等待人工确认后放行</li>
 *   <li>审计：ToolResult 记录请求的处置动作+目标，供 Trace 追溯</li>
 * </ul>
 *
 * <p>runId 通过 {@link ToolExecutionHolder} ThreadLocal 传入（调用方在执行前 set），
 * 因为 {@link ToolGateway#execute} 签名不含 runId，避免为单个工具修改全接口。
 */
@Component
public class ExecuteRemediationTool implements ToolGateway {

    private static final Logger log = LoggerFactory.getLogger(ExecuteRemediationTool.class);
    private static final String TOOL_NAME = "executeRemediation";

    private final ToolConfirmGate confirmGate;

    public ExecuteRemediationTool(ToolConfirmGate confirmGate) {
        this.confirmGate = confirmGate;
    }

    @Override
    public ToolType toolType() {
        return ToolType.DANGER_FUNCTION;
    }

    @Override
    public String toolName() {
        return TOOL_NAME;
    }

    @Override
    public ToolResult execute(TicketExtractResult extract, String originalContent) {
        long start = System.currentTimeMillis();

        String runId = ToolExecutionHolder.getRunId();
        if (runId == null) {
            log.error("ExecuteRemediation called but runId missing in ThreadLocal");
            return ToolResult.failure(ToolType.DANGER_FUNCTION, TOOL_NAME, "runId=missing",
                    "无法获取当前工单 runId，处置请求被拒绝", System.currentTimeMillis() - start);
        }

        String action = extract.errorMessage() != null ? extract.errorMessage() : "未指定具体处置动作";
        String target = extract.affectedSystem() != null ? extract.affectedSystem() : "未指定目标系统";
        String payload = "action=" + action + ",target=" + target + ",runId=" + runId;
        String input = "runId=" + runId + ",action=" + action + ",target=" + target;

        // 请求人工确认——ToolConfirmGate 会创建 PendingAction 并返回 blocked
        ToolConfirmGate.ConfirmDecision decision = confirmGate.requestConfirm(
                this.descriptor(), runId, payload);

        if (decision.isBlocked()) {
            String output = "处置操作已被拦截，等待人工确认。actionId=" + decision.actionId()
                    + "，处置动作=" + action + "，目标=" + target;
            log.warn("DANGER tool blocked, tool={}, runId={}, actionId={}", TOOL_NAME, runId, decision.actionId());
            // 返回成功——工具执行本身没失败，是按设计被门控拦截
            return ToolResult.success(ToolType.DANGER_FUNCTION, TOOL_NAME, input, output,
                    System.currentTimeMillis() - start);
        } else {
            // 不应该走到这里——当前设计 DANGER 工具总是被 blocked
            String output = "处置操作已获确认放行，执行处置: action=" + action + ",target=" + target;
            log.info("DANGER tool approved, tool={}, runId={}", TOOL_NAME, runId);
            return ToolResult.success(ToolType.DANGER_FUNCTION, TOOL_NAME, input, output,
                    System.currentTimeMillis() - start);
        }
    }
}
