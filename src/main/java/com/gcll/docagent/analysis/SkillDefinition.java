package com.gcll.docagent.analysis;

import java.util.List;

/**
 * 垂直技能定义——Runtime 上可插拔的分析能力单元。
 * <p>一个 Skill = 提示词 + 工具集 + 降级策略（共享 ReAct→直连→规则三级降级链）。
 * 新增技能只需在 {@link SkillRegistry} 注册，无需改动执行引擎——
 * 这是"通用 Runtime + 垂直 Skill"架构的落点。
 *
 * @param name              稳定 ID（API 参数用）
 * @param displayName       展示名
 * @param reactSystemPrompt ReAct 循环的系统提示词（含输出 JSON schema 与工具使用指引）
 * @param directPromptFile  直连 LLM 降级路径的 prompt 文件名（classpath:prompts/ 下）
 * @param defaultInstruction 未指定要求时的默认指令
 * @param toolNames         该 Skill 暴露给 ReAct 的工具名列表（按名路由到对应 ToolProvider）
 */
public record SkillDefinition(
        String name,
        String displayName,
        String reactSystemPrompt,
        String directPromptFile,
        String defaultInstruction,
        List<String> toolNames
) {
}
