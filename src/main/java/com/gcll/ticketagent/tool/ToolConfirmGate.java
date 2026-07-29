package com.gcll.ticketagent.tool;

import com.gcll.ticketagent.human.PendingAction;
import com.gcll.ticketagent.human.PendingActionStatus;
import com.gcll.ticketagent.human.PendingActionType;
import com.gcll.ticketagent.persistence.repository.PendingActionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DANGER 级工具执行门控——拦截高危操作，等待人工确认后放行。
 * <p>
 * 流程：
 * <ol>
 *   <li>DANGER 工具调用 {@link #requestConfirm(ToolDescriptor, String, String)} → 写 pending_action + 暂停</li>
 *   <li>人工确认后 {@link HumanConfirmService#confirm} → pending_action 状态变 CONFIRMED</li>
 *   <li>定时轮询 {@link #pollConfirmed()} → 发现 CONFIRMED → 放行工具执行</li>
 * </ol>
 * <p>
 * 当前实现：DANGER 工具直接拦截，创建 pending_action，返回"等待确认"结果。
 * 调用方拿到 blocked 结果后可自行决定等待或跳过。
 */
@Component
public class ToolConfirmGate {

    private static final Logger log = LoggerFactory.getLogger(ToolConfirmGate.class);

    private final PendingActionRepository pendingActionRepository;

    /**
     * 等待确认的工具执行请求（runId+toolName → 确认状态）。
     * 轮询线程定期检查 pending_action 表，发现 CONFIRMED 后移除此 entry。
     */
    private final ConcurrentHashMap<String, ConfirmState> pendingRequests = new ConcurrentHashMap<>();

    public ToolConfirmGate(PendingActionRepository pendingActionRepository) {
        this.pendingActionRepository = pendingActionRepository;
    }

    /**
     * 请求人工确认。DANGER 级工具执行前调用。
     *
     * @param descriptor   工具描述（含 riskLevel）
     * @param runId        当前工单 ID
     * @param toolPayload  工具执行参数（存入 pending_action.reason 供人工审核）
     * @return ConfirmDecision：APPROVED（已确认放行）/ BLOCKED（等待确认）/ REJECTED（已拒绝）
     */
    public ConfirmDecision requestConfirm(ToolDescriptor descriptor, String runId, String toolPayload) {
        String actionId = UUID.randomUUID().toString();
        PendingAction action = new PendingAction(
                actionId,
                runId,
                PendingActionType.DANGER_TOOL_CONFIRM,
                toolPayload,
                "DANGER tool: " + descriptor.name() + " requires human confirmation"
        );
        pendingActionRepository.save(action);

        // 注册等待
        ConfirmState state = new ConfirmState(actionId, descriptor.name(), runId);
        pendingRequests.put(state.key(), state);

        log.warn("DANGER tool blocked, tool={}, runId={}, actionId={}", descriptor.name(), runId, actionId);
        return ConfirmDecision.blocked(actionId);
    }

    /**
     * 检查某个工具是否已获人工确认放行。
     * 调用方轮询此方法判断是否可继续执行。
     *
     * @param runId   工单 ID
     * @param toolName 工具名
     * @return CONFIRMED / REJECTED / PENDING
     */
    public ConfirmStatus checkStatus(String runId, String toolName) {
        String key = ConfirmState.key(runId, toolName);
        ConfirmState state = pendingRequests.get(key);
        if (state == null) {
            return ConfirmStatus.PENDING;
        }

        // 查库
        return pendingActionRepository.findById(state.actionId)
                .map(action -> {
                    if (action.getStatus() == PendingActionStatus.CONFIRMED) {
                        return ConfirmStatus.CONFIRMED;
                    } else if (action.getStatus() == PendingActionStatus.REJECTED) {
                        return ConfirmStatus.REJECTED;
                    }
                    return ConfirmStatus.PENDING;
                })
                .orElse(ConfirmStatus.PENDING);
    }

    /**
     * 定时清理已确认/已拒绝的 pending 请求，释放内存。
     */
    @Scheduled(fixedDelay = 60_000)
    public void cleanupResolved() {
        pendingRequests.entrySet().removeIf(entry -> {
            ConfirmStatus status = checkStatus(entry.getValue().runId, entry.getValue().toolName);
            return status == ConfirmStatus.CONFIRMED || status == ConfirmStatus.REJECTED;
        });
    }

    // --- 内部类型 ---

    record ConfirmState(String actionId, String toolName, String runId) {
        String key() {
            return key(runId, toolName);
        }

        static String key(String runId, String toolName) {
            return runId + ":" + toolName;
        }
    }

    public record ConfirmDecision(ConfirmStatus status, String actionId) {
        static ConfirmDecision blocked(String actionId) {
            return new ConfirmDecision(ConfirmStatus.BLOCKED, actionId);
        }

        static ConfirmDecision approved() {
            return new ConfirmDecision(ConfirmStatus.CONFIRMED, null);
        }

        static ConfirmDecision rejected() {
            return new ConfirmDecision(ConfirmStatus.REJECTED, null);
        }

        public boolean isBlocked() {
            return status == ConfirmStatus.BLOCKED;
        }
    }

    public enum ConfirmStatus {
        PENDING,
        BLOCKED,
        CONFIRMED,
        REJECTED
    }
}
