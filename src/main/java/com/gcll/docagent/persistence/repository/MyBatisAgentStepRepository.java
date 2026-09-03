package com.gcll.docagent.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gcll.docagent.domain.AgentStep;
import com.gcll.docagent.persistence.converter.DomainEntityConverter;
import com.gcll.docagent.persistence.entity.AgentStepEntity;
import com.gcll.docagent.persistence.mapper.AgentStepMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MyBatisAgentStepRepository implements AgentStepRepository {

    private final AgentStepMapper agentStepMapper;

    public MyBatisAgentStepRepository(AgentStepMapper agentStepMapper) {
        this.agentStepMapper = agentStepMapper;
    }

    @Override
    public void save(AgentStep step) {
        AgentStepEntity entity = DomainEntityConverter.toEntity(step);
        // begin/recordMeta/end 会对同一步骤多次 save，按主键 upsert
        if (agentStepMapper.selectById(entity.getId()) == null) {
            agentStepMapper.insert(entity);
        } else {
            agentStepMapper.updateById(entity);
        }
    }

    @Override
    public List<AgentStep> findByRunId(String runId) {
        LambdaQueryWrapper<AgentStepEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AgentStepEntity::getRunId, runId)
                .orderByAsc(AgentStepEntity::getCreatedAt);
        return agentStepMapper.selectList(wrapper).stream()
                .map(DomainEntityConverter::toDomain)
                .toList();
    }
}
