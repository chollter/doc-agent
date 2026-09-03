package com.gcll.docagent.api;

import com.gcll.docagent.agent.AgentStepEventPublisher;
import com.gcll.docagent.domain.AgentStep;
import com.gcll.docagent.persistence.repository.AgentStepRepository;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 分析 run 的 SSE 流：实时推送每个步骤的 begin/end 事件。
 * <p>连接时先回放已落库的步骤（解决"先跑后连"的竞态，也支持刷新页面/历史回放），
 * 再接入实时事件；前端按 stepId 合并同一步骤的 RUNNING → SUCCESS/FAILED 更新。
 */
@RestController
@RequestMapping("/api/analysis/runs")
public class AnalysisRunStreamController {

    private final AgentStepEventPublisher stepEventPublisher;
    private final AgentStepRepository stepRepository;

    public AnalysisRunStreamController(AgentStepEventPublisher stepEventPublisher,
                                       AgentStepRepository stepRepository) {
        this.stepEventPublisher = stepEventPublisher;
        this.stepRepository = stepRepository;
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId) {
        SseEmitter emitter = new SseEmitter(600_000L);
        stepEventPublisher.register(runId, emitter);
        replay(runId, emitter);
        return emitter;
    }

    private void replay(String runId, SseEmitter emitter) {
        List<AgentStep> steps = stepRepository.findByRunId(runId);
        if (steps.isEmpty()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name("replay").data("begin:" + steps.size()));
            for (AgentStep step : steps) {
                emitter.send(SseEmitter.event()
                        .name("step")
                        .data(java.util.Map.of(
                                "runId", runId,
                                "stepId", step.getId(),
                                "stepName", step.getStepName(),
                                "status", step.getStatus(),
                                "message", step.getOutputSnapshot() == null ? "" : step.getOutputSnapshot(),
                                "timestamp", step.getCreatedAt().toEpochMilli())));
            }
            emitter.send(SseEmitter.event().name("replay").data("end"));
        } catch (Exception ex) {
            // 客户端断开等 IO 异常：交由 publisher 的回调清理
        }
    }
}
