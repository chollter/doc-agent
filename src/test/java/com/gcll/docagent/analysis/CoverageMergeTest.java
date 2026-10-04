package com.gcll.docagent.analysis;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageMergeTest {

    @Test
    void missingLlmCoverageIsBackfilledFromProfileRequirements() {
        TargetProfile profile = new TargetProfile(TargetProfile.MODE_JD, "jd-1", "AI应用工程师", null,
                List.of(
                        new TargetProfile.Requirement("rag", "有 RAG 项目经验", "MUST",
                                List.of("检索链路和上线结果"), List.of("RAG"), false),
                        new TargetProfile.Requirement("llm-integration", "有 LLM 集成经验", "IMPORTANT",
                                List.of("集成过的产品功能"), List.of("LLM"), false)),
                List.of(), List.of(), false);
        // LLM 只返回了 rag 的覆盖，llm-integration 被漏掉。
        List<MustHaveCoverage> llm = List.of(
                new MustHaveCoverage("rag", "有 RAG 项目经验", MustHaveCoverage.Status.MET,
                        "主导检索增强系统，日均调用 12 万次", "sec-2"));

        List<MustHaveCoverage> merged = CoverageMerge.merge(profile, llm);

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).requirementId()).isEqualTo("rag");
        assertThat(merged.get(0).status()).isEqualTo(MustHaveCoverage.Status.MET);
        assertThat(merged.get(0).evidence()).contains("12 万次");
        assertThat(merged.get(1).requirementId()).isEqualTo("llm-integration");
        assertThat(merged.get(1).status()).isEqualTo(MustHaveCoverage.Status.MISSING);
        assertThat(merged.get(1).evidence()).isNull();
        assertThat(merged.get(1).sectionId()).isNull();
    }

    @Test
    void unknownLlmEntriesAreKeptForCompatibility() {
        TargetProfile profile = new TargetProfile(TargetProfile.MODE_JD, "jd-1", "AI应用工程师", null,
                List.of(new TargetProfile.Requirement("rag", "有 RAG 项目经验", "MUST",
                        List.of(), List.of(), false)),
                List.of(), List.of(), false);
        List<MustHaveCoverage> llm = List.of(
                new MustHaveCoverage("rag", "有 RAG 项目经验", MustHaveCoverage.Status.PARTIAL, "做过检索", "sec-1"),
                new MustHaveCoverage("unknown-id", "画像外条目", MustHaveCoverage.Status.MET, "xx", "sec-9"));

        List<MustHaveCoverage> merged = CoverageMerge.merge(profile, llm);

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).requirementId()).isEqualTo("rag");
        assertThat(merged.get(0).status()).isEqualTo(MustHaveCoverage.Status.PARTIAL);
        // 画像外的 LLM 条目追加在末尾，不静默丢弃。
        assertThat(merged.get(1).requirementId()).isEqualTo("unknown-id");
    }

    @Test
    void nullTargetKeepsLlmCoverageUnchanged() {
        List<MustHaveCoverage> llm = List.of(
                new MustHaveCoverage("rag", "有 RAG 项目经验", MustHaveCoverage.Status.MET, "e", "s"));
        assertThat(CoverageMerge.merge(null, llm)).isSameAs(llm);
    }
}
