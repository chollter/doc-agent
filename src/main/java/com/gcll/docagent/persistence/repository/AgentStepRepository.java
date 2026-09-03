package com.gcll.docagent.persistence.repository;

import com.gcll.docagent.domain.AgentStep;

import java.util.List;

public interface AgentStepRepository {
    void save(AgentStep step);

    List<AgentStep> findByRunId(String runId);
}
