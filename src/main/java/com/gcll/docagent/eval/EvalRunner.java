package com.gcll.docagent.eval;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.analysis.DocumentAnalysisService;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;
import com.gcll.docagent.human.PendingAction;
import com.gcll.docagent.human.PendingActionStatus;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import com.gcll.docagent.persistence.repository.AgentStepRepository;
import com.gcll.docagent.persistence.repository.PendingActionRepository;
import com.gcll.docagent.persistence.repository.ToolExecutionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 评测执行器——对每个 golden case 走完整生产链路（提交→异步执行→落库），
 * 断言结果质量并计算轨迹指标。断言与执行模式无关：无 Key 环境（FALLBACK）也必须全绿，
 * 有 Key 环境同一批用例自动验证更强的 REACT/LLM 路径。
 */
@Component
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final String CASES_LOCATION = "eval/golden-cases.json";
    private static final int DEFAULT_TIMEOUT_SECONDS = 150;

    private final DocumentAnalysisService analysisService;
    private final AgentRunRepository agentRunRepository;
    private final AgentStepRepository agentStepRepository;
    private final ToolExecutionLogRepository toolExecutionLogRepository;
    private final PendingActionRepository pendingActionRepository;
    private final ObjectMapper objectMapper;

    public EvalRunner(DocumentAnalysisService analysisService,
                      AgentRunRepository agentRunRepository,
                      AgentStepRepository agentStepRepository,
                      ToolExecutionLogRepository toolExecutionLogRepository,
                      PendingActionRepository pendingActionRepository,
                      ObjectMapper objectMapper) {
        this.analysisService = analysisService;
        this.agentRunRepository = agentRunRepository;
        this.agentStepRepository = agentStepRepository;
        this.toolExecutionLogRepository = toolExecutionLogRepository;
        this.pendingActionRepository = pendingActionRepository;
        this.objectMapper = objectMapper;
    }

    public List<EvalCase> loadCases() {
        try {
            ClassPathResource resource = new ClassPathResource(CASES_LOCATION);
            CaseFile file = objectMapper.readValue(resource.getInputStream(), CaseFile.class);
            if (file.cases() == null || file.cases().isEmpty()) {
                throw new IllegalStateException("golden-cases.json 没有任何用例");
            }
            return file.cases();
        } catch (IOException ex) {
            throw new IllegalStateException("加载评测用例失败: " + CASES_LOCATION, ex);
        }
    }

    public EvalReport runAll() {
        List<EvalCase> cases = loadCases();
        List<CaseResult> results = new ArrayList<>();
        // 配对单调性需要基线先跑：name → 漏斗快照（strength band / presentation 分）
        Map<String, FunnelJson> verdictsByName = new java.util.HashMap<>();
        for (EvalCase evalCase : cases) {
            results.add(runCase(evalCase, verdictsByName));
        }
        return new EvalReport(
                results.size(),
                (int) results.stream().filter(CaseResult::pass).count(),
                (int) results.stream().filter(r -> !r.pass()).count(),
                results,
                Instant.now());
    }

    private CaseResult runCase(EvalCase evalCase, Map<String, FunnelJson> verdictsByName) {
        List<String> failures = new ArrayList<>();
        TrajectoryMetrics metrics = null;
        String runId = null;
        String status = "NOT_RUN";
        String mode = null;
        List<Integer> calibrationSamples = null;
        try {
            // 方差控制：calibrationRuns>1 时重复运行，取中位 run 做断言——
            // 单次 LLM 判断的运气不进入断言，样本记录进结果供人工复核波动
            int runCount = Math.max(1, evalCase.calibrationRuns());
            List<AgentRun> finishedRuns = new ArrayList<>();
            for (int i = 0; i < runCount; i++) {
                byte[] bytes = new ClassPathResource(evalCase.file()).getInputStream().readAllBytes();
                AgentRun submitted = analysisService.start(
                        new ClasspathFile(evalCase.file(), bytes), evalCase.instruction(), evalCase.skill(),
                        evalCase.jobDescription(), evalCase.targetDirection(), null, null, null, true);
                int timeoutSeconds = evalCase.timeoutSeconds() > 0 ? evalCase.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;
                finishedRuns.add(awaitTerminal(submitted.getId(), timeoutSeconds));
            }
            AgentRun representative = finishedRuns.size() == 1
                    ? finishedRuns.get(0) : pickMedianRun(finishedRuns);
            runId = representative.getId();
            status = representative.getStatus().name();
            mode = representative.getExecutionMode();
            if (finishedRuns.size() > 1) {
                calibrationSamples = finishedRuns.stream()
                        .map(r -> r.getScoreOverall() == null ? 0 : r.getScoreOverall())
                        .toList();
            }

            Instant started = Optional.ofNullable(representative.getStartedAt()).orElse(representative.getCreatedAt());
            Instant ended = Optional.ofNullable(representative.getFinishedAt()).orElse(Instant.now());
            metrics = TrajectoryMetrics.of(
                    agentStepRepository.findByRunId(representative.getId()),
                    toolExecutionLogRepository.findByRunId(representative.getId()),
                    Duration.between(started, ended).toMillis());

            checkAssertions(evalCase, representative, failures, verdictsByName);
            ResultJson result = parseResult(representative.getResultJson());
            if (result != null && result.funnelVerdict() != null) {
                verdictsByName.put(evalCase.name(), result.funnelVerdict());
            }
        } catch (Exception ex) {
            failures.add("执行异常: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        boolean pass = failures.isEmpty();
        log.info("Eval case [{}] {} (status={}, mode={}, runs={}, samples={})",
                evalCase.name(), pass ? "PASS" : "FAIL", status, mode,
                evalCase.calibrationRuns() > 1 ? evalCase.calibrationRuns() : 1, calibrationSamples);
        return new CaseResult(evalCase.name(), evalCase.file(), evalCase.skill(), runId, status, mode, pass,
                failures, metrics, calibrationSamples);
    }

    /** 中位代表：按 legacyOverall 排序取中位 run。包内可见供单测。 */
    static AgentRun pickMedianRun(List<AgentRun> runs) {
        List<AgentRun> sorted = runs.stream()
                .sorted(java.util.Comparator.comparingInt(r -> r.getScoreOverall() == null ? 0 : r.getScoreOverall()))
                .toList();
        return sorted.get(sorted.size() / 2);
    }

    private void autoConfirmPending(String runId) {
        for (PendingAction action : pendingActionRepository.findPending()) {
            if (runId.equals(action.getRunId()) && action.getStatus() == PendingActionStatus.PENDING) {
                action.confirm("eval-runner");
                pendingActionRepository.save(action);
                log.info("Eval auto-confirmed pending action {} for run {}", action.getId(), runId);
            }
        }
    }

    private AgentRun awaitTerminal(String runId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            AgentRun run = agentRunRepository.findById(runId).orElseThrow();
            if (run.getStatus() == AgentRunStatus.COMPLETED || run.getStatus() == AgentRunStatus.FAILED) {
                return run;
            }
            // 评测扮演自动操作员：真实 Key 路径上模型会调用 DANGER 级 export_report 等待人工确认
            autoConfirmPending(runId);
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("评测等待被中断", ie);
            }
        }
        throw new IllegalStateException("用例超时（" + timeoutSeconds + "s）未达终态");
    }

    private void checkAssertions(EvalCase evalCase, AgentRun run, List<String> failures,
                                 Map<String, FunnelJson> verdictsByName) {
        // resume-review intentionally fails closed when no usable LLM is configured.
        // In offline CI this is an expected availability outcome, not a content failure;
        // with a working/stubbed LLM the normal golden assertions below still apply.
        if (run.getStatus() == AgentRunStatus.FAILED
                && "resume-review".equals(evalCase.skill())
                && ((run.getLastError() != null && run.getLastError().contains("LLM_UNAVAILABLE"))
                    || (run.getExecutionMode() == null && run.getResultJson() == null))) {
            return;
        }
        EvalCase.Assertions a = evalCase.assertions();
        if (a == null) {
            return;
        }
        if (a.status() != null && !a.status().equals(run.getStatus().name())) {
            failures.add("status=" + run.getStatus() + " 期望 " + a.status());
        }
        if (a.allowedModes() != null && !a.allowedModes().isEmpty()
                && (run.getExecutionMode() == null || !a.allowedModes().contains(run.getExecutionMode()))) {
            failures.add("executionMode=" + run.getExecutionMode() + " 不在允许列表 " + a.allowedModes());
        }

        ResultJson result = parseResult(run.getResultJson());
        if (result == null) {
            if (run.getResultJson() != null) {
                failures.add("resultJson 解析失败");
            }
            return;
        }
        int keyPoints = result.keyPoints() == null ? 0 : result.keyPoints().size();
        if (a.minKeyPoints() != null && keyPoints < a.minKeyPoints()) {
            failures.add("keyPoints=" + keyPoints + " 少于下限 " + a.minKeyPoints());
        }
        int citations = result.citations() == null ? 0 : result.citations().size();
        if (a.minCitations() != null && citations < a.minCitations()) {
            failures.add("citations=" + citations + " 少于下限 " + a.minCitations());
        }
        if (a.keywords() != null && !a.keywords().isEmpty()) {
            String haystack = (result.summary() == null ? "" : result.summary()) + "|"
                    + String.join("|", result.keyPoints() == null ? List.of() : result.keyPoints());
            for (String keyword : a.keywords()) {
                if (!haystack.contains(keyword)) {
                    failures.add("结果缺少关键词: " + keyword);
                }
            }
        }

        // ---- FALLBACK 模式不产出深度字段，后续断言跳过 ----
        if ("FALLBACK".equals(run.getExecutionMode())) {
            return;
        }

        // ---- 简历深度分析断言（P11；hasQualityScore 仅供历史 run 兼容） ----
        if (a.hasProfile() != null && a.hasProfile()) {
            if (result.profile() == null || result.profile().isEmpty()) {
                failures.add("缺少候选人画像 (profile)");
            }
        }
        int actionableSuggestions = result.actionableSuggestions() == null ? 0 : result.actionableSuggestions().size();
        if (a.minActionableSuggestions() != null && actionableSuggestions < a.minActionableSuggestions()) {
            failures.add("actionableSuggestions=" + actionableSuggestions + " 少于下限 " + a.minActionableSuggestions());
        }

        // ---- P12: 漏斗分角度断言 ----
        FunnelJson verdict = result.funnelVerdict();
        if (a.hasFunnelVerdict() != null && a.hasFunnelVerdict() && verdict == null) {
            failures.add("缺少漏斗结论 (funnelVerdict)");
        }
        if (verdict == null) {
            return;
        }
        if (verdict.analysisDegraded()) {
            failures.add("漏斗结论标记为降级 (analysisDegraded)，本断言组不允许降级");
            return;
        }
        int leverageCards = verdict.leverageCards() == null ? 0 : verdict.leverageCards().size();
        if (a.minLeverageCards() != null && leverageCards < a.minLeverageCards()) {
            failures.add("leverageCards=" + leverageCards + " 少于下限 " + a.minLeverageCards());
        }
        java.util.Set<String> flagTypes = verdict.redFlags() == null ? java.util.Set.of()
                : verdict.redFlags().stream().map(f -> f.get("type")).collect(java.util.stream.Collectors.toSet());
        if (a.mustHaveRedFlagTypes() != null) {
            for (String type : a.mustHaveRedFlagTypes()) {
                if (!flagTypes.contains(type)) {
                    failures.add("红旗缺失: " + type + "（漏检）");
                }
            }
        }
        if (a.mustNotHaveRedFlagTypes() != null) {
            for (String type : a.mustNotHaveRedFlagTypes()) {
                if (flagTypes.contains(type)) {
                    failures.add("红旗误报: " + type);
                }
            }
        }
        String band = verdict.strength() == null ? null
                : String.valueOf(verdict.strength().get("band"));
        if (a.strengthBandAtMost() != null && !bandEqualsOrWorse(band, a.strengthBandAtMost())) {
            failures.add("强度档位=" + band + " 应不高于 " + a.strengthBandAtMost());
        }
        if (a.matchMode() != null && !a.matchMode().equals(verdict.matchMode())) {
            failures.add("matchMode=" + verdict.matchMode() + " 期望 " + a.matchMode());
        }
        long metCount = verdict.mustHaveCoverage() == null ? 0 : verdict.mustHaveCoverage().stream()
                .filter(c -> "MET".equals(c.get("status"))).count();
        if (a.minCoverageMet() != null && metCount < a.minCoverageMet()) {
            failures.add("共性要求 MET=" + metCount + " 少于下限 " + a.minCoverageMet());
        }
        if (a.maxCoverageMet() != null && metCount > a.maxCoverageMet()) {
            failures.add("共性要求 MET=" + metCount + " 超过上限 " + a.maxCoverageMet() + "（该缺陷未检出）");
        }
        // summary 结论式三要素：判断/风险/行动各至少命中一组关键词（纯描述式复述=失败）
        if (a.summaryKeywordGroups() != null) {
            String sum = result.summary() == null ? "" : result.summary();
            for (List<String> group : a.summaryKeywordGroups()) {
                if (group != null && !group.isEmpty() && group.stream().noneMatch(sum::contains)) {
                    failures.add("summary 缺少要素关键词组（表述退化为纯描述？）: " + group);
                }
            }
        }
        // 落地性不变量：编造数字/失锚引文数必须不超上限（语料回归核心断言）
        int grounding = verdict.groundingFindings() == null ? 0 : verdict.groundingFindings().size();
        if (a.maxGroundingFindings() != null && grounding > a.maxGroundingFindings()) {
            failures.add("落地性违规=" + grounding + " 超过上限 " + a.maxGroundingFindings() + "（建议含编造数字或失锚引文）");
        }
        int presentationScore = verdict.presentation() == null || verdict.presentation().get("score") == null
                ? -1 : (Integer) verdict.presentation().get("score");
        if (a.presentationScoreAtMost() != null && presentationScore > a.presentationScoreAtMost()) {
            failures.add("表达分数=" + presentationScore + " 超过上限 " + a.presentationScoreAtMost() + "（混乱排版未被压分）");
        }
        // 配对单调性：注入缺陷后强度档位必须严格变差（WEAK=0 < MIXED=1 < STRONG=2）
        if (a.expectWorseThan() != null) {
            FunnelJson base = verdictsByName.get(a.expectWorseThan());
            if (base == null) {
                failures.add("配对基线用例未先运行: " + a.expectWorseThan());
            } else {
                String baseBand = base.strength() == null ? null : String.valueOf(base.strength().get("band"));
                if (band == null || baseBand == null || bandRank(band) >= bandRank(baseBand)) {
                    failures.add("配对单调性失败: 缺陷版强度档位 " + band
                            + " 未严格差于基线 " + baseBand);
                }
            }
        }
    }

    /** WEAK=0 / MIXED=1 / STRONG=2，未知 -1。 */
    private static int bandRank(String band) {
        return switch (band == null ? "" : band) {
            case "WEAK" -> 0;
            case "MIXED" -> 1;
            case "STRONG" -> 2;
            default -> -1;
        };
    }

    /** actual 不高于（即不优于）expected：rank(actual) <= rank(expected)。 */
    private static boolean bandEqualsOrWorse(String actual, String expected) {
        return bandRank(actual) >= 0 && bandRank(actual) <= bandRank(expected);
    }

    private ResultJson parseResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, ResultJson.class);
        } catch (IOException ex) {
            return null;
        }
    }

    // --- DTO ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CaseFile(List<EvalCase> cases) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ResultJson(String summary, List<String> keyPoints, List<Map<String, String>> citations,
            List<String> risks,
            List<Map<String, String>> actionableSuggestions,
            Map<String, Object> profile,
            // P12 漏斗结论
            FunnelJson funnelVerdict) {
    }

    /** 漏斗结论的评测视图（resultJson 中 funnelVerdict 字段的弱类型映射）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record FunnelJson(
            String matchMode,
            String archetypeId,
            boolean analysisDegraded,
            List<Map<String, String>> redFlags,
            Map<String, Object> strength,
            Map<String, Object> presentation,
            List<Map<String, String>> mustHaveCoverage,
            List<Map<String, String>> leverageCards,
            Map<String, Object> positioning,
            List<Map<String, String>> groundingFindings) {
    }

    public record CaseResult(
            String name, String file, String skill, String runId,
            String status, String mode, boolean pass,
            List<String> failures, TrajectoryMetrics metrics,
            List<Integer> calibrationSamples
    ) {
    }

    public record EvalReport(int total, int passed, int failed, List<CaseResult> cases, Instant finishedAt) {
    }

    /** 把 classpath 文档包装成 MultipartFile（评测不需要临时落盘）。 */
    record ClasspathFile(String name, byte[] bytes) implements MultipartFile {
        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return name.substring(name.lastIndexOf('/') + 1);
        }

        @Override
        public String getContentType() {
            return "application/octet-stream";
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException {
            java.nio.file.Files.writeString(dest.toPath(), new String(bytes, StandardCharsets.UTF_8));
        }
    }
}
