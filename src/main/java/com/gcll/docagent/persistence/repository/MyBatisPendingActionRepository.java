package com.gcll.docagent.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gcll.docagent.human.PendingAction;
import com.gcll.docagent.human.PendingActionStatus;
import com.gcll.docagent.human.PendingActionType;
import com.gcll.docagent.persistence.entity.PendingActionEntity;
import com.gcll.docagent.persistence.mapper.PendingActionMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Repository
public class MyBatisPendingActionRepository implements PendingActionRepository {

    private final PendingActionMapper pendingActionMapper;

    public MyBatisPendingActionRepository(PendingActionMapper pendingActionMapper) {
        this.pendingActionMapper = pendingActionMapper;
    }

    @Override
    public PendingAction save(PendingAction action) {
        PendingActionEntity entity = toEntity(action);
        if (pendingActionMapper.selectById(action.getId()) == null) {
            pendingActionMapper.insert(entity);
        } else {
            pendingActionMapper.updateById(entity);
        }
        return action;
    }

    @Override
    public Optional<PendingAction> findById(String id) {
        PendingActionEntity entity = pendingActionMapper.selectById(id);
        return entity == null ? Optional.empty() : Optional.of(toDomain(entity));
    }

    @Override
    public List<PendingAction> findPending() {
        LambdaQueryWrapper<PendingActionEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PendingActionEntity::getStatus, PendingActionStatus.PENDING.name());
        return pendingActionMapper.selectList(wrapper).stream().map(this::toDomain).toList();
    }

    private PendingActionEntity toEntity(PendingAction action) {
        PendingActionEntity entity = new PendingActionEntity();
        entity.setId(action.getId());
        entity.setRunId(action.getRunId());
        entity.setActionType(action.getActionType().name());
        entity.setStatus(action.getStatus().name());
        entity.setPayload(action.getPayload());
        entity.setReason(action.getReason());
        entity.setCreatedAt(LocalDateTime.ofInstant(action.getCreatedAt(), ZoneId.systemDefault()));
        if (action.getConfirmedAt() != null) {
            entity.setConfirmedAt(LocalDateTime.ofInstant(action.getConfirmedAt(), ZoneId.systemDefault()));
        }
        entity.setConfirmedBy(action.getConfirmedBy());
        entity.setTargetTeam(action.getTargetTeam());
        return entity;
    }

    private PendingAction toDomain(PendingActionEntity entity) {
        PendingAction action = new PendingAction(
                entity.getId(),
                entity.getRunId(),
                PendingActionType.valueOf(entity.getActionType()),
                entity.getPayload(),
                entity.getReason(),
                entity.getTargetTeam()
        );
        if (PendingActionStatus.CONFIRMED.name().equals(entity.getStatus())) {
            action.confirm(entity.getConfirmedBy());
        } else if (PendingActionStatus.REJECTED.name().equals(entity.getStatus())) {
            action.reject(entity.getConfirmedBy());
        }
        return action;
    }
}
