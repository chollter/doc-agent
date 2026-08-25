package com.gcll.docagent.analysis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Skill 注册表——当前内置两个垂直技能，共用同一执行引擎与平台层。
 * <p>技能差异只有三样东西：系统提示词、工具集、默认指令——
 * 解析/降级链/trace/SSE/持久化全部复用。
 */
@Component
public class SkillRegistry {

    public static final String DEFAULT_SKILL = "document-analysis";

    private final Map<String, SkillDefinition> skills = new LinkedHashMap<>();

    public SkillRegistry() {
        register(new SkillDefinition(
                DEFAULT_SKILL,
                "文档分析",
                loadPrompt("document-analysis-react.txt"),
                "document-analysis.txt",
                "提炼核心内容、风险与建议",
                List.of("get_document_outline", "read_section", "search_document", "export_report")));

        register(new SkillDefinition(
                "resume-review",
                "简历审查",
                loadPrompt("resume-review-react.txt"),
                "resume-review.txt",
                "提炼这份简历的亮点，并给出针对性的改进建议",
                List.of("get_document_outline", "read_section", "search_document", "export_report")));

        // 导出工具是 DANGER 级：仅在提示词明确指引的技能中暴露，调用时被人工确认门控拦截
    }

    private void register(SkillDefinition skill) {
        skills.put(skill.name(), skill);
    }

    public Optional<SkillDefinition> find(String name) {
        if (name == null || name.isBlank()) {
            return Optional.of(skills.get(DEFAULT_SKILL));
        }
        return Optional.ofNullable(skills.get(name.trim()));
    }

    public SkillDefinition defaultSkill() {
        return skills.get(DEFAULT_SKILL);
    }

    public List<SkillDefinition> list() {
        return List.copyOf(skills.values());
    }

    private static String loadPrompt(String file) {
        try {
            return new ClassPathResource("prompts/" + file).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Skill prompt missing: " + file, ex);
        }
    }
}
