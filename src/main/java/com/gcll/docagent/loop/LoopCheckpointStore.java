package com.gcll.docagent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.persistence.entity.LoopCheckpointEntity;
import com.gcll.docagent.persistence.mapper.LoopCheckpointMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 循环检查点存储——每轮结束落库一次完整 LoopState（消息+预算+文档快照），
 * 是崩溃恢复与"执行可审计"的物理基础。
 */
@Component
public class LoopCheckpointStore {

    private static final Logger log = LoggerFactory.getLogger(LoopCheckpointStore.class);

    private final LoopCheckpointMapper mapper;
    private final ObjectMapper objectMapper;

    public LoopCheckpointStore(LoopCheckpointMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void save(String runId, LoopState state) {
        try {
            LoopCheckpointEntity entity = new LoopCheckpointEntity();
            entity.setRunId(runId);
            entity.setRound(state.round());
            entity.setStateJson(objectMapper.writeValueAsString(state));
            entity.setUpdatedAt(LocalDateTime.now());
            if (mapper.selectById(runId) == null) {
                mapper.insert(entity);
            } else {
                mapper.updateById(entity);
            }
        } catch (Exception ex) {
            // checkpoint 失败不阻断循环（可恢复性降级，运行正确性不受影响）
            log.warn("checkpoint 写入失败, runId={}: {}", runId, ex.getMessage());
        }
    }

    public Optional<LoopState> find(String runId) {
        LoopCheckpointEntity entity = mapper.selectById(runId);
        if (entity == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(entity.getStateJson(), LoopState.class));
        } catch (Exception ex) {
            log.warn("checkpoint 读取失败, runId={}: {}", runId, ex.getMessage());
            return Optional.empty();
        }
    }

    public void delete(String runId) {
        mapper.deleteById(runId);
    }
}
