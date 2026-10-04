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

    /** 心跳：推进 ANALYZING 状态 run 的 updated_at，防 stale 重排在执行中误触发（僵尸双执行）。 */
    void touch(String runId);

    /** 某用户最近的已完成简历分析，倒序取 limit 条——供迭代基线候选推荐（不自动绑定）。 */
    List<AgentRun> findRecentResumeRuns(String userId, int limit);
}
