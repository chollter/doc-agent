package com.gcll.docagent.llm.context;

import com.gcll.docagent.llm.context.ContextSnapshot.Disposition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextAssembler 单测——上下文快照账本的核心行为。
 *
 * <p>重点验证两条契约：
 * <ol>
 *   <li><b>逐字回归</b>：不设限或全部在预算内时，输出严格等于 concat(prefix+text)，正常简历零变化；</li>
 *   <li><b>记账不静默</b>：超预算时折叠尾段（FOLDED）或整段丢弃并把来源写进 droppedEvidence（DROPPED）。</li>
 * </ol>
 * 预算用 {@link ContextWindowManager#estimateTokens} 反推，避免依赖硬编码 token 数。
 */
class ContextAssemblerTest {

    private final ContextWindowManager cwm = new ContextWindowManager(4000, 800, 5);
    private final ContextAssembler assembler = new ContextAssembler(cwm, 0);

    private static ContextAssembler.Segment seg(String kind, String source, String text) {
        return ContextAssembler.Segment.of(kind, source, text);
    }

    @Test
    void keepsAllSegmentsVerbatimWhenUnderBudget() {
        ContextAssembler.Segment a = seg("instr", "run", "分析这份简历");
        ContextAssembler.Segment b = seg("fulltext", "doc", "张三 6年 Java 后端");
        int budget = cwm.estimateTokens(a.text()) + cwm.estimateTokens(b.text()) + 100;

        ContextAssembler.AssembledContext result = assembler.assemble("DIRECT_LLM", List.of(a, b), budget);

        // 逐字等于旧的裸拼接（prefix 均为空）
        assertThat(result.text()).isEqualTo("分析这份简历张三 6年 Java 后端");
        assertThat(result.snapshot().segments())
                .allMatch(s -> s.disposition() == Disposition.KEPT);
        assertThat(result.snapshot().droppedEvidence()).isEmpty();
        assertThat(result.snapshot().hasLoss()).isFalse();
        assertThat(result.snapshot().totalTokensAfter()).isEqualTo(result.snapshot().totalTokensBefore());
    }

    @Test
    void prefixIsReproducedVerbatimSoOldSeparatorsSurvive() {
        ContextAssembler.Segment a = seg("instr", "run", "头部");
        ContextAssembler.Segment b = new ContextAssembler.Segment("fulltext", "doc", "\n## 简历全文\n", "正文");
        int budget = cwm.estimateTokens("头部") + cwm.estimateTokens("\n## 简历全文\n") + cwm.estimateTokens("正文") + 50;

        ContextAssembler.AssembledContext result = assembler.assemble("EVALUATION", List.of(a, b), budget);

        assertThat(result.text()).isEqualTo("头部\n## 简历全文\n正文");
    }

    @Test
    void budgetNonPositiveMeansUnlimitedAndVerbatim() {
        String huge = "x".repeat(50000);
        ContextAssembler.AssembledContext result = assembler.assemble("DIRECT_LLM", List.of(seg("fulltext", "doc", huge)), 0);

        assertThat(result.text()).isEqualTo(huge);
        assertThat(result.snapshot().segments().get(0).disposition()).isEqualTo(Disposition.KEPT);
        assertThat(result.snapshot().hasLoss()).isFalse();
    }

    @Test
    void foldsTailSegmentWhenOverBudget() {
        ContextAssembler.Segment a = seg("instr", "run", "short head");
        String bigBody = "简历正文".repeat(2000);
        ContextAssembler.Segment b = new ContextAssembler.Segment("fulltext", "doc", "\n## 全文\n", bigBody);

        int budget = cwm.estimateTokens(a.text())
                + cwm.estimateTokens(b.prefix())
                + cwm.estimateTokens(bigBody) / 2; // 只够 a + prefix + 半个 body → b 必折叠

        ContextAssembler.AssembledContext result = assembler.assemble("DIRECT_LLM", List.of(a, b), budget);

        ContextSnapshot.SegmentRecord recB = result.snapshot().segments().get(1);
        assertThat(recB.disposition()).isEqualTo(Disposition.FOLDED);
        assertThat(recB.tokensAfter()).isLessThan(recB.tokensBefore());
        // prefix 完整保留（语义标签不被截没），正文被折叠并带截断标记
        assertThat(result.text()).contains("short head").contains("## 全文").contains("截断");
        assertThat(result.snapshot().hasLoss()).isTrue();
        assertThat(result.snapshot().droppedEvidence()).isEmpty();
    }

    @Test
    void dropsSegmentAndRecordsEvidenceWhenNoBudgetLeft() {
        String bigBody = "简历正文".repeat(2000);
        ContextAssembler.Segment a = seg("fulltext", "doc-1", bigBody);
        ContextAssembler.Segment b = seg("evidence", "sec-9", "17人组约4人转岗");
        int budget = cwm.estimateTokens(bigBody); // 只够 a，b 无预算可用

        ContextAssembler.AssembledContext result = assembler.assemble("EVALUATION", List.of(a, b), budget);

        assertThat(result.snapshot().segments().get(0).disposition()).isEqualTo(Disposition.KEPT);
        assertThat(result.snapshot().segments().get(1).disposition()).isEqualTo(Disposition.DROPPED);
        assertThat(result.snapshot().droppedEvidence()).containsExactly("sec-9");
        assertThat(result.text()).doesNotContain("17人组约4人转岗");
        assertThat(result.snapshot().hasLoss()).isTrue();
        // 账本摘要把丢弃来源显式写出，不静默
        assertThat(result.snapshot().describe()).contains("DROPPED=").contains("sec-9");
    }

    @Test
    void describeRecordsSelectionLineage() {
        ContextAssembler.Segment a = new ContextAssembler.Segment("fulltext", "doc", "", "正文内容",
                "用户上传文档全文", List.of("sec-1", "sec-2", "sec-3", "sec-4", "sec-5"));
        ContextAssembler.AssembledContext result = assembler.assemble("DIRECT_LLM", List.of(a), 5000);

        // 账本能回答"这段为什么进上下文、锚在哪几节"；锚点超 3 个压缩为 +n
        assertThat(result.snapshot().describe())
                .contains("fulltext(KEPT why=用户上传文档全文 anchors=sec-1,sec-2,sec-3,+2)");
        // 血缘字段不改变装配输出（逐字回归）
        assertThat(result.text()).isEqualTo("正文内容");
    }

    @Test
    void exposeBudgetOverrideForCallers() {
        assertThat(new ContextAssembler(cwm, 1234).budgetOverride()).isEqualTo(1234);
        assertThat(assembler.budgetOverride()).isEqualTo(0);
    }
}
