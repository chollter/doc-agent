package com.gcll.docagent.api;

import com.gcll.docagent.agent.AgentStepEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/tickets/agent-runs")
public class AgentRunStreamController {

    private final AgentStepEventPublisher stepEventPublisher;

    public AgentRunStreamController(AgentStepEventPublisher stepEventPublisher) {
        this.stepEventPublisher = stepEventPublisher;
    }

    @GetMapping(value = "/{runId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String runId) {
        // 5 minutes SSE timeout
        SseEmitter emitter = new SseEmitter(300_000L);
        stepEventPublisher.register(runId, emitter);
        return emitter;
    }
}
