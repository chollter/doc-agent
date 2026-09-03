package com.gcll.docagent.persistence.repository;

import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AgentRunRepository {
    AgentRun save(AgentRun run);

    Optional<AgentRun> findById(String id);

    Optional<AgentRun> findByIdempotencyKey(String idempotencyKey);

    List<AgentRun> findStuckRunningRuns(Instant updatedBefore);

    Collection<AgentRun> findAll();

    List<String> findQueuedIds(int limit);

    /** CAS 认领，成功返回 true（多实例竞争下仅一个赢家）。 */
    boolean claim(String runId, String instance);

    /** 重新入队（自愈路径）。 */
    boolean requeue(String runId, String fromStatus);
}
