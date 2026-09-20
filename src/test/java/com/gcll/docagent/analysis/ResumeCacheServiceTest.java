package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.parsing.ParsedDocument;
import com.gcll.docagent.persistence.mapper.AnalysisCacheMapper;
import com.gcll.docagent.persistence.mapper.ResumeProfileMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 简历缓存服务的纯逻辑测试：内容哈希稳定性 + 缓存键组成 + 开关行为。
 * upsert/命中链路在 ResumeCacheIntegrationTest 里走真库验证。
 */
class ResumeCacheServiceTest {

    private static ParsedDocument doc(String text) {
        return new ParsedDocument("resume.md", "markdown",
                List.of(new DocSection("sec-1", null, text, null)));
    }

    @Test
    void contentHashIsStableAndContentSensitive() {
        String a1 = ResumeCacheService.contentHash(doc("# 同一份简历"));
        String a2 = ResumeCacheService.contentHash(doc("# 同一份简历"));
        String b = ResumeCacheService.contentHash(doc("# 改过的简历"));

        assertThat(a1).isEqualTo(a2);
        assertThat(a1).hasSize(64).isNotEqualTo(b);
    }

    @Test
    void cacheKeyChangesWithEveryAnalysisInput() {
        String hash = ResumeCacheService.contentHash(doc("# 简历"));
        String base = ResumeCacheService.buildCacheKey(
                hash, "resume-review", "按方向画像分析这份简历", null, "AI应用开发", null, "v7");

        // 同输入 → 同键（确定性）
        assertThat(ResumeCacheService.buildCacheKey(
                hash, "resume-review", "按方向画像分析这份简历", null, "AI应用开发", null, "v7"))
                .isEqualTo(base);

        // 任一分析输入变化 → 新键（换简历/技能/指令/JD/方向/人群/prompt 版本）
        assertThat(ResumeCacheService.buildCacheKey(
                ResumeCacheService.contentHash(doc("# 另一份")), "resume-review", "按方向画像分析这份简历", null, "AI应用开发", null, "v7"))
                .isNotEqualTo(base);
        assertThat(ResumeCacheService.buildCacheKey(
                hash, "document-analysis", "按方向画像分析这份简历", null, "AI应用开发", null, "v7"))
                .isNotEqualTo(base);
        assertThat(ResumeCacheService.buildCacheKey(
                hash, "resume-review", "换一个指令", null, "AI应用开发", null, "v7"))
                .isNotEqualTo(base);
        assertThat(ResumeCacheService.buildCacheKey(
                hash, "resume-review", "按方向画像分析这份简历", "5年Java后端", "AI应用开发", null, "v7"))
                .isNotEqualTo(base);
        assertThat(ResumeCacheService.buildCacheKey(
                hash, "resume-review", "按方向画像分析这份简历", null, "数据工程", null, "v7"))
                .isNotEqualTo(base);
        assertThat(ResumeCacheService.buildCacheKey(
                hash, "resume-review", "按方向画像分析这份简历", null, "AI应用开发", "SENIOR", "v7"))
                .isNotEqualTo(base);
        assertThat(ResumeCacheService.buildCacheKey(
                hash, "resume-review", "按方向画像分析这份简历", null, "AI应用开发", null, "v8"))
                .isNotEqualTo(base);
    }

    @Test
    void disabledCacheSkipsReadsAndWrites() {
        ResumeProfileMapper profileMapper = mock(ResumeProfileMapper.class);
        AnalysisCacheMapper cacheMapper = mock(AnalysisCacheMapper.class);
        ResumeCacheService service = new ResumeCacheService(
                profileMapper, cacheMapper, new ObjectMapper(), false);

        assertThat(service.cacheEnabled()).isFalse();
        // 关闭时查缓存不触库，按未命中返回
        assertThat(service.findCached("any-key")).isEmpty();
        // 关闭时完成分析不写缓存（档案写入不受影响，upsert 仍会触库）
        com.gcll.docagent.domain.AgentRun run = new com.gcll.docagent.domain.AgentRun(
                "doc-x", "t", "web", "demo-user", "outline");
        run.setResultJson("{}");
        service.storeResult(run, doc("# 简历"));

        verify(cacheMapper, never()).insert(any(com.gcll.docagent.persistence.entity.AnalysisCacheEntity.class));
        verify(cacheMapper, never()).updateById(any(com.gcll.docagent.persistence.entity.AnalysisCacheEntity.class));
    }
}
