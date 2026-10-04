package com.gcll.docagent.llm.context;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文装配器——把多段候选上下文按预算拼成最终喂给 LLM 的文本，并产出 {@link ContextSnapshot} 账本。
 *
 * <p>这是简历主路径两个裸拼接点（{@code runDirectLlm} / {@code buildEvaluationInput}）的统一收口：
 * 复用已有的 {@link ContextWindowManager} 做 token 估算与首尾折叠，本类不重造截断逻辑，
 * 只负责"按段过预算 + 记账"。
 *
 * <p><b>逐字回归保证</b>：当 {@code budgetTokens <= 0}（不设限）或所有段都在预算内时，
 * 输出严格等于 {@code concat(prefix + text)}——即与旧的裸拼接逐字一致，正常简历路径零行为变化。
 * 只有真正超预算时才折叠尾段（{@link ContextSnapshot.Disposition#FOLDED}）或整段丢弃并告警
 * （{@link ContextSnapshot.Disposition#DROPPED} → {@link ContextSnapshot#droppedEvidence}）。
 *
 * <p>折叠时段的 {@code prefix}（承载"这段是什么"的语义标签，如"## 简历全文"）始终完整保留，
 * 只压 {@code text} 正文——避免把小节标题也截没导致语义丢失。
 */
@Component
public class ContextAssembler {

    private final ContextWindowManager windowManager;

    /**
     * 预算覆盖值（{@code docagent.analysis.context-budget-tokens}，默认 0=不覆盖）。
     * &gt;0 时装配点用它替代模型窗口派生预算（测试注入/手动收紧场景）；
     * ≤0 表示"由调用方按 {@link com.gcll.docagent.llm.LlmGateway#budgetForCall} 窗口派生"。
     */
    private final int budgetOverrideTokens;

    public ContextAssembler(ContextWindowManager windowManager,
                            @Value("${docagent.analysis.context-budget-tokens:0}") int budgetOverrideTokens) {
        this.windowManager = windowManager;
        this.budgetOverrideTokens = budgetOverrideTokens;
    }

    /** 手动预算覆盖值；≤0 表示未配置覆盖，调用方应走窗口派生。 */
    public int budgetOverride() {
        return budgetOverrideTokens;
    }

    /**
     * 一段候选上下文。
     *
     * @param kind    段类型（instr / observations / fulltext / eval-header ...）
     * @param source  段的来源标识（runId、sectionId、doc 等），丢弃时进 droppedEvidence
     * @param prefix  段前缀分隔符（旧裸拼接里这段之前的固定文本，如 "## 简历全文"）；不参与折叠
     * @param text    段正文；超预算时被折叠
     * @param why     入选理由短码——"这段为什么进上下文"（血缘记账）
     * @param anchors 该段携带的证据锚点（节ID 等），回答"这段内容锚在简历哪几节"
     */
    public record Segment(String kind, String source, String prefix, String text,
                          String why, java.util.List<String> anchors) {

        public Segment(String kind, String source, String prefix, String text) {
            this(kind, source, prefix, text, "", java.util.List.of());
        }

        public static Segment of(String kind, String source, String text) {
            return new Segment(kind, source, "", text, "", java.util.List.of());
        }

        public static Segment of(String kind, String source, String text, String why) {
            return new Segment(kind, source, "", text, why, java.util.List.of());
        }
    }

    public record AssembledContext(String text, ContextSnapshot snapshot) {
    }

    public AssembledContext assemble(String callSite, List<Segment> segments, int budgetTokens) {
        List<ContextSnapshot.SegmentRecord> records = new ArrayList<>();
        List<String> dropped = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        int totalBefore = 0;
        int totalAfter = 0;
        int remaining = budgetTokens;

        for (Segment seg : segments) {
            String prefix = seg.prefix() == null ? "" : seg.prefix();
            String text = seg.text() == null ? "" : seg.text();
            int prefixTokens = windowManager.estimateTokens(prefix);
            int textTokens = windowManager.estimateTokens(text);
            int before = prefixTokens + textTokens;
            totalBefore += before;

            // 预算不设限（≤0，窗口派生失败/覆盖值未配且网关不可用时的回退）：原样保留，逐字等于旧裸拼接
            if (budgetTokens <= 0) {
                out.append(prefix).append(text);
                totalAfter += before;
                records.add(record(seg, before, before, ContextSnapshot.Disposition.KEPT));
                continue;
            }

            if (before <= remaining) {
                out.append(prefix).append(text);
                remaining -= before;
                totalAfter += before;
                records.add(record(seg, before, before, ContextSnapshot.Disposition.KEPT));
            } else if (remaining > prefixTokens) {
                // 折叠：prefix 完整保留，只把 text 压到剩余预算内
                int textBudget = remaining - prefixTokens;
                String folded = windowManager.truncate(text, textBudget);
                int foldedTokens = prefixTokens + windowManager.estimateTokens(folded);
                out.append(prefix).append(folded);
                remaining -= foldedTokens;
                totalAfter += foldedTokens;
                records.add(record(seg, before, foldedTokens, ContextSnapshot.Disposition.FOLDED));
            } else {
                // 连 prefix 都放不下 → 整段丢弃并告警（不静默）
                dropped.add(seg.source() != null ? seg.source() : seg.kind());
                records.add(record(seg, before, 0, ContextSnapshot.Disposition.DROPPED));
            }
        }

        ContextSnapshot snapshot = new ContextSnapshot(
                callSite, records, totalBefore, totalAfter, budgetTokens, dropped);
        return new AssembledContext(out.toString(), snapshot);
    }

    private static ContextSnapshot.SegmentRecord record(
            Segment seg, int before, int after, ContextSnapshot.Disposition disposition) {
        return new ContextSnapshot.SegmentRecord(seg.kind(), seg.source(), before, after, disposition,
                seg.why(), seg.anchors());
    }
}
