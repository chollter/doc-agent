package com.gcll.docagent.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gcll.docagent.persistence.entity.ToolExecutionLogEntity;
import com.gcll.docagent.persistence.mapper.ToolExecutionLogMapper;
import com.gcll.docagent.tool.ToolResult;
import com.gcll.docagent.tool.ToolType;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class MyBatisToolExecutionLogRepository implements ToolExecutionLogRepository {

    private final ToolExecutionLogMapper mapper;

    public MyBatisToolExecutionLogRepository(ToolExecutionLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void save(String runId, String stepName, ToolResult result) {
        ToolExecutionLogEntity entity = new ToolExecutionLogEntity();
        entity.setId(UUID.randomUUID().toString());
        entity.setRunId(runId);
        entity.setStepName(stepName);
        entity.setToolType(result.toolType().name());
        entity.setToolName(result.toolName());
        entity.setInputSnapshot(truncate(result.input(), 4000));
        entity.setOutputSnapshot(truncate(result.output(), 4000));
        entity.setDurationMs(result.durationMs());
        entity.setStatus(result.success() ? "SUCCESS" : "FAILED");
        entity.setErrorMessage(result.errorMessage());
        entity.setCreatedAt(LocalDateTime.now());
        mapper.insert(entity);
    }

    @Override
    public List<ToolResult> findByRunId(String runId) {
        LambdaQueryWrapper<ToolExecutionLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ToolExecutionLogEntity::getRunId, runId)
                .orderByAsc(ToolExecutionLogEntity::getCreatedAt);
        return mapper.selectList(wrapper).stream()
                .map(this::toToolResult)
                .toList();
    }

    private ToolResult toToolResult(ToolExecutionLogEntity entity) {
        if ("SUCCESS".equals(entity.getStatus())) {
            return ToolResult.success(
                    ToolType.valueOf(entity.getToolType()),
                    entity.getToolName(),
                    entity.getInputSnapshot(),
                    entity.getOutputSnapshot(),
                    entity.getDurationMs() == null ? 0 : entity.getDurationMs()
            );
        }
        return ToolResult.failure(
                ToolType.valueOf(entity.getToolType()),
                entity.getToolName(),
                entity.getInputSnapshot(),
                entity.getErrorMessage(),
                entity.getDurationMs() == null ? 0 : entity.getDurationMs()
        );
    }

    private static String truncate(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLen ? value : value.substring(0, maxLen);
    }
}
