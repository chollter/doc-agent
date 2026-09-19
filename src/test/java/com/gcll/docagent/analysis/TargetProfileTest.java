package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.llm.LlmGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class TargetProfileTest {
    @Test
    void convertsCuratedDirectionIntoUnifiedRequirements() {
        Archetype archetype = new ArchetypeRegistry(new ObjectMapper()).all().stream().findFirst().orElse(null);
        ArchetypeRegistry registry = new ArchetypeRegistry(new ObjectMapper());
        registry.load();
        archetype = registry.resolve("AI应用开发").orElseThrow();

        TargetProfile profile = TargetProfile.fromArchetype(archetype);

        assertThat(profile.mode()).isEqualTo(TargetProfile.MODE_DIRECTION);
        assertThat(profile.requirements()).extracting(TargetProfile.Requirement::id)
                .containsExactly("llm-api", "backend-3y", "prompt-eng");
        assertThat(profile.requirements().get(0).evidenceExpected()).isNotEmpty();
        assertThat(profile.variants()).isNotEmpty();
    }

    @Test
    void parsesJdRequirementsWithoutInventingMissingFields() throws Exception {
        ObjectProvider<LlmGateway> provider = mock(ObjectProvider.class);
        JobProfileExtractor extractor = new JobProfileExtractor(provider, new ObjectMapper());
        TargetProfile profile = extractor.parse("""
                {"title":"AI应用工程师","requirements":[
                  {"id":"rag","requirement":"有 RAG 项目经验","priority":"MUST",
                   "evidenceExpected":["检索链路和上线结果"],"keywords":["RAG"]}],"variants":[]}
                """);

        assertThat(profile.mode()).isEqualTo(TargetProfile.MODE_JD);
        assertThat(profile.requirements()).singleElement().satisfies(r -> {
            assertThat(r.id()).isEqualTo("rag");
            assertThat(r.priority()).isEqualTo("MUST");
            assertThat(r.evidenceExpected()).containsExactly("检索链路和上线结果");
        });
        assertThat(profile.variants()).isEmpty();
    }

    @Test
    void invalidJdResponseFallsBackWithoutRequirements() {
        assertThat(TargetProfile.jdFallback().requirements()).isEmpty();
        assertThat(TargetProfile.jdFallback().degraded()).isTrue();
    }
}
