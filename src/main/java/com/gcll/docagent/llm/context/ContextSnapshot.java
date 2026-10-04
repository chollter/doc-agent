package com.gcll.docagent.llm.context;

import java.util.List;

/**
 * 上下文快照——一次 LLM 判断"到底喂进了什么"的账本。
 *
 * <p>问题驱动：简历主路径的 prompt 是无预算裸拼接（全文直灌），出了判断偏差时
 * 无法复现"当时窗口里有哪些证据、哪段被折叠/丢弃"。快照让每次组装可复现、可归因，
 * 丢证据可告警（{@link #droppedEvidence}），是判断质量工程与版本迭代归因的地基。
 *
 * @param callSite          组装点标识（如 DIRECT_LLM / EVALUATION）
 * @param segments          逐段记账（保留了什么、折叠了什么、丢的是什么）
 * @param totalTokensBefore 折叠/丢弃前的总 token 估算
 * @param totalTokensAfter  实际喂进窗口的总 token 估算
 * @param budgetTokens      本次组装的 token 预算（≤0 表示不设限，逐字保留）
 * @param droppedEvidence   被预算整段挤掉的证据来源（告警来源，非静默丢弃）
 */
public record ContextSnapshot(
        String callSite,
        List<SegmentRecord> segments,
        int totalTokensBefore,
        int totalTokensAfter,
        int budgetTokens,
        List<String> droppedEvidence) {

    public enum Disposition { KEPT, FOLDED, DROPPED }

    /**
     * 一段上下文的账本记录。
     *
     * @param why     入选理由短码（这段为什么进上下文），血缘记账
     * @param anchors 该段携带的证据锚点（节ID 等），回答"判断锚在哪"
     */
    public record SegmentRecord(
            String kind,
            String source,
            int tokensBefore,
            int tokensAfter,
            Disposition disposition,
            String why,
            java.util.List<String> anchors) {
    }

    /** 是否发生过折叠或丢弃——true 说明窗口没装下全部候选上下文。 */
    public boolean hasLoss() {
        return segments.stream().anyMatch(s -> s.disposition() != Disposition.KEPT);
    }

    /** 压成一行账本摘要，供 trace/log 落地（可从 run.getSteps() 反查）。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(callSite).append(" tokens=").append(totalTokensAfter).append('/').append(totalTokensBefore)
                .append(" budget=").append(budgetTokens <= 0 ? "unlimited" : budgetTokens);
        for (SegmentRecord s : segments) {
            sb.append(" | ").append(s.kind()).append('(').append(s.disposition());
            if (s.disposition() != Disposition.KEPT) {
                sb.append(' ').append(s.tokensAfter()).append('/').append(s.tokensBefore());
            }
            if (s.why() != null && !s.why().isBlank()) {
                sb.append(" why=").append(s.why());
            }
            if (s.anchors() != null && !s.anchors().isEmpty()) {
                sb.append(" anchors=").append(anchorsRepr(s.anchors()));
            }
            sb.append(')');
        }
        if (!droppedEvidence.isEmpty()) {
            sb.append(" | DROPPED=").append(droppedEvidence);
        }
        return sb.toString();
    }

    /** 锚点列表压缩表示：最多列 3 个节ID，其余计数（防一行账本被长列表撑爆）。 */
    private static String anchorsRepr(java.util.List<String> anchors) {
        int n = anchors.size();
        String head = String.join(",", anchors.subList(0, Math.min(3, n)));
        return n > 3 ? head + ",+" + (n - 3) : head;
    }
}
