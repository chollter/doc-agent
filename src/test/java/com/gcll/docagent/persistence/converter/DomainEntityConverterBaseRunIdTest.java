package com.gcll.docagent.persistence.converter;

import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;
import com.gcll.docagent.persistence.entity.AgentRunEntity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * baseRunId 血缘字段的往返测试。同时钉住"迁移向后兼容"——
 * 老 run（无 base_run_id 列 / 值为 null）读写不能被 converter 破坏。
 */
class DomainEntityConverterBaseRunIdTest {

    private AgentRun newRun() {
        AgentRun run = new AgentRun("doc-1", "trace-1", "web", "demo-user", "# 简历");
        run.setStatus(AgentRunStatus.COMPLETED);
        return run;
    }

    @Test
    void roundTripsExplicitlyBoundBaseRunId() {
        AgentRun run = newRun();
        run.setBaseRunId("doc-old-99");

        AgentRunEntity entity = DomainEntityConverter.toEntity(run);
        assertThat(entity.getBaseRunId()).isEqualTo("doc-old-99");

        AgentRun back = DomainEntityConverter.toDomain(entity);
        assertThat(back.getBaseRunId()).isEqualTo("doc-old-99");
    }

    @Test
    void nullBaseRunIdSurvivesRoundTripForLegacyRuns() {
        AgentRun run = newRun();

        AgentRunEntity entity = DomainEntityConverter.toEntity(run);
        assertThat(entity.getBaseRunId()).isNull();

        AgentRun back = DomainEntityConverter.toDomain(entity);
        assertThat(back.getBaseRunId()).isNull();
    }
}
