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
 * <p>无有效 API Key 时，resume-review 按产品策略 fail-closed，EvalRunner 将
 * LLM_UNAVAILABLE 视为环境可用性结果；有有效 Key 或桩 LLM 时继续执行完整内容断言。
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
