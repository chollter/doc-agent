package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 校准对照测试——"53→75" 的结构化证据链：
 * 分角度 diff 正确区分 IMPROVED/REGRESSED，结论同时呈现变准与变差的角度。
 */
class CalibrationServiceTest {

    private final AgentRunRepository repository = mock(AgentRunRepository.class);
    private final CalibrationService service = new CalibrationService(repository, new ObjectMapper());

    private static AgentRun completed(String id, String version, int overall, String resultJson, Instant createdAt) {
        AgentRun run = new AgentRun(id, "trace-" + id, "s", "u", "content");
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setFileName("resume.md");
        run.setPromptVersion(version);
        run.setOptimizationNote("note-" + version);
        run.setScoreOverall(overall);
        run.setResultJson(resultJson);
        run.setCreatedAt(createdAt);
        return run;
    }

    private static final String V1_JSON = """
            {"summary":"x","funnelVerdict":{
              "redFlags":[{"type":"TIMELINE_GAP","severity":"HIGH","message":"时间线有 8 个月空窗"}],
              "matchMode":"DIRECTION",
              "mustHaveCoverage":[{"requirementId":"a","status":"MET"},{"requirementId":"b","status":"PARTIAL"}],
              "strength":{"band":"WEAK"},
              "presentation":{"score":40,"band":"D"},
              "leverageCards":[],
              "analysisDegraded":false}}
            """;

    private static final String V2_JSON = """
            {"summary":"x","funnelVerdict":{
              "redFlags":[],
              "matchMode":"DIRECTION",
              "mustHaveCoverage":[{"requirementId":"a","status":"MET"},{"requirementId":"b","status":"MET"},{"requirementId":"c","status":"MET"}],
              "strength":{"band":"STRONG"},
              "presentation":{"score":78,"band":"B"},
              "leverageCards":[{"kind":"STRENGTH"}],
              "analysisDegraded":false}}
            """;

    @Test
    void shouldDiffAnglesBetweenVersions() {
        AgentRun v1 = completed("r1", "v1", 53, V1_JSON, Instant.now().minusSeconds(600));
        AgentRun v2 = completed("r2", "v2", 75, V2_JSON, Instant.now());
        when(repository.findAll()).thenReturn(List.of(v1, v2));

        CalibrationService.Comparison comparison = service.compareVersions("resume.md", "v1", "v2");

        assertThat(comparison.fileName()).isEqualTo("resume.md");
        assertThat(comparison.baseline().promptVersion()).isEqualTo("v1");
        assertThat(comparison.candidate().promptVersion()).isEqualTo("v2");

        var byAngle = comparison.deltas().stream()
                .collect(java.util.stream.Collectors.toMap(CalibrationService.AngleDelta::angle, d -> d));
        // 53→75 的每个角度都应标记为变准
        assertThat(byAngle.get("综合分(兼容映射)").direction()).isEqualTo("IMPROVED");
        assertThat(byAngle.get("内容强度")).isEqualTo(new CalibrationService.AngleDelta("内容强度", "WEAK", "STRONG", "IMPROVED"));
        assertThat(byAngle.get("表达分数").direction()).isEqualTo("IMPROVED");
        assertThat(byAngle.get("HIGH红旗数").direction()).isEqualTo("IMPROVED");
        // 覆盖 1/2 MET → 3/3 MET：匹配档位由 WEAK 计算为 STRONG（matchBand 为派生值，从覆盖计算）
        assertThat(byAngle.get("匹配档位").candidate()).isEqualTo("STRONG");
        assertThat(comparison.conclusion()).contains("变准的角度").doesNotContain("同时变差");
    }

    @Test
    void shouldExposeRegressedAnglesInConclusion() {
        // v2 分数涨了但覆盖掉了——校准不应以破坏其他角度为代价，结论必须点出
        String v2BadCoverage = """
                {"summary":"x","funnelVerdict":{
                  "redFlags":[],"matchMode":"DIRECTION",
                  "mustHaveCoverage":[{"requirementId":"a","status":"MISSING"}],
                  "strength":{"band":"STRONG"},
                  "presentation":{"score":90,"band":"A"},
                  "leverageCards":[],"analysisDegraded":false}}
                """;
        AgentRun v1 = completed("r1", "v1", 53, V1_JSON, Instant.now().minusSeconds(600));
        AgentRun v2 = completed("r2", "v2", 75, v2BadCoverage, Instant.now());
        when(repository.findAll()).thenReturn(List.of(v1, v2));

        CalibrationService.Comparison comparison = service.compareVersions("resume.md", "v1", "v2");

        assertThat(comparison.conclusion()).contains("同时变差").contains("匹配档位");
    }

    @Test
    void shouldPickLatestCompletedRunPerVersion() {
        AgentRun oldV1 = completed("r-old", "v1", 40, "{}", Instant.now().minusSeconds(9999));
        AgentRun newV1 = completed("r-new", "v1", 53, V1_JSON, Instant.now().minusSeconds(600));
        AgentRun v2 = completed("r2", "v2", 75, V2_JSON, Instant.now());
        AgentRun queued = completed("r-q", "v2", 99, "{}", Instant.now().plusSeconds(10));
        queued.setStatus(AgentRunStatus.QUEUED);
        when(repository.findAll()).thenReturn(List.of(oldV1, newV1, v2, queued));

        assertThat(service.compareVersions("resume.md", "v1", "v2").baseline().runId()).isEqualTo("r-new");
        assertThat(service.compareVersions("resume.md", "v1", "v2").candidate().runId()).isEqualTo("r2");
    }

    @Test
    void shouldCompareByRunIdsDirectly() {
        when(repository.findById("r1")).thenReturn(Optional.of(completed("r1", "v1", 53, V1_JSON, Instant.now())));
        when(repository.findById("r2")).thenReturn(Optional.of(completed("r2", "v2", 75, V2_JSON, Instant.now())));

        assertThat(service.compareRuns("r1", "r2").candidate().presentationScore()).isEqualTo(78);
    }

    @Test
    void shouldTolerateMissingFunnelVerdict() {
        when(repository.findById(anyString()))
                .thenReturn(Optional.of(completed("r1", "v1", 30, "{\"summary\":\"old style\"}", Instant.now())))
                .thenReturn(Optional.of(completed("r2", "v2", 60, "{}", Instant.now())));

        CalibrationService.Comparison comparison = service.compareRuns("r1", "r2");

        // 旧版 run 无 funnelVerdict——角度标记 MISSING 而非抛错
        assertThat(comparison.deltas()).anyMatch(d -> "MISSING".equals(d.direction()));
    }
}
