package com.gcll.docagent.langchain4j;

import dev.langchain4j.service.UserMessage;

/**
 * 技能执行 ReAct AiService——LangChain4j 类型安全 Agent 接口。
 * <p>每个 {@link com.gcll.docagent.analysis.SkillDefinition} 经
 * {@link SkillAssistantFactory} 生成专属实例：按技能挑选 @Tool Provider 集合，
 * 系统提示词经 systemMessageProvider 从 {@link ReActContextHolder} 动态注入。
 */
public interface SkillAssistant {

    /**
     * 执行 ReAct 分析循环。
     *
     * @param userMessage 用户消息（用户要求 + 文档大纲）
     * @return 最终分析结论（严格 JSON）
     */
    String analyze(@UserMessage String userMessage);
}
