package com.gcll.ticketagent.langchain4j;

import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j 配置——ReAct 推理循环的底层支撑。
 *
 * <h3>与 Spring AI Alibaba 共存策略</h3>
 * Spring AI 管分诊/抽取/建议等流程调用（ChatClient + dashscope starter），
 * LangChain4j 管 ReAct 自主推理+工具迭代（AiService + @Tool）。
 * 两者通过 OpenAI 兼容协议接入同一个 dashscope 模型，互不冲突：
 * <ul>
 *   <li>Spring AI：spring.ai.dashscope.* 配置，自动注入 ChatClient.Builder</li>
 *   <li>LangChain4j：此处手动构建 OpenAiChatModel，baseUrl 指向阿里云百炼 OpenAI 兼容端点</li>
 * </ul>
 *
 * <h3>模型配置</h3>
 * ReAct 循环需要强推理能力，默认用 qwen-plus（与 Spring AI 默认模型一致）。
 * 可通过环境变量 LANGCHAIN4J_MODEL 覆盖。
 */
@Configuration
public class LangChain4jConfig {

    /** ReAct 最大工具调用轮次——每轮可调多个工具，防止无限循环 */
    private static final int MAX_TOOL_CALLING_ROUNDS = 8;

    @Bean
    public OpenAiChatModel langChain4jChatModel(
            @Value("${spring.ai.dashscope.chat.options.model:${LLM_MODEL:qwen-plus}}") String model,
            @Value("${spring.ai.dashscope.api-key:${LLM_API_KEY:}}") String apiKey
    ) {
        return OpenAiChatModel.builder()
                .baseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
                .apiKey(apiKey)
                .modelName(model)
                .timeout(Duration.ofSeconds(90))
                .temperature(0.3)
                .maxRetries(1)
                .build();
    }

    /**
     * ReAct AiService——LangChain4j 的类型安全 Agent 接口。
     * AiService 自动处理：工具注册、ReAct 循环、步数限制。
     *
     * <p>系统提示词通过 systemMessageProvider 动态注入（含工单上下文），
     * 每次调用前由 {@link ReActInvestigationStrategy} 设置到 {@link ReActContextHolder}。
     */
    @Bean
    public ReActAssistant reActAssistant(OpenAiChatModel chatModel, ReActToolProvider toolProvider) {
        return AiServices.builder(ReActAssistant.class)
                .chatModel(chatModel)
                .tools(toolProvider)
                .systemMessageProvider(args -> ReActContextHolder.getSystemPrompt())
                .maxToolCallingRoundTrips(MAX_TOOL_CALLING_ROUNDS)
                .build();
    }
}
