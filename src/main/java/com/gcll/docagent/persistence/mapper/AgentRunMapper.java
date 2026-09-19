package com.gcll.docagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gcll.docagent.persistence.entity.AgentRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    /** 待认领 run（先进先出）。 */
    @Select("SELECT id FROM agent_run WHERE status = 'QUEUED' ORDER BY created_at LIMIT #{limit}")
    List<String> findQueuedIds(int limit);

    /** CAS 认领：只有仍处于 QUEUED 才会成功，多实例竞争天然互斥（无需 SKIP LOCKED）。 */
    @Update("UPDATE agent_run SET status = 'ANALYZING', claimed_by = #{instance}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND status = 'QUEUED'")
    int claim(@org.apache.ibatis.annotations.Param("id") String id,
              @org.apache.ibatis.annotations.Param("instance") String instance);

    /** 重新入队：卡死/崩溃的 run 回到队列，由任意实例（含原实例）重新认领续跑。 */
    @Update("UPDATE agent_run SET status = 'QUEUED', updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = #{fromStatus}")
    int requeue(@org.apache.ibatis.annotations.Param("id") String id,
                @org.apache.ibatis.annotations.Param("fromStatus") String fromStatus);

    /**
     * 心跳：推进 ANALYZING 状态 run 的 updated_at，防 stale 重排在长 LLM 调用执行中误触发
     * （僵尸双执行）。仅 ANALYZING 生效——终态 run 不被心跳复活。
     */
    @Update("UPDATE agent_run SET updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND status = 'ANALYZING'")
    int heartbeat(@org.apache.ibatis.annotations.Param("id") String id);
}
