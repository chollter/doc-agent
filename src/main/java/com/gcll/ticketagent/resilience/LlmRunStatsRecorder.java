package com.gcll.ticketagent.resilience;

import com.gcll.ticketagent.api.dto.LlmRunStatsDto;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LlmRunStatsRecorder {

    /** 最大持有的 runId 数量，超出时淘汰最旧的。防止长运行内存泄漏。 */
    private static final int MAX_RUN_ENTRIES = 500;

    private final Map<String, List<LlmRunStatsDto.LlmCallBrief>> callsByRunId = new ConcurrentHashMap<>();

    public void record(String runId, String callName, CallResult<LlmResponse> result) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        LlmResponse response = result.value();
        Throwable error = result.error();
        LlmRunStatsDto.LlmCallBrief call = new LlmRunStatsDto.LlmCallBrief(
                callName,
                result.success(),
                result.circuitOpen(),
                result.attempts(),
                result.durationMs(),
                response == null ? null : response.model(),
                response == null ? 0 : response.promptTokens(),
                response == null ? 0 : response.completionTokens(),
                error == null ? null : error.getClass().getSimpleName(),
                Instant.now()
        );
        callsByRunId.computeIfAbsent(runId, ignored -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(call);
        evictIfNeeded();
    }

    /**
     * 工单结束后调用，释放该 runId 的统计数据。
     */
    public void remove(String runId) {
        if (runId != null) {
            callsByRunId.remove(runId);
        }
    }

    /**
     * 超出 MAX_RUN_ENTRIES 时淘汰最旧的条目（按列表首条时间粗略判断）。
     */
    private void evictIfNeeded() {
        if (callsByRunId.size() <= MAX_RUN_ENTRIES) {
            return;
        }
        // 简单策略：移除最早插入的 1/4 条目
        int toRemove = MAX_RUN_ENTRIES / 4;
        var iterator = callsByRunId.keySet().iterator();
        for (int i = 0; i < toRemove && iterator.hasNext(); i++) {
            iterator.next();
            iterator.remove();
        }
    }

    public LlmRunStatsDto snapshot(String runId) {
        List<LlmRunStatsDto.LlmCallBrief> storedCalls = callsByRunId.getOrDefault(runId, List.of());
        List<LlmRunStatsDto.LlmCallBrief> calls;
        synchronized (storedCalls) {
            calls = new ArrayList<>(storedCalls);
        }
        calls.sort(Comparator.comparing(LlmRunStatsDto.LlmCallBrief::occurredAt));
        Map<String, MutableCallNameStats> mutableByCallName = new LinkedHashMap<>();
        int successCalls = 0;
        int failedCalls = 0;
        int fallbackSignals = 0;
        long totalDurationMs = 0;

        for (LlmRunStatsDto.LlmCallBrief call : calls) {
            if (call.success()) {
                successCalls++;
            } else {
                failedCalls++;
                fallbackSignals++;
            }
            totalDurationMs += call.durationMs();
            mutableByCallName.computeIfAbsent(call.callName(), ignored -> new MutableCallNameStats())
                    .record(call);
        }

        Map<String, LlmRunStatsDto.CallNameStats> byCallName = new LinkedHashMap<>();
        mutableByCallName.forEach((callName, stats) -> byCallName.put(callName, stats.toDto()));
        return new LlmRunStatsDto(
                runId,
                calls.size(),
                successCalls,
                failedCalls,
                fallbackSignals,
                totalDurationMs,
                byCallName,
                calls
        );
    }

    private static final class MutableCallNameStats {
        private int totalCalls;
        private int successCalls;
        private int failedCalls;
        private long totalDurationMs;
        private long maxDurationMs;

        private void record(LlmRunStatsDto.LlmCallBrief call) {
            totalCalls++;
            if (call.success()) {
                successCalls++;
            } else {
                failedCalls++;
            }
            totalDurationMs += call.durationMs();
            maxDurationMs = Math.max(maxDurationMs, call.durationMs());
        }

        private LlmRunStatsDto.CallNameStats toDto() {
            return new LlmRunStatsDto.CallNameStats(
                    totalCalls,
                    successCalls,
                    failedCalls,
                    totalDurationMs,
                    maxDurationMs
            );
        }
    }
}
