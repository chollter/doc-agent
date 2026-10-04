package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 改法生成必须尊重简历已写明的约束口径：
 * 项目处于建设期/移交后的经历没有业务指标可填，建议不得索要【填：结果指标】逼用户编数字。
 */
class AlignmentAnalyzerConstraintTest {

    @Test
    void constraintPhaseEvidenceYieldsReframeNotMetricDemand() {
        String text = "TASS一体化三期 主导后端开发 使用状态机与Outbox "
                + "项目成果：主导后端完成开发与联调，通过6类异常场景验证；项目处于建设期，离职前维护工作移交团队同事。";
        AlignmentAnalyzer analyzer = analyzer();
        AlignmentEntry entry = analyzer.align(profile(), entities(text, CONSTRAINT_RESULT), text, "run-1").get(0);

        AlignmentEntry.SuggestionFix fix = entry.fix();
        assertNotNull(fix, "有证据但覆盖不足时应产出改法");
        assertEquals(AlignmentEntry.FixType.REFRAME, fix.type());
        assertFalse(fix.after().contains("结果指标"), "约束口径下不得索要结果指标");
        assertTrue(fix.after().contains("阶段"), "改法应引导写清项目阶段与移交边界");
    }

    @Test
    void evidenceWithoutConstraintStillAsksForRealMetrics() {
        String text = "Redmine知识库 负责在线检索链路 使用RRF混合检索 检索结果绑定案件号";
        AlignmentAnalyzer analyzer = analyzer();
        AlignmentEntry entry = analyzer.align(profile(), entities(text, "检索效率提升但无量化口径"), text, "run-2").get(0);

        AlignmentEntry.SuggestionFix fix = entry.fix();
        assertNotNull(fix);
        assertEquals(AlignmentEntry.FixType.ENHANCE, fix.type());
        assertTrue(fix.after().contains("【填：结果指标与具体数值】"));
    }

    private static AlignmentAnalyzer analyzer() {
        ObjectProvider<com.gcll.docagent.llm.LlmGateway> noLlm = new ObjectProvider<>() {
            @Override
            public com.gcll.docagent.llm.LlmGateway getObject() {
                throw new UnsupportedOperationException();
            }

            @Override
            public com.gcll.docagent.llm.LlmGateway getIfAvailable() {
                return null;
            }
        };
        return new AlignmentAnalyzer(new EvidenceAssessmentService(), noLlm, new ObjectMapper());
    }

    private static TargetProfile profile() {
        return new TargetProfile(TargetProfile.MODE_DIRECTION, "p", "后端", "",
                List.of(new TargetProfile.Requirement("r1", "分布式任务调度", "MUST",
                        List.of(), List.of("状态机", "检索"), false)),
                List.of(), List.of(), false);
    }

    private static final String CONSTRAINT_RESULT =
            "项目成果：主导后端完成开发与联调，通过6类异常场景验证；项目处于建设期，离职前维护工作移交团队同事。";

    private static ResumeEntities entities(String text, String resultValue) {
        return new ResumeEntities(List.of(), List.of(project(text, resultValue)));
    }

    private static ResumeProjectFact project(String text, String resultValue) {
        // results 槽位不可回锚（无 sourceQuote）——降级判定停在 L2→PARTIAL 才产出改法；
        // 但槽位值仍进 buildProjectText，约束口径检测走的是这条路（与真实抽取一致）
        return new ResumeProjectFact("proj-1", "sec-1",
                fact(firstSentence(text), text), null,
                List.of(), List.of(new ResumeProjectFact.Fact("使用状态机与Outbox或RRF混合检索", "explicit", null)),
                null, List.of(),
                new ResumeProjectFact.Fact(resultValue, "explicit", null),
                null, null);
    }

    private static ResumeProjectFact.Fact fact(String value, String source) {
        return new ResumeProjectFact.Fact(value, "explicit",
                source.contains(value) ? value : null);
    }

    private static String firstSentence(String text) {
        return text.split(" ")[0];
    }
}
