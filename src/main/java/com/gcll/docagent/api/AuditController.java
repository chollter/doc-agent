package com.gcll.docagent.api;

import com.gcll.docagent.api.dto.LlmRunStatsDto;
import com.gcll.docagent.api.dto.AgentStepAuditDto;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentStep;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import com.gcll.docagent.resilience.LlmRunStatsRecorder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {
    private final AgentRunRepository agentRunRepository;
    private final LlmRunStatsRecorder llmRunStatsRecorder;

    public AuditController(AgentRunRepository agentRunRepository, LlmRunStatsRecorder llmRunStatsRecorder) {
        this.agentRunRepository = agentRunRepository;
        this.llmRunStatsRecorder = llmRunStatsRecorder;
    }

    @GetMapping("/agent-runs/{runId}")
    public List<AgentStepAuditDto> agentRunAudit(@PathVariable String runId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found: " + runId));
        return run.getSteps().stream().map(this::toDto).toList();
    }

    @GetMapping("/agent-runs/{runId}/llm-stats")
    public LlmRunStatsDto agentRunLlmStats(@PathVariable String runId) {
        agentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "Agent run not found: " + runId));
        return llmRunStatsRecorder.snapshot(runId);
    }

    private AgentStepAuditDto toDto(AgentStep step) {
        return new AgentStepAuditDto(
                step.getId(),
                step.getStepName(),
                step.getStatus(),
                step.getInputSnapshot(),
                step.getOutputSnapshot(),
                step.isLlmUsed(),
                step.getToolUsed(),
                step.getCostMs(),
                step.getErrorMessage(),
                step.getCreatedAt()
        );
    }
}
