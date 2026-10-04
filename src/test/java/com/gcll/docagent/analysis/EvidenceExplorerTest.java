package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.parsing.ParsedDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvidenceExplorerTest {

    private final EvidenceExplorer explorer = new EvidenceExplorer(null, new ObjectMapper());
    private final ParsedDocument document = new ParsedDocument("resume.md", "markdown", List.of(
            new DocSection("sec-1", "工作经历", "负责支付系统重构，故障率从 2% 降到 0.5%", null)));

    @Test
    void keepsOnlyQuotesAnchoredToExistingSection() {
        EvidenceExplorer.ExploreResult result = explorer.parse("""
                {"sufficient":true,"evidence":[
                  {"sectionId":"sec-1","quote":"故障率从 2% 降到 0.5%"},
                  {"sectionId":"sec-9","quote":"不存在的经历"},
                  {"sectionId":"sec-1","quote":"模型编造的结果"}
                ]}
                """, document);

        assertThat(result.sufficient()).isTrue();
        assertThat(result.evidence()).containsExactly(
                new AnalysisResult.Citation("sec-1", "故障率从 2% 降到 0.5%"));
    }

    @Test
    void acceptsJsonFenceAndWhitespaceDifferences() {
        EvidenceExplorer.ExploreResult result = explorer.parse(
                "```json\n{\"sufficient\":true,\"evidence\":[{\"sectionId\":\"sec-1\",\"quote\":\"负责支付系统重构，故障率从2%降到0.5%\"}]}\n```",
                document);

        assertThat(result.evidence()).hasSize(1);
    }

    @Test
    void invalidOrEmptyModelOutputBecomesInsufficient() {
        assertThat(explorer.parse("not-json", document).sufficient()).isFalse();
        assertThat(explorer.parse("{\"sufficient\":false,\"evidence\":[]}", document).evidence())
                .isEmpty();
    }
}
