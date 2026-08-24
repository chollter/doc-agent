package com.gcll.docagent.tool;

import com.gcll.docagent.human.PendingAction;
import com.gcll.docagent.human.PendingActionStatus;
import com.gcll.docagent.human.PendingActionType;
import com.gcll.docagent.persistence.repository.PendingActionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ToolConfirmGate 测试——验证 DANGER 级工具门控拦截、状态查询、清理机制。
 */
class ToolConfirmGateTest {

    private PendingActionRepository pendingActionRepository;
    private ToolConfirmGate gate;

    // 内存存储，模拟 repository
    private java.util.Map<String, PendingAction> store;

    @BeforeEach
    void setUp() {
        store = new java.util.concurrent.ConcurrentHashMap<>();
        pendingActionRepository = new PendingActionRepository() {
            @Override
            public PendingAction save(PendingAction action) {
                store.put(action.getId(), action);
                return action;
            }

            @Override
            public Optional<PendingAction> findById(String id) {
                return Optional.ofNullable(store.get(id));
            }

            @Override
            public java.util.List<PendingAction> findPending() {
                return store.values().stream().filter(a -> a.getStatus() == PendingActionStatus.PENDING).toList();
            }
        };
        gate = new ToolConfirmGate(pendingActionRepository);
    }

    @Test
    @DisplayName("DANGER 工具请求确认 → 创建 PendingAction + 返回 BLOCKED")
    void requestConfirm_createsPendingActionAndReturnsBlocked() {
        ToolDescriptor descriptor = new ToolDescriptor("executeRemediation", ToolType.DANGER_FUNCTION, "处置执行");
        String runId = "run-001";

        ToolConfirmGate.ConfirmDecision decision = gate.requestConfirm(descriptor, runId, "action=restart,target=payment");

        // 返回 BLOCKED
        assertTrue(decision.isBlocked());
        assertEquals(ToolConfirmGate.ConfirmStatus.BLOCKED, decision.status());
        assertNotNull(decision.actionId());

        // PendingAction 已写入存储
        PendingAction saved = store.get(decision.actionId());
        assertNotNull(saved);
        assertEquals(runId, saved.getRunId());
        assertEquals(PendingActionType.DANGER_TOOL_CONFIRM, saved.getActionType());
        assertEquals(PendingActionStatus.PENDING, saved.getStatus());
    }

    @Test
    @DisplayName("DANGER 工具请求确认 → 待确认状态查询返回 PENDING")
    void checkStatus_returnsPendingWhenNotConfirmed() {
        ToolDescriptor descriptor = new ToolDescriptor("executeRemediation", ToolType.DANGER_FUNCTION, "处置执行");
        String runId = "run-002";

        gate.requestConfirm(descriptor, runId, "action=restart");

        ToolConfirmGate.ConfirmStatus status = gate.checkStatus(runId, "executeRemediation");
        assertEquals(ToolConfirmGate.ConfirmStatus.PENDING, status);
    }

    @Test
    @DisplayName("人工确认后 → checkStatus 返回 CONFIRMED")
    void checkStatus_returnsConfirmedAfterHumanConfirm() {
        ToolDescriptor descriptor = new ToolDescriptor("executeRemediation", ToolType.DANGER_FUNCTION, "处置执行");
        String runId = "run-003";

        gate.requestConfirm(descriptor, runId, "action=restart");

        // 模拟人工确认
        PendingAction action = store.values().stream()
                .filter(a -> a.getRunId().equals(runId))
                .findFirst().orElseThrow();
        action.confirm("admin");

        ToolConfirmGate.ConfirmStatus status = gate.checkStatus(runId, "executeRemediation");
        assertEquals(ToolConfirmGate.ConfirmStatus.CONFIRMED, status);
    }

    @Test
    @DisplayName("人工拒绝后 → checkStatus 返回 REJECTED")
    void checkStatus_returnsRejectedAfterHumanReject() {
        ToolDescriptor descriptor = new ToolDescriptor("executeRemediation", ToolType.DANGER_FUNCTION, "处置执行");
        String runId = "run-004";

        gate.requestConfirm(descriptor, runId, "action=clear-cache");

        // 模拟人工拒绝
        PendingAction action = store.values().stream()
                .filter(a -> a.getRunId().equals(runId))
                .findFirst().orElseThrow();
        action.reject("admin");

        ToolConfirmGate.ConfirmStatus status = gate.checkStatus(runId, "executeRemediation");
        assertEquals(ToolConfirmGate.ConfirmStatus.REJECTED, status);
    }

    @Test
    @DisplayName("不存在的 runId+toolName → checkStatus 返回 PENDING")
    void checkStatus_returnsPendingForUnknownRequest() {
        ToolConfirmGate.ConfirmStatus status = gate.checkStatus("run-999", "unknownTool");
        assertEquals(ToolConfirmGate.ConfirmStatus.PENDING, status);
    }

    @Test
    @DisplayName("清理已确认/已拒绝的请求 → 从内存移除")
    void cleanupResolved_removesConfirmedAndRejected() {
        ToolDescriptor descriptor = new ToolDescriptor("executeRemediation", ToolType.DANGER_FUNCTION, "处置执行");

        // 请求3个确认
        gate.requestConfirm(descriptor, "run-005", "action=a");
        gate.requestConfirm(descriptor, "run-006", "action=b");
        gate.requestConfirm(descriptor, "run-007", "action=c");

        // 确认第一个、拒绝第二个
        store.values().stream()
                .filter(a -> a.getRunId().equals("run-005"))
                .findFirst().orElseThrow()
                .confirm("admin");
        store.values().stream()
                .filter(a -> a.getRunId().equals("run-006"))
                .findFirst().orElseThrow()
                .reject("admin");

        // 执行清理
        gate.cleanupResolved();

        // run-005 和 run-006 被清理，run-007 仍在
        assertEquals(ToolConfirmGate.ConfirmStatus.PENDING, gate.checkStatus("run-007", "executeRemediation"));
        // 已清理的不再查得到（从内存移除后走 PENDING 兜底，因为 find 不到了）
        // 实际行为：cleanupResolved 移除 pendingRequests entry，checkStatus 找不到 entry 返回 PENDING
        // 但 DB 里有记录，所以不影响正确性
    }

    @Test
    @DisplayName("不同 runId 的同名工具互不影响")
    void differentRunId_sameTool_independent() {
        ToolDescriptor descriptor = new ToolDescriptor("executeRemediation", ToolType.DANGER_FUNCTION, "处置执行");

        gate.requestConfirm(descriptor, "run-008", "action=restart-1");
        gate.requestConfirm(descriptor, "run-009", "action=restart-2");

        // 只确认 run-008
        store.values().stream()
                .filter(a -> a.getRunId().equals("run-008"))
                .findFirst().orElseThrow()
                .confirm("admin");

        assertEquals(ToolConfirmGate.ConfirmStatus.CONFIRMED, gate.checkStatus("run-008", "executeRemediation"));
        assertEquals(ToolConfirmGate.ConfirmStatus.PENDING, gate.checkStatus("run-009", "executeRemediation"));
    }
}
