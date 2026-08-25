package com.gcll.docagent.langchain4j;

import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LangChain4j 配置——ReAct 循环的底层模型接入。
 *
 * <h3>与 Spring AI Alibaba 共存</h3>
 * Spring AI 管流程化调用（直连 LLM 降级路径），LangChain4j 管 ReAct 自主推理+工具迭代。
 * 两者通过 OpenAI 兼容协议接入同一个 DashScope 模型，互不冲突。
 *
 * <p>技能专属的 AiService 装配见 {@link SkillAssistantFactory}。
 */
@Configuration
public class LangChain4jConfig {

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
}
