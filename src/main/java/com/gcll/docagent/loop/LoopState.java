package com.gcll.docagent.loop;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gcll.docagent.parsing.ParsedDocument;

import java.util.List;

/**
 * 循环状态——checkpoint 的完整载荷：消息历史 + 预算消耗 + 文档快照 + 会话轮次。
 * <p>turn=0 为首轮（结构化报告），turn>0 为追问轮（自由文本回答）。
 * 文档随状态持久化是崩溃恢复的关键：重启后 DocumentStore（内存）已失，
 * 恢复执行从 checkpoint 重建文档与上下文，从最后一轮继续。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoopState(
        String skillName,
        List<LoopMessage> messages,
        int round,
        int toolCalls,
        long tokensUsed,
        ParsedDocument document,
        int turn
) {

    public LoopState(String skillName, List<LoopMessage> messages, int round,
                     int toolCalls, long tokensUsed, ParsedDocument document) {
        this(skillName, messages, round, toolCalls, tokensUsed, document, 0);
    }

    public LoopState advance(int toolCallsDelta) {
        return new LoopState(skillName, messages, round + 1, toolCalls + toolCallsDelta, tokensUsed, document, turn);
    }

    public LoopState withTokens(long tokensUsed) {
        return new LoopState(skillName, messages, round, toolCalls, tokensUsed, document, turn);
    }

    public LoopState withMessages(List<LoopMessage> messages) {
        return new LoopState(skillName, messages, round, toolCalls, tokensUsed, document, turn);
    }

    /** 追问轮：重置轮次预算但保留消息历史、文档和累计 token。 */
    public LoopState nextTurn(List<LoopMessage> messages) {
        return new LoopState(skillName, messages, 0, 0, tokensUsed, document, turn + 1);
    }
}
