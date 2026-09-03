package com.gcll.docagent.api;

import com.gcll.docagent.api.dto.HumanActionDto;
import com.gcll.docagent.human.PendingAction;
import com.gcll.docagent.persistence.repository.PendingActionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 人工确认闭环（HITL）——DANGER 工具被 ToolConfirmGate 拦截后，
 * 前端经此查询待确认动作并决策；确认/拒绝结果由工具侧轮询感知后放行或跳过。
 */
@RestController
@RequestMapping("/api/human")
public class HumanConfirmController {

    private final PendingActionRepository pendingActionRepository;

    public HumanConfirmController(PendingActionRepository pendingActionRepository) {
        this.pendingActionRepository = pendingActionRepository;
    }

    /** 待确认动作列表；带 runId 参数时只返回该 run 的。 */
    @GetMapping("/pending")
    public List<HumanActionDto> pending(@RequestParam(required = false) String runId) {
        return pendingActionRepository.findPending().stream()
                .filter(a -> runId == null || runId.isBlank() || a.getRunId().equals(runId))
                .map(HumanConfirmController::toDto)
                .toList();
    }

    @PostMapping("/actions/{id}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable String id) {
        PendingAction action = require(id);
        action.confirm("demo-user");
        pendingActionRepository.save(action);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/actions/{id}/reject")
    public ResponseEntity<Void> reject(@PathVariable String id) {
        PendingAction action = require(id);
        action.reject("demo-user");
        pendingActionRepository.save(action);
        return ResponseEntity.ok().build();
    }

    private PendingAction require(String id) {
        return pendingActionRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_NOT_FOUND, "待确认动作不存在: " + id));
    }

    private static HumanActionDto toDto(PendingAction action) {
        return new HumanActionDto(
                action.getId(),
                action.getRunId(),
                action.getActionType().name(),
                action.getStatus().name(),
                action.getPayload(),
                action.getReason(),
                action.getCreatedAt());
    }
}
