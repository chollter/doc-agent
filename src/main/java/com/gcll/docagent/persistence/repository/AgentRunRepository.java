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
}
