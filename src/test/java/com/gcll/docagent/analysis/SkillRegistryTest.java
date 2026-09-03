package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRegistryTest {

    private final SkillRegistry registry = new SkillRegistry();

    @Test
    void registersMultipleSkillsSharingEngine() {
        assertThat(registry.list()).hasSize(2);
        assertThat(registry.list().stream().map(SkillDefinition::name))
                .containsExactly("document-analysis", "resume-review");
    }

    @Test
    void skillsDeclareOwnPromptAndToolset() {
        SkillDefinition doc = registry.find("document-analysis").orElseThrow();
        SkillDefinition resume = registry.find("resume-review").orElseThrow();

        assertThat(doc.displayName()).isEqualTo("文档分析");
        assertThat(resume.displayName()).isEqualTo("简历审查");
        assertThat(doc.reactSystemPrompt()).isNotEqualTo(resume.reactSystemPrompt());
        assertThat(doc.toolNames()).contains("get_document_outline", "read_section", "export_report");
        // 两个技能的工具集当前一致，但按名声明——技能可各自增删工具而不影响引擎
        assertThat(doc.toolNames()).isEqualTo(resume.toolNames());
    }

    @Test
    void blankNameFallsBackToDefaultAndUnknownRejected() {
        assertThat(registry.find(null)).contains(registry.defaultSkill());
        assertThat(registry.find("  ")).contains(registry.defaultSkill());
        assertThat(registry.find("no-such-skill")).isEmpty();
    }
}
