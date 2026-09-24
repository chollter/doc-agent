package com.gcll.docagent.analysis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gcll.docagent.persistence.entity.AgentMessageEntity;
import com.gcll.docagent.persistence.mapper.AgentMessageMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 追问消息持久化——run 的多轮对话（USER/ASSISTANT）按 turn 升序存取。
 * <p>从编排服务剥离：消息读写是独立的持久化关注点，与分析执行链无关。
 */
@Component
public class RunMessageStore {

    private final AgentMessageMapper agentMessageMapper;

    public RunMessageStore(AgentMessageMapper agentMessageMapper) {
        this.agentMessageMapper = agentMessageMapper;
    }

    public List<AgentMessageEntity> getMessages(String runId) {
        return agentMessageMapper.selectList(
                new LambdaQueryWrapper<AgentMessageEntity>()
                        .eq(AgentMessageEntity::getRunId, runId)
                        .orderByAsc(AgentMessageEntity::getTurn));
    }

    public void saveMessage(String runId, int turn, String role, String content) {
        AgentMessageEntity entity = new AgentMessageEntity();
        entity.setRunId(runId);
        entity.setTurn(turn);
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt(LocalDateTime.now());
        agentMessageMapper.insert(entity);
    }
}
