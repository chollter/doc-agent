package com.gcll.ticketagent.langchain4j;

import dev.langchain4j.service.UserMessage;

/**
 * ReAct AiService 接口——LangChain4j 的类型安全 Agent。
 *
 * <p>AiService 自动处理 ReAct 循环的核心逻辑：
 * <ol>
 *   <li>LLM 生成 Thought（推理）</li>
 *   <li>LLM 决定调哪个 @Tool（Action）</li>
 *   <li>执行 @Tool，返回 Observation</li>
 *   <li>将 Observation 追加到上下文，回到步骤1</li>
 *   <li>直到 LLM 给出最终回复或达到 maxToolCallingRoundTrips</li>
 * </ol>
 *
 * <p>系统提示词在 {@link LangChain4jConfig} 中通过 AiServices.builder().systemMessageProvider() 注入，
 * 因为需要动态包含工单上下文（issueType/priority/routedTeam 等），
 * 无法使用静态的 @SystemMessage 注解。
 */
public interface ReActAssistant {

    /**
     * 执行 ReAct 推理循环。
     *
     * @param userMessage 用户消息（工单详情）
     * @return 最终推理结论
     */
    String investigate(@UserMessage String userMessage);
}
