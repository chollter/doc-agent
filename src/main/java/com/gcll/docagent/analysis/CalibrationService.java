package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.api.BusinessException;
import com.gcll.docagent.api.ErrorCode;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * 校准对照服务——"同一份简历，v1 打 53、v2 打 75"的结构化证据。
 * <p>回答三个问题：分数从多少到多少（legacyOverall）、是哪个角度变了
 * （分角度档位并排）、变好的角度有没有以变坏的角度为代价（IMPROVED/REGRESSED 并列）。
 * <p>配对缺陷注入评测保证这个提升不可能来自无差别放水（缺陷版仍须严格更差），
 * 本服务负责把"判断变准了"呈现为可复核的 diff。
 */
@Service
public class CalibrationService {

    private static final Logger log = LoggerFactory.getLogger(CalibrationService.class);

    private final AgentRunRepository runRepository;
    private final ObjectMapper objectMapper;

    public CalibrationService(AgentRunRepository runRepository, ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    /** 单个 run 的漏斗快照（从 resultJson 提取，解析失败的字段为 null）。 */
    public record RunSnapshot(
            String runId,
            String promptVersion,
            String optimizationNote,
            Integer scoreOverall,
            String strengthBand,
            Integer presentationScore,
            String presentationBand,
            String matchBand,
            String matchMode,
            int highRedFlagCount,
            int coverageMet,
            int leverageCards,
            boolean degraded
    ) {
    }

    /** 一个角度的前后变化。direction: IMPROVED / REGRESSED / UNCHANGED / MISSING。 */
    public record AngleDelta(String angle, String baseline, String candidate, String direction) {
    }

    public record Comparison(
            String fileName,
            RunSnapshot baseline,
            RunSnapshot candidate,
            List<AngleDelta> deltas,
            String conclusion
    ) {
    }

    /** 按 runId 对比两个 run。 */
    public Comparison compareRuns(String runIdA, String runIdB) {
        AgentRun a = requireCompleted(runIdA);
        AgentRun b = requireCompleted(runIdB);
        return compare(a, b);
    }

    /** 同文件按版本对比：各取该版本最新的一条 COMPLETED run。 */
    public Comparison compareVersions(String fileName, String baselineVersion, String candidateVersion) {
        if (fileName == null || fileName.isBlank() || baselineVersion == null || candidateVersion == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "需要 fileName + baselineVersion + candidateVersion（或直接给 runA/runB）");
        }
        AgentRun baseline = latestCompleted(fileName, baselineVersion);
        AgentRun candidate = latestCompleted(fileName, candidateVersion);
        return compare(baseline, candidate);
    }

    private AgentRun requireCompleted(String runId) {
        AgentRun run = runRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "run 不存在: " + runId));
        if (run.getStatus() != AgentRunStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.INVALID_STATE,
                    "run " + runId + " 状态 " + run.getStatus() + "，仅 COMPLETED 可对照");
        }
        return run;
    }

    private AgentRun latestCompleted(String fileName, String promptVersion) {
        return runRepository.findAll().stream()
                .filter(r -> fileName.equals(r.getFileName()))
                .filter(r -> promptVersion.equals(r.getPromptVersion()))
                .filter(r -> r.getStatus() == AgentRunStatus.COMPLETED)
                .max(Comparator.comparing(r -> r.getCreatedAt() != null ? r.getCreatedAt() : java.time.Instant.EPOCH))
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND,
                        "找不到 " + fileName + " @promptVersion=" + promptVersion + " 的 COMPLETED run"));
    }

    private Comparison compare(AgentRun baseline, AgentRun candidate) {
        RunSnapshot a = snapshot(baseline);
        RunSnapshot b = snapshot(candidate);
        List<AngleDelta> deltas = new ArrayList<>();

        deltas.add(delta("综合分(兼容映射)", str(a.scoreOverall()), str(b.scoreOverall()),
                numericDirection(a.scoreOverall(), b.scoreOverall())));
        deltas.add(bandDelta("内容强度", a.strengthBand(), b.strengthBand(),
                bandRank("WEAK", "MIXED", "STRONG")));
        deltas.add(delta("表达分数", str(a.presentationScore()), str(b.presentationScore()),
                numericDirection(a.presentationScore(), b.presentationScore())));
        deltas.add(bandDelta("匹配档位", a.matchBand(), b.matchBand(),
                bandRank("NONE", "WEAK", "PARTIAL", "STRONG")));
        deltas.add(delta("HIGH红旗数", str(a.highRedFlagCount()), str(b.highRedFlagCount()),
                reverseNumericDirection(a.highRedFlagCount(), b.highRedFlagCount())));
        deltas.add(delta("共性要求MET数", str(a.coverageMet()), str(b.coverageMet()),
                numericDirection(a.coverageMet(), b.coverageMet())));

        List<String> improved = deltas.stream()
                .filter(d -> "IMPROVED".equals(d.direction())).map(AngleDelta::angle).toList();
        List<String> regressed = deltas.stream()
                .filter(d -> "REGRESSED".equals(d.direction())).map(AngleDelta::angle).toList();
        StringBuilder conclusion = new StringBuilder();
        if (!improved.isEmpty()) {
            conclusion.append("变准的角度：").append(String.join("、", improved)).append("。");
        }
        if (!regressed.isEmpty()) {
            conclusion.append("注意，以下角度同时变差：").append(String.join("、", regressed))
                    .append("（校准不应以破坏其他角度为代价）。");
        }
        if (conclusion.isEmpty()) {
            conclusion.append("各角度无变化——版本差异未影响该简历的判断。");
        }

        return new Comparison(Objects.requireNonNullElse(baseline.getFileName(), baseline.getId()),
                a, b, List.copyOf(deltas), conclusion.toString());
    }

    /** 从 resultJson 提取漏斗快照——字段缺失时优雅降级为 null，不抛错。 */
    private RunSnapshot snapshot(AgentRun run) {
        String json = run.getResultJson();
        String strengthBand = null;
        Integer presentationScore = null;
        String presentationBand = null;
        String matchBand = null;
        String matchMode = null;
        int highRedFlags = 0;
        int coverageMet = 0;
        int leverageCards = 0;
        boolean degraded = false;
        if (json != null && !json.isBlank()) {
            try {
                JsonNode root = objectMapper.readTree(json);
                JsonNode verdict = root.get("funnelVerdict");
                if (verdict != null && !verdict.isNull()) {
                    JsonNode strength = verdict.get("strength");
                    strengthBand = strength == null ? null : textOrNull(strength.get("band"));
                    JsonNode presentation = verdict.get("presentation");
                    if (presentation != null && !presentation.isNull()) {
                        presentationScore = presentation.has("score") ? presentation.get("score").asInt() : null;
                        presentationBand = textOrNull(presentation.get("band"));
                    }
                    matchBand = textOrNull(verdict.get("matchBand"));
                    matchMode = textOrNull(verdict.get("matchMode"));
                    degraded = verdict.has("analysisDegraded") && verdict.get("analysisDegraded").asBoolean();
                    JsonNode flags = verdict.get("redFlags");
                    if (flags != null && flags.isArray()) {
                        for (JsonNode f : flags) {
                            if (f.has("severity") && "HIGH".equals(f.get("severity").asText())) {
                                highRedFlags++;
                            }
                        }
                    }
                    JsonNode coverage = verdict.get("mustHaveCoverage");
                    int coverageTotal = 0;
                    if (coverage != null && coverage.isArray()) {
                        for (JsonNode c : coverage) {
                            coverageTotal++;
                            if (c.has("status") && "MET".equals(c.get("status").asText())) {
                                coverageMet++;
                            }
                        }
                    }
                    // matchBand 是 FunnelVerdict 的派生方法（非记录组件，不进 JSON）——按同规则计算
                    if (coverageTotal > 0) {
                        double metRate = (double) coverageMet / coverageTotal;
                        matchBand = metRate >= 0.75 ? "STRONG" : metRate >= 0.5 ? "PARTIAL"
                                : coverageMet > 0 ? "WEAK" : "NONE";
                    }
                    JsonNode cards = verdict.get("leverageCards");
                    leverageCards = cards == null ? 0 : cards.size();
                }
            } catch (Exception ex) {
                log.warn("Failed to parse resultJson for calibration snapshot, runId={}: {}",
                        run.getId(), ex.getMessage());
            }
        }
        return new RunSnapshot(run.getId(), run.getPromptVersion(), run.getOptimizationNote(),
                run.getScoreOverall(), strengthBand, presentationScore, presentationBand,
                matchBand, matchMode, highRedFlags, coverageMet, leverageCards, degraded);
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static String str(Object v) {
        return v == null ? "无数据" : String.valueOf(v);
    }

    private static AngleDelta delta(String angle, String baseline, String candidate, String direction) {
        return new AngleDelta(angle, baseline, candidate, direction);
    }

    private static AngleDelta bandDelta(String angle, String a, String b, ToIntFunction<String> rank) {
        if (a == null || b == null) {
            return delta(angle, str(a), str(b), "MISSING");
        }
        int ra = rank.applyAsInt(a);
        int rb = rank.applyAsInt(b);
        return delta(angle, a, b, rb > ra ? "IMPROVED" : rb < ra ? "REGRESSED" : "UNCHANGED");
    }

    private static String numericDirection(Integer a, Integer b) {
        if (a == null || b == null) {
            return "MISSING";
        }
        return b > a ? "IMPROVED" : b < a ? "REGRESSED" : "UNCHANGED";
    }

    /** 越小越好（红旗数）。 */
    private static String reverseNumericDirection(int a, int b) {
        return b < a ? "IMPROVED" : b > a ? "REGRESSED" : "UNCHANGED";
    }

    private static ToIntFunction<String> bandRank(String... ordered) {
        return value -> {
            for (int i = 0; i < ordered.length; i++) {
                if (ordered[i].equalsIgnoreCase(value)) {
                    return i;
                }
            }
            return -1;
        };
    }
}
