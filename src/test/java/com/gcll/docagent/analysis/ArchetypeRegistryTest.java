package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方向画像注册表测试——资源加载、方向短语解析、词汇覆盖检查（含防造假护栏）。
 */
class ArchetypeRegistryTest {

    private final ArchetypeRegistry registry = new ArchetypeRegistry(new ObjectMapper());

    ArchetypeRegistryTest() {
        registry.load();
    }

    @Test
    void shouldLoadCuratedArchetypes() {
        assertThat(registry.all()).isNotEmpty();
        assertThat(registry.all().stream().map(Archetype::getId)).contains("ai-app-dev");
    }

    @Test
    void shouldResolveDirectionByAlias() {
        assertThat(registry.resolve("AI应用开发")).isPresent();
        assertThat(registry.resolve("想找 agent 开发方向")).isPresent();
        assertThat(registry.resolve("llm应用")).isPresent();
        assertThat(registry.resolve("前端开发")).isEmpty();
    }

    @Test
    void vocabularyGapShouldUpgradeSynonymToTerm() {
        Archetype arch = registry.resolve("AI应用开发").orElseThrow();
        // 简历用"检索增强"表述——建议补充术语 RAG
        var gaps = arch.findVocabularyGaps("负责检索增强文档问答系统，封装函数调用接口");

        assertThat(gaps).anySatisfy(g -> {
            assertThat(g.term()).isEqualTo("RAG");
            assertThat(g.usedSynonym()).isEqualTo("检索增强");
        });
    }

    @Test
    void vocabularyGapShouldNotSuggestUnbackedTerms() {
        // 防造假护栏：术语和同义词都没出现 → 不建议
        Archetype arch = registry.resolve("AI应用开发").orElseThrow();
        var gaps = arch.findVocabularyGaps("做了个后端系统，会用 Java 和 MySQL");

        assertThat(gaps).isEmpty();
    }

    @Test
    void vocabularyGapShouldSkipCoveredTerms() {
        Archetype arch = registry.resolve("AI应用开发").orElseThrow();
        var gaps = arch.findVocabularyGaps("基于 RAG 架构构建知识库，实现 Function Calling 工具调用");

        assertThat(gaps).noneMatch(g -> g.term().equals("RAG") || g.term().equals("Function Calling"));
    }

    @Test
    void resolveEmptyDirectionShouldBeEmpty() {
        assertThat(registry.resolve(null)).isEqualTo(Optional.empty());
        assertThat(registry.resolve("  ")).isEmpty();
    }
}
