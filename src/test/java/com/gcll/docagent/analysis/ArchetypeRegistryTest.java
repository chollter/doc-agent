package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 方向画像注册表测试——资源加载与方向短语解析。
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
        assertThat(registry.resolve("想找大模型应用方向")).isPresent();
        assertThat(registry.resolve("llm应用")).isPresent();
        assertThat(registry.resolve("前端开发")).isEmpty();
    }

    @Test
    void resolveEmptyDirectionShouldBeEmpty() {
        assertThat(registry.resolve(null)).isEqualTo(Optional.empty());
        assertThat(registry.resolve("  ")).isEmpty();
    }
}
