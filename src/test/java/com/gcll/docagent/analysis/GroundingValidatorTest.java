package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 落地性校验测试——反编造不变量的行为锁定：
 * 编造数字必被标记、原文/引文数字放行、占位符豁免、失锚引文必被标记。
 */
class GroundingValidatorTest {

    private final GroundingValidator validator = new GroundingValidator();

    private static final String RESUME = "负责订单系统重构，QPS 从 500 提升到 2000，可用性 99.95%";

    private static ActionableSuggestion sug(String before, String after) {
        return new ActionableSuggestion("HIGH", "工作经历", "sec-1", before, after, "原因");
    }

    @Test
    void shouldFlagFabricatedNumbers() {
        var findings = validator.validate(
                List.of(sug("负责订单系统重构", "重构后 QPS 达到 8000，错误率 0.01%")), RESUME);

        assertThat(findings)
                .anyMatch(f -> f.type().equals(GroundingValidator.Finding.FABRICATED_NUMBER)
                        && f.detail().contains("8000"))
                .anyMatch(f -> f.type().equals(GroundingValidator.Finding.FABRICATED_NUMBER)
                        && f.detail().contains("0.01"));
    }

    @Test
    void shouldPassGroundedNumbers() {
        var findings = validator.validate(
                List.of(sug("QPS 从 500 提升到 2000", "QPS 从 500 提升到 2000（原文数据）")), RESUME);

        assertThat(findings).isEmpty();
    }

    @Test
    void placeholderNumbersAreExempt() {
        var findings = validator.validate(
                List.of(sug("负责订单系统重构", "重构后 QPS 从【补充真实数据】提升到【补充真实数据】")), RESUME);

        assertThat(findings).isEmpty();
    }

    @Test
    void beforeQuoteNotInResumeShouldBeFlagged() {
        var findings = validator.validate(
                List.of(sug("主导了整个中台建设", "主导中台建设，QPS 500")), RESUME);

        assertThat(findings).anyMatch(f -> f.type().equals(GroundingValidator.Finding.UNGROUNDED_BEFORE));
    }

    @Test
    void whitespaceNormalizationShouldKeepAnchors() {
        String pdfLikeResume = "负责订单系统重构，\nQPS 从 500 提升到 2000";
        var findings = validator.validate(
                List.of(sug("QPS 从 500 提升到 2000", "QPS 从 500 提升到 2000")), pdfLikeResume);

        assertThat(findings).isEmpty();
    }

    @Test
    void extractNumbersShouldSkipPlaceholderSpans() {
        assertThat(GroundingValidator.extractGroundableNumbers("从【补充 200 条数据】中挑 5 条"))
                .containsExactly("5");
    }
}
