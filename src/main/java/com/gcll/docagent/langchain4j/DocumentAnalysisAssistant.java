package com.gcll.docagent.langchain4j;

import dev.langchain4j.service.UserMessage;

/**
 * 文档分析 ReAct AiService——LangChain4j 类型安全 Agent 接口。
 *
 * <p>AiService 自动处理 ReAct 循环：
 * <ol>
 *   <li>LLM 生成 Thought（推理）</li>
 *   <li>LLM 决定调哪个 @Tool（Action：get_document_outline / read_section / search_document）</li>
 *   <li>执行 @Tool，返回 Observation</li>
 *   <li>Observation 追加到上下文，回到步骤1，直到给出最终结构化 JSON 或达到轮次上限</li>
 * </ol>
 *
 * <p>系统提示词由 {@code LangChain4jConfig} 经 systemMessageProvider 从
 * {@link ReActContextHolder} 动态注入（含用户要求与输出 schema，每次 run 不同）。
 */
public interface DocumentAnalysisAssistant {

    /**
     * 执行 ReAct 分析循环。
     *
     * @param userMessage 用户消息（用户要求 + 文档大纲）
     * @return 最终分析结论（严格 JSON）
     */
    String analyze(@UserMessage String userMessage);
}
