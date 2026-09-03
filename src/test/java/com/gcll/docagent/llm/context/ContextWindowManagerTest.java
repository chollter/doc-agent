package com.gcll.docagent.llm.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContextWindowManager 单测。
 * 重点验证优化1：truncate(content, maxTokens) 按真实 token 截断，而非固定字数。
 */
class ContextWindowManagerTest {

    private final ContextWindowManager manager = new ContextWindowManager(4000, 800, 5);

    /** 老方法 truncate(content)：未超固定字数原样返回。 */
    @Test
    void truncateByCharsUnderLimit() {
        String content = "短内容";
        assertThat(manager.truncate(content)).isSameAs(content);
    }

    /** 老方法 truncate(content)：超固定字数，首尾保留中间折叠。 */
    @Test
    void truncateByCharsOverLimitFoldsMiddle() {
        String content = "x".repeat(5000);
        String result = manager.truncate(content);
        assertThat(result).contains("已截断");
        assertThat(result.length()).isLessThan(content.length());
    }

    /** 优化1：truncate(content, maxTokens) 未超 token 预算原样返回。 */
    @Test
    void truncateByTokensUnderBudget() {
        String content = "短内容不超过预算";
        assertThat(manager.truncate(content, 1000)).isSameAs(content);
    }

    /** 优化1：超 token 预算，按 token 比例截断（保留首尾）。 */
    @Test
    void truncateByTokensOverBudgetFoldsMiddle() {
        // 构造超长内容（中文，约 1 字 1.5 token，重复撑爆预算）
        String content = "支付回调服务发生内存溢出堆栈溢出异常".repeat(500);
        int originalTokens = manager.estimateTokens(content);
        int budget = 100;  // 故意给很小的预算

        String result = manager.truncate(content, budget);

        assertThat(result).contains("已按 token 截断");
        assertThat(result.length()).isLessThan(content.length());
        // 截断后 token 应明显小于原值
        int resultTokens = manager.estimateTokens(result);
        assertThat(resultTokens).isLessThan(originalTokens);
    }

    /** 优化1：maxTokens <= 0 时原样返回（防御）。 */
    @Test
    void truncateByTokensZeroOrNegative() {
        String content = "内容";
        assertThat(manager.truncate(content, 0)).isSameAs(content);
        assertThat(manager.truncate(content, -1)).isSameAs(content);
    }

    /** 优化1：null/空内容安全返回。 */
    @Test
    void truncateByTokensNullEmpty() {
        assertThat(manager.truncate(null, 100)).isNull();
        assertThat(manager.truncate("", 100)).isEqualTo("");
    }
}
