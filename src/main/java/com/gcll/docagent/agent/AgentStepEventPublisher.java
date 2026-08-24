package com.gcll.docagent.agent;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class AgentStepEventPublisher {

    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public void register(String runId, SseEmitter emitter) {
        emitters.computeIfAbsent(runId, key -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
    }

    public void publish(String runId, String stepName, String status, String message) {
        List<SseEmitter> runEmitters = emitters.get(runId);
        if (runEmitters == null) {
            return;
        }
        for (SseEmitter emitter : runEmitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name("step")
                        .data(Map.of(
                                "runId", runId,
                                "stepName", stepName,
                                "status", status,
                                "message", message == null ? "" : message,
                                "timestamp", System.currentTimeMillis()
                        )));
            } catch (IOException ex) {
                remove(runId, emitter);
            }
        }
    }

    private void remove(String runId, SseEmitter emitter) {
        List<SseEmitter> runEmitters = emitters.get(runId);
        if (runEmitters != null) {
            runEmitters.remove(emitter);
        }
    }
}
