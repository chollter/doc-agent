package com.gcll.docagent.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden case 回归——走完整生产链路（提交→异步执行→断言→轨迹指标）。
 * <p>断言与执行模式无关：无有效 API Key 的环境降级到 FALLBACK 也必须全绿，
 * 因此该测试在 CI（test-key，LLM 调用必然失败降级）与本地（真实 Key，REACT 路径）
 * 都是同一套通过标准。
 */
@SpringBootTest
@ActiveProfiles("test")
class EvalGoldenCaseRegressionTest {

    @Autowired
    private EvalRunner evalRunner;

    @Test
    @Timeout(600)
    void allGoldenCasesPassInAnyExecutionMode() {
        EvalRunner.EvalReport report = evalRunner.runAll();

        List<String> failureSummary = report.cases().stream()
                .filter(c -> !c.pass())
                .flatMap(c -> c.failures().stream().map(f -> c.name() + ": " + f))
                .toList();
        assertThat(failureSummary)
                .as("评测失败明细（total=%d, passed=%d）: %s", report.total(), report.passed(), failureSummary)
                .isEmpty();
        assertThat(report.passed()).isEqualTo(report.total());

        // 轨迹指标必须可计算（降级模式下无 ReAct 步，指标为 0 也算有效）
        report.cases().forEach(c -> assertThat(c.metrics()).isNotNull());
    }
}
