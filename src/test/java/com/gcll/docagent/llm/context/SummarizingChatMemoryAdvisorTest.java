package com.gcll.docagent.llm.context;

import com.gcll.docagent.resilience.CallResult;
import com.gcll.docagent.resilience.LlmCallExecutor;
import com.gcll.docagent.resilience.LlmResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * SummarizingChatMemoryAdvisor 核心算法单测（阶段 A）。
 * 直接测 package-private 的 {@code manageHistory}，覆盖三个分支：
 * <ol>
 *   <li>历史未超阈值 → 不摘要（原样返回）</li>
 *   <li>历史超阈值 → 摘要旧消息 + 保留最近 N 条</li>
 *   <li>摘要 LLM 失败 → 降级裁剪（保留最近 N 条）</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class SummarizingChatMemoryAdvisorTest {

    @Mock
    private LlmCallExecutor summarizer;

    /** 测试用 advisor：窗口设很小（500 token），阈值 0.5 → 250 token 触发，保留最近 2 条。 */
    private SummarizingChatMemoryAdvisor advisor;

    @BeforeEach
    void setUp() {
        // contextWindowTokens=500, thresholdRatio=0.5 → 阈值 250 token；keepRecent=2
        // manageHistory 不依赖 chatMemory，但 AbstractChatMemoryAdvisor 父类要求非 null，给个真实实例
        advisor = new SummarizingChatMemoryAdvisor(
                MessageWindowChatMemory.builder().maxMessages(100).build(),
                summarizer, 500, 0.5, 2);
    }

    /** 场景1：历史 token 未超阈值 → 原样返回，不触发摘要、不调 LLM。 */
    @Test
    void underThresholdReturnsAsIsNoSummarize() {
        // 构造一段短历史（明显低于 250 token 阈值）
        List<Message> history = new ArrayList<>();
        history.add(new UserMessage("系统登录报错"));
        history.add(new AssistantMessage("查看日志"));

        List<Message> result = advisor.manageHistory("run-1", history);

        assertThat(result).hasSameSizeAs(history);
        // 不应调用摘要 LLM
        org.mockito.Mockito.verifyNoInteractions(summarizer);
    }

    /** 场景2：历史超阈值 → 旧消息被摘要成一条 system message，最近 2 条原文保留。 */
    @Test
    void overThresholdSummarizesOldKeepsRecent() {
        // mock 摘要 LLM 返回成功
        when(summarizer.execute(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.ok(LlmResponse.of("这是历史摘要内容", 5, 10), 1, 100));

        // 构造超长历史：8 条消息，每条塞大量重复文本撑爆 250 token 阈值
        List<Message> history = new ArrayList<>();
        String longText = "这是一个很长的排查记录内容用于撑爆 token 阈值触发摘要逻辑".repeat(20);
        for (int i = 0; i < 6; i++) {
            history.add(new UserMessage("第" + i + "轮提问 " + longText));
            history.add(new AssistantMessage("第" + i + "轮回答 " + longText));
        }

        List<Message> result = advisor.manageHistory("run-2", history);

        // 期望：1 条摘要 system message + 最近 2 条原文（keepRecent=2）
        assertThat(result).hasSize(3);
        // 第一条是摘要 system message
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.get(0).getText()).contains("此前对话历史摘要");
        assertThat(result.get(0).getText()).contains("这是历史摘要内容");
        // 后两条是最近的原文
        assertThat(result.get(1)).isInstanceOf(UserMessage.class);
        assertThat(result.get(2)).isInstanceOf(AssistantMessage.class);
    }

    /** 场景3：摘要 LLM 失败/熔断 → 降级为窗口裁剪，保留最近 2 条（不比现状差）。 */
    @Test
    void summarizeFailureDegradesToWindowTrim() {
        // mock 摘要 LLM 返回失败（熔断打开）
        when(summarizer.execute(anyString(), anyString(), anyString()))
                .thenReturn(CallResult.<LlmResponse>circuitOpen(100));

        List<Message> history = new ArrayList<>();
        String longText = "撑爆阈值的超长内容".repeat(30);
        for (int i = 0; i < 6; i++) {
            history.add(new UserMessage("问" + i + longText));
            history.add(new AssistantMessage("答" + i + longText));
        }

        List<Message> result = advisor.manageHistory("run-3", history);

        // 降级：只保留最近 2 条，无摘要 system message
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        assertThat(result.get(1)).isInstanceOf(AssistantMessage.class);
        // 不应出现摘要标记
        result.forEach(m -> assertThat(m.getText()).doesNotContain("此前对话历史摘要"));
    }

    /** 场景4：历史条数 ≤ keepRecent → 即使超 token 阈值也没东西可摘要，退化为保留 recent。 */
    @Test
    void fewerThanKeepRecentTrimsDirectly() {
        // 只有 2 条但单条极长（超 token 阈值），keepRecent=2 → 没东西可摘要
        List<Message> history = new ArrayList<>();
        String huge = "x".repeat(5000);
        history.add(new UserMessage(huge));
        history.add(new AssistantMessage(huge));

        List<Message> result = advisor.manageHistory("run-4", history);

        assertThat(result).hasSize(2);
        org.mockito.Mockito.verifyNoInteractions(summarizer);
    }
}
