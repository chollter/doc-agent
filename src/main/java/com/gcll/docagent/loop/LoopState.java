package com.gcll.docagent.loop;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gcll.docagent.parsing.ParsedDocument;

import java.util.List;

/**
 * 循环状态——checkpoint 的完整载荷：消息历史 + 预算消耗 + 文档快照。
 * <p>文档随状态持久化是崩溃恢复的关键：重启后 DocumentStore（内存）已失，
 * 恢复执行从 checkpoint 重建文档与上下文，从最后一轮继续。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoopState(
        String skillName,
        List<LoopMessage> messages,
        int round,
        int toolCalls,
        long tokensUsed,
        ParsedDocument document
) {

    public LoopState advance(int toolCallsDelta) {
        return new LoopState(skillName, messages, round + 1, toolCalls + toolCallsDelta, tokensUsed, document);
    }

    public LoopState withTokens(long tokensUsed) {
        return new LoopState(skillName, messages, round, toolCalls, tokensUsed, document);
    }

    public LoopState withMessages(List<LoopMessage> messages) {
        return new LoopState(skillName, messages, round, toolCalls, tokensUsed, document);
    }
}
