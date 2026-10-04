package com.gcll.docagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 判断稳定性度量——同一用例真实跑 N 次，逐字段算跨跑一致率。
 * <p>分层口径是核心：红旗来自 RedFlagChecker（纯代码），一致率是对"可复现"这条
 * 不变量的持续证明，不是 LLM 能力；强度档/表达分/定性维度/定位锚点出自 LLM 判断，
 * 波动如实上报。<b>阈值只贴 STABLE/DRIFT 标签，原始数字永远保留——不假装有行业达标线。</b>
 * 纯函数 + 纯 record，无 Spring 无 IO，数学全部可单测钉死。
 */
public final class StabilityMeasurer {

    private StabilityMeasurer() {
    }

    /** 层标签：DETERMINISTIC=代码事实层（应恒 100%），LLM=判断层（测真实方差）。 */
    public static final String LAYER_DETERMINISTIC = "DETERMINISTIC";
    public static final String LAYER_LLM = "LLM";

    /** 单次运行的可对比快照（弱类型 resultJson 抽取后的净结果；null=该次没产出，如实参与统计）。 */
    public record RunObservation(String runId, String executionMode, boolean degraded,
                                 String strengthBand, Integer presentationScore,
                                 Map<String, String> dimensionLevels, List<String> redFlagKeys,
                                 String positioningAnchor) {
    }

    /**
     * 一个字段跨 N 跑的一致性。分类字段用众数占比；分数字段额外给极差。
     * status：runs≥2 时 agreeRate≥threshold 记 STABLE 否则 DRIFT；runs<2 不给标签（样本不够，不装样子）。
     */
    public record FieldConsistency(String field, String layer, List<String> perRun,
                                   String majority, Integer majorityCount, Double agreeRate,
                                   Integer scoreRange, String status) {
    }

    public record StabilityReport(String caseName, String file, int runs, double agreeThreshold,
                                  List<RunObservation> observations, List<FieldConsistency> fields,
                                  Instant finishedAt) {
    }

    /** 从落库 resultJson 的评测视图抽取快照。result=null（FAILED/无结果）如实产出全 null 观测。 */
    public static RunObservation observe(String runId, String executionMode, EvalRunner.ResultJson result) {
        if (result == null || result.funnelVerdict() == null) {
            return new RunObservation(runId, executionMode, true, null, null, Map.of(), List.of(), null);
        }
        EvalRunner.FunnelJson v = result.funnelVerdict();
        String band = v.strength() == null ? null : str(v.strength().get("band"));
        Integer score = v.presentation() == null || v.presentation().get("score") == null
                ? null : ((Number) v.presentation().get("score")).intValue();
        Map<String, String> dims = new TreeMap<>();
        if (v.evaluation() != null && v.evaluation().get("dimensions") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> d) {
                    String name = str(d.get("dimension"));
                    if (name != null) {
                        dims.put(name, str(d.get("level")) == null ? "ABSENT" : str(d.get("level")));
                    }
                }
            }
        }
        // 与 IterationDiffService 同一精确键：type::severity::message，不做模糊归并
        List<String> flags = new ArrayList<>();
        if (v.redFlags() != null) {
            v.redFlags().forEach(f -> flags.add(f.get("type") + "::" + f.get("severity") + "::" + f.get("message")));
        }
        String anchor = v.positioning() == null ? null : str(v.positioning().get("currentAnchor"));
        return new RunObservation(runId, executionMode, v.analysisDegraded(), band, score, dims,
                new ArrayList<>(new TreeSet<>(flags)), anchor);
    }

    /** N 次观测 → 逐字段一致率报告。 */
    public static StabilityReport measure(String caseName, String file, List<RunObservation> obs,
                                          double agreeThreshold) {
        if (obs.isEmpty()) {
            throw new IllegalArgumentException("稳定性度量至少需要 1 次运行观测");
        }
        List<FieldConsistency> fields = new ArrayList<>();
        // 代码层比对必须先排序：一致率不能依赖上游列表顺序（即便观测来自别处）
        fields.add(categorical("redFlags", LAYER_DETERMINISTIC,
                obs.stream().map(o -> o.redFlagKeys().stream().sorted()
                        .collect(java.util.stream.Collectors.joining(" | "))).toList(), agreeThreshold));
        fields.add(categorical("strengthBand", LAYER_LLM,
                obs.stream().map(o -> nz(o.strengthBand())).toList(), agreeThreshold));
        fields.add(score(obs.stream().map(RunObservation::presentationScore).toList(), agreeThreshold));
        fields.add(categorical("positioningAnchor", LAYER_LLM,
                obs.stream().map(o -> nz(o.positioningAnchor())).toList(), agreeThreshold));

        LinkedHashSet<String> dims = new LinkedHashSet<>();
        obs.forEach(o -> dims.addAll(o.dimensionLevels().keySet()));
        for (String dim : new TreeSet<>(dims)) {
            // 某跑整维缺席记 ABSENT——和 observe 内 level 缺失同口径，不把"没输出"混成 NULL
            fields.add(categorical("dimension." + dim, LAYER_LLM,
                    obs.stream().map(o -> o.dimensionLevels().getOrDefault(dim, "ABSENT")).toList(), agreeThreshold));
        }
        return new StabilityReport(caseName, file, obs.size(), agreeThreshold, List.copyOf(obs),
                List.copyOf(fields), Instant.now());
    }

    private static FieldConsistency categorical(String field, String layer, List<String> perRun,
                                                double threshold) {
        return consistency(field, layer, perRun, null, threshold);
    }

    /** 表达分：按等值算众数占比（分桶是另一件事，第一版给原始极差）。 */
    private static FieldConsistency score(List<Integer> perRunScores, double threshold) {
        List<String> asText = perRunScores.stream().map(StabilityMeasurer::nz).toList();
        List<Integer> present = perRunScores.stream().filter(java.util.Objects::nonNull).toList();
        Integer range = present.size() >= 2 ? java.util.Collections.max(present) - java.util.Collections.min(present) : null;
        return consistency("presentationScore", LAYER_LLM, asText, range, threshold);
    }

    private static FieldConsistency consistency(String field, String layer, List<String> perRun,
                                                Integer scoreRange, double threshold) {
        String majority = null;
        int majorityCount = 0;
        for (String candidate : new TreeSet<>(perRun)) {
            long count = perRun.stream().filter(candidate::equals).count();
            if (count > majorityCount) {
                majority = candidate;
                majorityCount = (int) count;
            }
        }
        double agreeRate = (double) majorityCount / perRun.size();
        String status = perRun.size() >= 2 ? (agreeRate >= threshold ? "STABLE" : "DRIFT") : null;
        return new FieldConsistency(field, layer, List.copyOf(perRun), majority, majorityCount,
                Math.round(agreeRate * 10000.0) / 10000.0, scoreRange, status);
    }

    private static String nz(Object value) {
        return value == null ? "NULL" : String.valueOf(value);
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
