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
        for (EvalCase evalCase : cases) {
            results.add(runCase(evalCase));
        }
        return new EvalReport(
                results.size(),
                (int) results.stream().filter(CaseResult::pass).count(),
                (int) results.stream().filter(r -> !r.pass()).count(),
                results,
                Instant.now());
    }

    private CaseResult runCase(EvalCase evalCase) {
        List<String> failures = new ArrayList<>();
        TrajectoryMetrics metrics = null;
        String runId = null;
        String status = "NOT_RUN";
        String mode = null;
        try {
            byte[] bytes = new ClassPathResource(evalCase.file()).getInputStream().readAllBytes();
            AgentRun run = analysisService.start(
                    new ClasspathFile(evalCase.file(), bytes), evalCase.instruction(), evalCase.skill(), null);
            runId = run.getId();

            int timeoutSeconds = evalCase.timeoutSeconds() > 0 ? evalCase.timeoutSeconds() : DEFAULT_TIMEOUT_SECONDS;
            AgentRun finished = awaitTerminal(run.getId(), timeoutSeconds);
            status = finished.getStatus().name();
            mode = finished.getExecutionMode();

            Instant started = Optional.ofNullable(finished.getStartedAt()).orElse(finished.getCreatedAt());
            Instant ended = Optional.ofNullable(finished.getFinishedAt()).orElse(Instant.now());
            metrics = TrajectoryMetrics.of(
                    agentStepRepository.findByRunId(run.getId()),
                    toolExecutionLogRepository.findByRunId(run.getId()),
                    Duration.between(started, ended).toMillis());

            checkAssertions(evalCase, finished, failures);
        } catch (Exception ex) {
            failures.add("执行异常: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        boolean pass = failures.isEmpty();
        log.info("Eval case [{}] {} (status={}, mode={})", evalCase.name(), pass ? "PASS" : "FAIL", status, mode);
        return new CaseResult(evalCase.name(), evalCase.file(), evalCase.skill(), runId, status, mode, pass, failures, metrics);
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

    private void checkAssertions(EvalCase evalCase, AgentRun run, List<String> failures) {
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
    record ResultJson(String summary, List<String> keyPoints, List<Map<String, String>> citations) {
    }

    public record CaseResult(
            String name, String file, String skill, String runId,
            String status, String mode, boolean pass,
            List<String> failures, TrajectoryMetrics metrics
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
