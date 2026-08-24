package com.gcll.docagent.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;
import com.gcll.docagent.persistence.converter.DomainEntityConverter;
import com.gcll.docagent.persistence.entity.AgentRunEntity;
import com.gcll.docagent.persistence.mapper.AgentRunMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
public class MyBatisAgentRunRepository implements AgentRunRepository {

    private final AgentRunMapper agentRunMapper;
    private final AgentStepRepository agentStepRepository;

    public MyBatisAgentRunRepository(AgentRunMapper agentRunMapper, AgentStepRepository agentStepRepository) {
        this.agentRunMapper = agentRunMapper;
        this.agentStepRepository = agentStepRepository;
    }

    @Override
    public AgentRun save(AgentRun run) {
        AgentRunEntity entity = DomainEntityConverter.toEntity(run);
        AgentRunEntity existing = agentRunMapper.selectById(run.getId());
        if (existing == null) {
            agentRunMapper.insert(entity);
        } else {
            agentRunMapper.updateById(entity);
        }
        return run;
    }

    @Override
    public Optional<AgentRun> findById(String id) {
        AgentRunEntity entity = agentRunMapper.selectById(id);
        if (entity == null) {
            return Optional.empty();
        }
        AgentRun run = DomainEntityConverter.toDomain(entity);
        run.getSteps().addAll(agentStepRepository.findByRunId(id));
        return Optional.of(run);
    }

    @Override
    public Optional<AgentRun> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        AgentRunEntity entity = agentRunMapper.selectOne(new LambdaQueryWrapper<AgentRunEntity>()
                .eq(AgentRunEntity::getIdempotencyKey, idempotencyKey)
                .last("LIMIT 1"));
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(DomainEntityConverter.toDomain(entity));
    }

    @Override
    public List<AgentRun> findStuckRunningRuns(Instant updatedBefore) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(updatedBefore, ZoneId.systemDefault());
        return agentRunMapper.selectList(new LambdaQueryWrapper<AgentRunEntity>()
                        .eq(AgentRunEntity::getStatus, AgentRunStatus.RUNNING.name())
                        .lt(AgentRunEntity::getUpdatedAt, cutoff))
                .stream()
                .map(DomainEntityConverter::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public Collection<AgentRun> findAll() {
        return agentRunMapper.selectList(new LambdaQueryWrapper<>()).stream()
                .map(DomainEntityConverter::toDomain)
                .collect(Collectors.toList());
    }
}
