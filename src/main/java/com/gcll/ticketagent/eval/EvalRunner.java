package com.gcll.ticketagent.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.api.dto.ReplyType;
import com.gcll.ticketagent.api.dto.SubmitAgentRunRequest;
import com.gcll.ticketagent.domain.AgentRunStatus;
import com.gcll.ticketagent.domain.AgentStep;
import com.gcll.ticketagent.eval.adversarial.AdversarialCaseStore;
import com.gcll.ticketagent.eval.judge.EvalJudgeService;
import com.gcll.ticketagent.persistence.repository.AgentRunRepository;
import com.gcll.ticketagent.persistence.repository.ToolExecutionLogRepository;
import com.gcll.ticketagent.resilience.LlmRunStatsRecorder;
import com.gcll.ticketagent.ticket.TicketApplicationService;
import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class EvalRunner {

    static final String DEFAULT_SUITE = "eval/eval-cases.json";
    private static final String SUITE_SMOKE = "smoke";
    private static final String SUITE_CORE = "core";
    private static final String SUITE_FULL = "full";

    private static final List<String> SMOKE_CASE_IDS = List.of(
            "insufficient-info",
            "payment-callback-500",
            "mfa-consult",
            "log-tool-failure-continue",
            "payment-rule-routing"
    );
    private static final List<String> CORE_CASE_IDS = List.of(
            "insufficient-info",
            "payment-callback-500",
            "oom-killed",
            "db-connection-timeout",
            "mfa-permission",
            "data-consistency",
            "test-env-low-priority",
            "knowledge-no-hit",
            "mq-backlog",
            "cache-timeout",
            "batch-semantic-followup",
            "intermittent-slow-followup",
            "mfa-consult",
            "colloquial-payment-status",
            "consult-non-incident",
            "requirement-not-incident",
            "log-tool-failure-continue",
            "knowledge-degraded-continue",
            "gateway-504",
            "payment-rule-routing"
    );

    private static final List<String> NO_PLAN = List.of();
    private static final List<String> NO_KEYWORDS = List.of();
    private static final String NO_ISSUE_TYPE = null;

    private final TicketApplicationService ticketApplicationService;
    private final AgentRunRepository agentRunRepository;
    private final ToolExecutionLogRepository toolExecutionLogRepository;
    private final ObjectMapper objectMapper;
    private final EvalJudgeService evalJudgeService;
    private final AdversarialCaseStore adversarialCaseStore;
    private final LlmRunStatsRecorder llmRunStatsRecorder;

    public EvalRunner(
            TicketApplicationService ticketApplicationService,
            AgentRunRepository agentRunRepository,
            ToolExecutionLogRepository toolExecutionLogRepository,
            ObjectMapper objectMapper,
            EvalJudgeService evalJudgeService,
            AdversarialCaseStore adversarialCaseStore,
            LlmRunStatsRecorder llmRunStatsRecorder
    ) {
        this.ticketApplicationService = ticketApplicationService;
        this.agentRunRepository = agentRunRepository;
        this.toolExecutionLogRepository = toolExecutionLogRepository;
        this.objectMapper = objectMapper;
        this.evalJudgeService = evalJudgeService;
        this.adversarialCaseStore = adversarialCaseStore;
        this.llmRunStatsRecorder = llmRunStatsRecorder;
    }

    /**
     * 默认只跑 golden 套件（行为与历史完全一致，保护 {@code EvalRunnerTest}）。
     */
    public EvalReport run() {
        return run(false);
    }

    public EvalReport run(String suiteName) {
        return run(suiteName, false);
    }

    public EvalReport runCase(String caseId) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId must not be blank");
        }
        List<EvalCase> cases = loadCases(DEFAULT_SUITE).stream()
                .filter(evalCase -> caseId.equals(evalCase.id()))
                .toList();
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("Unsupported eval caseId: " + caseId);
        }
        return runCases(DEFAULT_SUITE + "#case:" + caseId, cases);
    }

    /**
     * 运行 Eval。{@code includeAdversarial=true} 时在 golden 套件后追加
     * {@link AdversarialCaseStore#load()} 的对抗 case（无文件时返回空，行为等价默认）。
     * 其余断言逻辑零改动。
     */
    public EvalReport run(boolean includeAdversarial) {
        return run(SUITE_FULL, includeAdversarial);
    }

    public EvalReport run(String suiteName, boolean includeAdversarial) {
        String normalizedSuite = normalizeSuite(suiteName);
        List<EvalCase> cases = new ArrayList<>(selectCases(loadCases(DEFAULT_SUITE), normalizedSuite));
        if (includeAdversarial) {
            cases.addAll(adversarialCaseStore.load());
        }
        return runCases(DEFAULT_SUITE + "#" + normalizedSuite, cases);
    }

    private EvalReport runCases(String suiteName, List<EvalCase> cases) {
        int passed = 0;
        List<String> failures = new ArrayList<>();
        List<EvalCaseResult> caseResults = new ArrayList<>();
        Map<String, Integer> failureByAssertion = new LinkedHashMap<>();
        Map<String, GroupCounter> groups = new LinkedHashMap<>();
        List<CaseMetric> caseMetrics = new ArrayList<>();
        // judge 探活：不可用则完全跳过质量评测（不影响现有流程断言）
        boolean judgeActive = evalJudgeService.available();
        // 收集有根因输出的 case（用于 judge 评分）：caseId + 根因文本
        Map<String, String> rootCauseTexts = judgeActive ? new LinkedHashMap<>() : null;

        for (EvalCase evalCase : cases) {
            GroupCounter counter = groups.computeIfAbsent(groupName(evalCase), ignored -> new GroupCounter());
            counter.total++;
            var response = ticketApplicationService.submit(new SubmitAgentRunRequest(
                    "eval-" + evalCase.id() + "-" + UUID.randomUUID(),
                    "u1001",
                    evalCase.title(),
                    evalCase.description(),
                    "EVAL",
                    null,
                    null
            ));
            // judge 可用时，收集根因文本（只对有根因输出的 case）
            if (judgeActive && evalCase.expectRootCauseEvidence()
                    && response.analysis() != null && response.analysis().rootCause() != null) {
                String rootCauseJson = writeRootCauseJson(response.analysis().rootCause());
                if (rootCauseJson != null) {
                    rootCauseTexts.put(evalCase.id(), rootCauseJson);
                }
            }
            List<EvalAssertionResult> assertions = evaluateAssertions(response, evalCase);
            boolean casePassed = assertions.stream().allMatch(EvalAssertionResult::passed);
            caseResults.add(new EvalCaseResult(
                    evalCase.id(),
                    groupName(evalCase),
                    response.runId(),
                    casePassed,
                    assertions,
                    auditSteps(response.runId())
            ));
            caseMetrics.add(buildCaseMetric(response.runId(), evalCase, casePassed));
            if (casePassed) {
                passed++;
                counter.passed++;
            } else {
                failures.add(formatFailure(evalCase, response, assertions));
                collectFailedAssertions(assertions, failureByAssertion);
            }
        }

        List<EvalGroupReport> groupReports = groups.entrySet().stream()
                .map(entry -> new EvalGroupReport(
                        entry.getKey(),
                        entry.getValue().total,
                        entry.getValue().passed,
                        entry.getValue().total - entry.getValue().passed
                ))
                .toList();
        return new EvalReport(suiteName, cases.size(), passed, cases.size() - passed,
                groupReports, failures, failureByAssertion, caseResults, buildQualitySummary(cases, rootCauseTexts),
                buildMetricsSummary(caseMetrics), null);
    }

    private String normalizeSuite(String suiteName) {
        if (suiteName == null || suiteName.isBlank()) {
            return SUITE_FULL;
        }
        String normalized = suiteName.trim().toLowerCase();
        if (List.of(SUITE_SMOKE, SUITE_CORE, SUITE_FULL).contains(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("Unsupported eval suite: " + suiteName);
    }

    private List<EvalCase> selectCases(List<EvalCase> allCases, String suiteName) {
        return switch (suiteName) {
            case SUITE_SMOKE -> selectByIds(allCases, SMOKE_CASE_IDS);
            case SUITE_CORE -> selectByIds(allCases, CORE_CASE_IDS);
            case SUITE_FULL -> allCases;
            default -> throw new IllegalArgumentException("Unsupported eval suite: " + suiteName);
        };
    }

    private List<EvalCase> selectByIds(List<EvalCase> allCases, List<String> ids) {
        Map<String, EvalCase> byId = allCases.stream()
                .collect(java.util.stream.Collectors.toMap(EvalCase::id, c -> c, (a, b) -> a, LinkedHashMap::new));
        return ids.stream()
                .map(id -> {
                    EvalCase evalCase = byId.get(id);
                    if (evalCase == null) {
                        throw new IllegalStateException("Eval case not found: " + id);
                    }
                    return evalCase;
                })
                .toList();
    }

    private CaseMetric buildCaseMetric(String runId, EvalCase evalCase, boolean passed) {
        var run = agentRunRepository.findById(runId).orElseThrow();
        List<AgentStep> steps = run.getSteps();
        List<ToolResult> tools = toolExecutionLogRepository.findByRunId(runId);
        var llmStats = llmRunStatsRecorder.snapshot(runId);
        long runCostMs = steps.stream().mapToLong(AgentStep::getCostMs).sum();
        boolean ragSearch = steps.stream().anyMatch(step -> "KNOWLEDGE_SEARCH".equals(step.getStepName()));
        boolean ragDegraded = steps.stream()
                .filter(step -> "KNOWLEDGE_SEARCH".equals(step.getStepName()))
                .map(AgentStep::getOutputSnapshot)
                .anyMatch(output -> output != null && output.contains("degraded:"));
        int promptTokens = llmStats.calls().stream().mapToInt(call -> call.promptTokens()).sum();
        int completionTokens = llmStats.calls().stream().mapToInt(call -> call.completionTokens()).sum();
        return new CaseMetric(
                groupName(evalCase),
                passed,
                run.getStatus() == AgentRunStatus.FINAL || run.getStatus() == AgentRunStatus.WAIT_HUMAN_CONFIRM,
                run.getStatus() == AgentRunStatus.WAIT_USER_INPUT,
                run.getStatus() == AgentRunStatus.WAIT_HUMAN_CONFIRM,
                ragSearch,
                ragDegraded,
                !tools.isEmpty(),
                tools.size(),
                (int) tools.stream().filter(ToolResult::success).count(),
                (int) tools.stream().filter(result -> !result.success()).count(),
                runCostMs,
                llmStats.totalCalls(),
                llmStats.successCalls(),
                llmStats.failedCalls(),
                llmStats.totalDurationMs(),
                promptTokens,
                completionTokens
        );
    }

    private List<EvalAuditStepResult> auditSteps(String runId) {
        return agentRunRepository.findById(runId).orElseThrow().getSteps().stream()
                .map(step -> new EvalAuditStepResult(
                        step.getStepName(),
                        step.getStatus(),
                        step.isLlmUsed(),
                        step.getToolUsed(),
                        step.getCostMs(),
                        step.getInputSnapshot(),
                        step.getOutputSnapshot(),
                        step.getErrorMessage(),
                        step.getCreatedAt() == null ? null : step.getCreatedAt().toString()
                ))
                .toList();
    }

    private EvalMetricsSummary buildMetricsSummary(List<CaseMetric> metrics) {
        if (metrics.isEmpty()) {
            return EvalMetricsSummary.empty();
        }
        int totalCases = metrics.size();
        int passedCases = (int) metrics.stream().filter(CaseMetric::passed).count();
        int totalToolCalls = metrics.stream().mapToInt(CaseMetric::totalToolCalls).sum();
        int successfulToolCalls = metrics.stream().mapToInt(CaseMetric::successfulToolCalls).sum();
        List<Long> runCosts = metrics.stream().map(CaseMetric::runCostMs).sorted().toList();
        Map<String, Integer> casesByScenarioType = new LinkedHashMap<>();
        metrics.forEach(metric -> casesByScenarioType.merge(metric.scenarioType(), 1, Integer::sum));
        return new EvalMetricsSummary(
                totalCases,
                passedCases,
                rate(passedCases, totalCases),
                (int) metrics.stream().filter(CaseMetric::analyzed).count(),
                (int) metrics.stream().filter(CaseMetric::followUp).count(),
                (int) metrics.stream().filter(CaseMetric::humanConfirm).count(),
                (int) metrics.stream().filter(CaseMetric::ragSearch).count(),
                (int) metrics.stream().filter(CaseMetric::ragDegraded).count(),
                (int) metrics.stream().filter(CaseMetric::toolCall).count(),
                totalToolCalls,
                successfulToolCalls,
                metrics.stream().mapToInt(CaseMetric::failedToolCalls).sum(),
                rate(successfulToolCalls, totalToolCalls),
                Math.round(metrics.stream().mapToLong(CaseMetric::runCostMs).average().orElse(0.0)),
                percentile95(runCosts),
                metrics.stream().mapToInt(CaseMetric::totalLlmCalls).sum(),
                metrics.stream().mapToInt(CaseMetric::successfulLlmCalls).sum(),
                metrics.stream().mapToInt(CaseMetric::failedLlmCalls).sum(),
                metrics.stream().mapToLong(CaseMetric::totalLlmDurationMs).sum(),
                metrics.stream().mapToInt(CaseMetric::promptTokens).sum(),
                metrics.stream().mapToInt(CaseMetric::completionTokens).sum(),
                Collections.unmodifiableMap(casesByScenarioType)
        );
    }

    private double rate(int numerator, int denominator) {
        if (denominator == 0) {
            return 0.0;
        }
        return Math.round((numerator * 10000.0 / denominator)) / 100.0;
    }

    private long percentile95(List<Long> sortedValues) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(sortedValues.size() * 0.95) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private record CaseMetric(
            String scenarioType,
            boolean passed,
            boolean analyzed,
            boolean followUp,
            boolean humanConfirm,
            boolean ragSearch,
            boolean ragDegraded,
            boolean toolCall,
            int totalToolCalls,
            int successfulToolCalls,
            int failedToolCalls,
            long runCostMs,
            int totalLlmCalls,
            int successfulLlmCalls,
            int failedLlmCalls,
            long totalLlmDurationMs,
            int promptTokens,
            int completionTokens
    ) {
    }

    private void collectFailedAssertions(
            List<EvalAssertionResult> assertions,
            Map<String, Integer> failureByAssertion
    ) {
        assertions.stream()
                .filter(assertion -> !assertion.passed())
                .map(EvalAssertionResult::name)
                .forEach(name -> failureByAssertion.merge(name, 1, Integer::sum));
    }

    private List<EvalAssertionResult> evaluateAssertions(
            com.gcll.ticketagent.api.dto.AgentRunResponse response,
            EvalCase evalCase
    ) {
        List<EvalAssertionResult> assertions = new ArrayList<>();
        assertions.add(assertion(
                "replyType",
                response.replyType() == evalCase.expectedReplyType(),
                String.valueOf(evalCase.expectedReplyType()),
                String.valueOf(response.replyType()),
                "Response type should match the case expectation."
        ));
        assertions.add(assertion(
                "status",
                response.status() == evalCase.expectedStatus(),
                String.valueOf(evalCase.expectedStatus()),
                String.valueOf(response.status()),
                "Run status should match the case expectation."
        ));
        if (evalCase.expectedPriority() != null) {
            String actualPriority = response.analysis() == null ? null : response.analysis().ticket().priority();
            assertions.add(assertion(
                    "priority",
                    evalCase.expectedPriority().equals(actualPriority),
                    evalCase.expectedPriority(),
                    String.valueOf(actualPriority),
                    "Ticket priority should match the case expectation."
            ));
        }
        if (evalCase.maxQuestions() > 0) {
            int questionCount = response.questions() == null ? 0 : response.questions().size();
            assertions.add(assertion(
                    "maxQuestions",
                    response.questions() != null && questionCount <= evalCase.maxQuestions(),
                    "<= " + evalCase.maxQuestions(),
                    String.valueOf(questionCount),
                    "Follow-up question count should stay within the configured limit."
            ));
        }
        if (evalCase.needHumanConfirm()) {
            boolean actual = response.analysis() != null && response.analysis().humanConfirm().required();
            assertions.add(assertion(
                    "humanConfirm",
                    actual,
                    "required=true",
                    "required=" + actual,
                    "Human confirmation should be required for this case."
            ));
        }
        assertions.add(assertion(
                "steps",
                stepExpectationPassed(response.runId(), evalCase),
                stepExpectationSummary(evalCase),
                actualSteps(response.runId()).toString(),
                "Required workflow steps should appear, and skipped steps should stay absent."
        ));
        assertions.add(assertion(
                "knowledgeDegraded",
                knowledgeDegradedExpectationPassed(response.runId(), evalCase),
                String.valueOf(evalCase.expectKnowledgeDegraded()),
                actualKnowledgeDegraded(response.runId()),
                "Knowledge degraded marker should match the case expectation."
        ));
        assertions.add(assertion(
                "plan",
                planExpectationPassed(response.runId(), evalCase),
                "actions=" + evalCase.expectPlanActions() + ", skipped=" + evalCase.expectSkipPlanActions(),
                actualPlanOutput(response.runId()),
                "Planner output should include expected actions and skipped actions."
        ));
        assertions.add(assertion(
                "toolLog",
                toolExpectationPassed(response.runId(), evalCase),
                "expectToolLog=" + evalCase.expectToolLog(),
                actualToolNames(response.runId()).toString(),
                "Tool execution log presence should match the case expectation."
        ));
        assertions.add(assertion(
                "selectedTools",
                selectedToolExpectationPassed(response.runId(), evalCase),
                evalCase.expectSelectedTools().toString(),
                actualToolNames(response.runId()).toString(),
                "Expected tools should be executed."
        ));
        assertions.add(assertion(
                "failedTools",
                failedToolExpectationPassed(response.runId(), evalCase),
                evalCase.expectFailedTools().toString(),
                actualFailedTools(response.runId()).toString(),
                "Expected failed tools should be recorded as failed."
        ));
        assertions.add(assertion(
                "rootCauseEvidence",
                rootCauseExpectationPassed(response, evalCase),
                "expectRootCauseEvidence=" + evalCase.expectRootCauseEvidence(),
                actualRootCauseEvidence(response),
                "Root cause evidence should be present when required."
        ));
        assertions.add(assertion(
                "semanticFollowUp",
                semanticFollowUpPassed(response, evalCase),
                "keywords=" + evalCase.questionKeywords(),
                response.questions() == null ? "[]" : response.questions().toString(),
                "Follow-up questions should include one of the semantic keywords when required."
        ));
        assertions.add(assertion(
                "issueType",
                issueTypeExpectationPassed(response, evalCase),
                String.valueOf(evalCase.expectedIssueType()),
                actualIssueType(response),
                "Issue type should match the case expectation when configured."
        ));
        return assertions;
    }

    private EvalAssertionResult assertion(String name, boolean passed, String expected, String actual, String message) {
        return new EvalAssertionResult(name, passed, expected, actual, message);
    }

    private String formatFailure(
            EvalCase evalCase,
            com.gcll.ticketagent.api.dto.AgentRunResponse response,
            List<EvalAssertionResult> assertions
    ) {
        List<String> failedAssertions = assertions.stream()
                .filter(assertion -> !assertion.passed())
                .map(assertion -> assertion.name()
                        + "(expected=" + assertion.expected()
                        + ", actual=" + truncate(assertion.actual(), 160) + ")")
                .toList();
        return evalCase.id() + " failed: " + String.join("; ", failedAssertions)
                + ", replyType=" + response.replyType()
                + ", status=" + response.status();
    }

    /**
     * 构造质量评测汇总。judge 不可用时返回 skipped；否则对收集到的根因文本逐个调 judge 打分。
     * judge 失败的 case 记 score=null，不计入平均分（降级）。
     */
    private EvalQualitySummary buildQualitySummary(List<EvalCase> cases, Map<String, String> rootCauseTexts) {
        if (rootCauseTexts == null || rootCauseTexts.isEmpty()) {
            return new EvalQualitySummary(true, 0.0, 0, List.of());
        }
        Map<String, EvalCase> caseById = cases.stream()
                .collect(java.util.stream.Collectors.toMap(EvalCase::id, c -> c, (a, b) -> a));
        List<EvalQualitySummary.CaseScore> scores = new ArrayList<>();
        double sum = 0;
        int scored = 0;
        for (Map.Entry<String, String> entry : rootCauseTexts.entrySet()) {
            EvalCase evalCase = caseById.get(entry.getKey());
            if (evalCase == null) {
                continue;
            }
            com.gcll.ticketagent.eval.judge.EvalQualityScore score = evalJudgeService.score(evalCase, entry.getValue());
            scores.add(new EvalQualitySummary.CaseScore(entry.getKey(), score));
            if (score != null) {
                sum += score.score();
                scored++;
            }
        }
        double average = scored > 0 ? sum / scored : 0.0;
        return new EvalQualitySummary(false, average, scored, scores);
    }

    private String writeRootCauseJson(Object rootCause) {
        try {
            return objectMapper.writeValueAsString(rootCause);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<EvalCase> loadCases(String path) {
        try {
            return objectMapper.readValue(
                    new ClassPathResource(path).getInputStream(),
                    new TypeReference<List<EvalCase>>() {
                    }
            );
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load eval cases from " + path, ex);
        }
    }

    private boolean stepExpectationPassed(String runId, EvalCase evalCase) {
        var steps = actualSteps(runId);
        if (evalCase.expectGapAnalysis()) {
            // v2: 追问已改为 FOLLOW_UP_QUESTION_GENERATE + TRIAGE_PIPELINE
            if (!steps.contains("FOLLOW_UP_QUESTION_GENERATE") || !steps.contains("TRIAGE_PIPELINE")) {
                return false;
            }
        }
        if (evalCase.expectEvidence()) {
            // v2: 排查阶段策略步骤（LINEAR_INVESTIGATION / CONSULT_INVESTIGATION / REACT_INVESTIGATION）
            // 或兼容 v1 步骤名（AGENT_PLAN + TOOL_SELECTION）
            boolean investigationStepFound = steps.stream().anyMatch(s ->
                    s.contains("INVESTIGATION") || s.equals("AGENT_PLAN") || s.equals("TOOL_SELECTION"));
            if (!investigationStepFound) {
                return false;
            }
        }
        return (!evalCase.expectKnowledgeSearch() || steps.contains("KNOWLEDGE_SEARCH"))
                && (!evalCase.expectEvidence() || steps.contains("EVIDENCE_COLLECTION"))
                && (evalCase.expectKnowledgeSearch() || !steps.contains("KNOWLEDGE_SEARCH"));
    }

    private boolean planExpectationPassed(String runId, EvalCase evalCase) {
        if (evalCase.expectPlanActions().isEmpty() && evalCase.expectSkipPlanActions().isEmpty()) {
            return true;
        }
        String planOutput = actualPlanOutput(runId);
        boolean actionsOk = evalCase.expectPlanActions().stream().allMatch(planOutput::contains);
        boolean skippedOk = evalCase.expectSkipPlanActions().stream().allMatch(planOutput::contains);
        return actionsOk && skippedOk;
    }

    private boolean knowledgeDegradedExpectationPassed(String runId, EvalCase evalCase) {
        if (!evalCase.expectKnowledgeDegraded()) {
            return true;
        }
        return agentRunRepository.findById(runId).orElseThrow().getSteps().stream()
                .filter(step -> "KNOWLEDGE_SEARCH".equals(step.getStepName()))
                .map(AgentStep::getOutputSnapshot)
                .filter(output -> output != null && output.contains("degraded:"))
                .findFirst()
                .isPresent();
    }

    private boolean selectedToolExpectationPassed(String runId, EvalCase evalCase) {
        if (evalCase.expectSelectedTools().isEmpty()) {
            return true;
        }
        List<String> executed = actualToolNames(runId);
        boolean allSelectedPresent = evalCase.expectSelectedTools().stream().allMatch(executed::contains);
        boolean similarSkipped = !evalCase.expectSkipPlanActions().contains("SIMILAR_CASE_SEARCH")
                || !executed.contains("searchSimilarCases");
        return allSelectedPresent && similarSkipped;
    }

    private boolean failedToolExpectationPassed(String runId, EvalCase evalCase) {
        if (evalCase.expectFailedTools().isEmpty()) {
            return true;
        }
        List<ToolResult> executed = toolExecutionLogRepository.findByRunId(runId);
        return evalCase.expectFailedTools().stream().allMatch(expected ->
                executed.stream().anyMatch(result ->
                        expected.equals(result.toolName()) && !result.success()));
    }

    private boolean semanticFollowUpPassed(com.gcll.ticketagent.api.dto.AgentRunResponse response, EvalCase evalCase) {
        if (!evalCase.expectSemanticFollowUp()) {
            return true;
        }
        if (response.questions() == null || response.questions().isEmpty()) {
            return false;
        }
        String joined = String.join(" ", response.questions());
        return evalCase.questionKeywords().stream().anyMatch(joined::contains);
    }

    private boolean toolExpectationPassed(String runId, EvalCase evalCase) {
        if (!evalCase.expectToolLog()) {
            return toolExecutionLogRepository.findByRunId(runId).isEmpty();
        }
        return !toolExecutionLogRepository.findByRunId(runId).isEmpty();
    }

    private boolean issueTypeExpectationPassed(com.gcll.ticketagent.api.dto.AgentRunResponse response, EvalCase evalCase) {
        if (evalCase.expectedIssueType() == null) {
            return true;
        }
        return evalCase.expectedIssueType().equals(actualIssueType(response));
    }

    private boolean rootCauseExpectationPassed(com.gcll.ticketagent.api.dto.AgentRunResponse response, EvalCase evalCase) {
        if (!evalCase.expectRootCauseEvidence()) {
            return true;
        }
        return response.analysis() != null
                && response.analysis().rootCause() != null
                && response.analysis().rootCause().evidence() != null
                && !response.analysis().rootCause().evidence().isEmpty();
    }

    private String groupName(EvalCase evalCase) {
        return evalCase.scenarioType() == null || evalCase.scenarioType().isBlank()
                ? "default"
                : evalCase.scenarioType();
    }

    private List<String> actualSteps(String runId) {
        return agentRunRepository.findById(runId).orElseThrow().getSteps().stream()
                .map(AgentStep::getStepName)
                .toList();
    }

    private String actualPlanOutput(String runId) {
        return agentRunRepository.findById(runId).orElseThrow().getSteps().stream()
                .filter(step -> "AGENT_PLAN".equals(step.getStepName()))
                .map(AgentStep::getOutputSnapshot)
                .findFirst()
                .orElse("");
    }

    private String actualKnowledgeDegraded(String runId) {
        boolean degraded = agentRunRepository.findById(runId).orElseThrow().getSteps().stream()
                .filter(step -> "KNOWLEDGE_SEARCH".equals(step.getStepName()))
                .map(AgentStep::getOutputSnapshot)
                .anyMatch(output -> output != null && output.contains("degraded:"));
        return String.valueOf(degraded);
    }

    private List<String> actualToolNames(String runId) {
        return toolExecutionLogRepository.findByRunId(runId).stream()
                .map(ToolResult::toolName)
                .toList();
    }

    private List<String> actualFailedTools(String runId) {
        return toolExecutionLogRepository.findByRunId(runId).stream()
                .filter(result -> !result.success())
                .map(ToolResult::toolName)
                .toList();
    }

    private String actualRootCauseEvidence(com.gcll.ticketagent.api.dto.AgentRunResponse response) {
        if (response.analysis() == null || response.analysis().rootCause() == null
                || response.analysis().rootCause().evidence() == null) {
            return "[]";
        }
        return response.analysis().rootCause().evidence().toString();
    }

    private String actualIssueType(com.gcll.ticketagent.api.dto.AgentRunResponse response) {
        if (response.analysis() == null || response.analysis().ticket() == null) {
            return null;
        }
        return response.analysis().ticket().issueType();
    }

    private String stepExpectationSummary(EvalCase evalCase) {
        return "gap=" + evalCase.expectGapAnalysis()
                + ", knowledge=" + evalCase.expectKnowledgeSearch()
                + ", evidence=" + evalCase.expectEvidence();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static final class GroupCounter {
        private int total;
        private int passed;
    }
}
