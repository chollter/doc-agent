package com.gcll.docagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gcll.docagent.analysis.DocumentStore;
import com.gcll.docagent.analysis.DocumentAnalysisService;
import com.gcll.docagent.api.dto.AnalysisRunDtos.Detail;
import com.gcll.docagent.api.dto.HumanActionDto;
import com.gcll.docagent.api.dto.AnalysisRunDtos.DocumentView;
import com.gcll.docagent.api.dto.AnalysisRunDtos.LlmInteractionDto;
import com.gcll.docagent.api.dto.AnalysisRunDtos.OptimizationHistoryItem;
import com.gcll.docagent.api.dto.AnalysisRunDtos.RunPipelineDto;
import com.gcll.docagent.api.dto.AnalysisRunDtos.Start;
import com.gcll.docagent.api.dto.AnalysisRunDtos.Summary;
import com.gcll.docagent.api.dto.AnalysisRunDtos.ResumeItem;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentStep;
import com.gcll.docagent.human.PendingAction;
import com.gcll.docagent.persistence.entity.LlmInteractionEntity;
import com.gcll.docagent.persistence.mapper.LlmInteractionMapper;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import com.gcll.docagent.persistence.repository.AgentStepRepository;
import com.gcll.docagent.persistence.repository.PendingActionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/analysis")
public class DocumentAnalysisController {

    private final DocumentAnalysisService analysisService;
    private final AgentRunRepository agentRunRepository;
    private final DocumentStore documentStore;
    private final ObjectMapper objectMapper;
    private final PendingActionRepository pendingActionRepository;
    private final LlmInteractionMapper interactionMapper;
    private final AgentStepRepository agentStepRepository;
    private final com.gcll.docagent.analysis.ResumeCacheService resumeCacheService;
    private final com.gcll.docagent.analysis.CalibrationService calibrationService;
    private final com.gcll.docagent.analysis.SuggestionApplier suggestionApplier;
    private final com.gcll.docagent.analysis.RunMessageStore runMessageStore;
    private final com.gcll.docagent.analysis.IterationDiffService iterationDiffService;

    public DocumentAnalysisController(DocumentAnalysisService analysisService,
                                      AgentRunRepository agentRunRepository,
                                      DocumentStore documentStore,
                                      ObjectMapper objectMapper,
                                      PendingActionRepository pendingActionRepository,
                                      LlmInteractionMapper interactionMapper,
                                      AgentStepRepository agentStepRepository,
                                      com.gcll.docagent.analysis.ResumeCacheService resumeCacheService,
                                      com.gcll.docagent.analysis.CalibrationService calibrationService,
                                      com.gcll.docagent.analysis.SuggestionApplier suggestionApplier,
                                      com.gcll.docagent.analysis.RunMessageStore runMessageStore,
                                      com.gcll.docagent.analysis.IterationDiffService iterationDiffService) {
        this.analysisService = analysisService;
        this.agentRunRepository = agentRunRepository;
        this.documentStore = documentStore;
        this.objectMapper = objectMapper;
        this.pendingActionRepository = pendingActionRepository;
        this.interactionMapper = interactionMapper;
        this.agentStepRepository = agentStepRepository;
        this.resumeCacheService = resumeCacheService;
        this.calibrationService = calibrationService;
        this.suggestionApplier = suggestionApplier;
        this.runMessageStore = runMessageStore;
        this.iterationDiffService = iterationDiffService;
    }

    /** 提交分析：file/resumeId 二选一（同步解析建档，解析错误直接 400），异步执行（SSE/轮询获取进度）。
     * forceRefresh=true 跳过结论缓存，强制重跑并覆盖历史结论。 */
    @PostMapping(path = "/runs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Start submit(
            @RequestPart(name = "file", required = false) MultipartFile file,
            @RequestParam(required = false) String resumeId,
            @RequestParam(required = false) String instruction,
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) String jobDescription,
            @RequestParam(required = false) String targetDirection,
            @RequestParam(required = false) String persona,
            @RequestParam(required = false) String promptVersion,
            @RequestParam(required = false) String optimizationNote,
            @RequestParam(required = false) String baseRunId,
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh) {
        AgentRun run;
        if (file != null) {
            run = analysisService.start(file, instruction, skill, jobDescription,
                    targetDirection, persona, promptVersion, optimizationNote, forceRefresh, baseRunId);
        } else if (resumeId != null && !resumeId.isBlank()) {
            run = analysisService.startFromResume(resumeId.trim(), instruction, skill, jobDescription,
                    targetDirection, persona, promptVersion, optimizationNote, forceRefresh, baseRunId);
        } else {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "请上传简历文件，或通过 resumeId 选择历史简历");
        }
        return new Start(run.getId(), run.getStatus().name());
    }

    /** 简历档案列表（最近使用在前）——前端"历史简历"选择的数据源。 */
    @GetMapping("/resumes")
    public List<ResumeItem> listResumes() {
        return resumeCacheService.listProfiles().stream()
                .map(p -> new ResumeItem(
                        p.getId(), p.getFileName(), p.getFileType(), p.getCharCount(),
                        p.getRunCount(), p.getCreatedAt(), p.getLastUsedAt()))
                .toList();
    }

    /** 历史列表（新→旧）。 */
    @GetMapping("/runs")
    public List<Summary> listRuns() {
        return agentRunRepository.findAll().stream()
                .sorted(Comparator.comparing(AgentRun::getCreatedAt).reversed())
                .limit(50)
                .map(run -> new Summary(
                        run.getId(), run.getFileName(), run.getSkill(), run.getInstruction(),
                        run.getStatus().name(), run.getExecutionMode(), run.getCreatedAt()))
                .toList();
    }

    /** run 详情：状态 + 结构化结果。 */
    @GetMapping("/runs/{runId}")
    public Detail getRun(@PathVariable String runId) {
        AgentRun run = requireRun(runId);
        JsonNode result = null;
        if (run.getResultJson() != null) {
            try {
                result = readResultForApi(run.getResultJson());
            } catch (IOException ignored) {
                // 结果 JSON 损坏时返回 null，前端按无结果渲染
            }
        }
        List<HumanActionDto> pending = pendingActionRepository.findPending().stream()
                .filter(a -> a.getRunId().equals(runId))
                .map(a -> new HumanActionDto(a.getId(), a.getRunId(), a.getActionType().name(),
                        a.getStatus().name(), a.getPayload(), a.getReason(), a.getCreatedAt()))
                .toList();
        return new Detail(
                run.getId(), run.getFileName(), run.getSkill(), run.getInstruction(),
                run.getStatus().name(), run.getExecutionMode(),
                run.getCurrentSummary(), result, run.getLastError(),
                pending,
                run.getCreatedAt(), run.getFinishedAt());
    }

    /**
     * API 投影：funnelVerdict 下的管线中间产物不出接口——分析、评测、校准在服务内部
     * 继续使用完整模型（DB result_json 仍存全量），仅在出接口时剥除，新旧 run 一视同仁。
     */
    private JsonNode readResultForApi(String resultJson) throws IOException {
        JsonNode result = objectMapper.readTree(resultJson);
        JsonNode verdict = result.get("funnelVerdict");
        if (verdict instanceof ObjectNode verdictObj) {
            for (String field : PIPELINE_ONLY_VERDICT_FIELDS) {
                verdictObj.remove(field);
            }
        }
        return result;
    }

    /** 仅服务内部消费的字段（mustHaveCoverage 是 requirementVerdicts 的派生投影；
     *  leverageCards 是 keyPoints/risks 的代码派生源，一并剥除）。 */
    private static final List<String> PIPELINE_ONLY_VERDICT_FIELDS = List.of(
            "requirementVerdicts", "mustHaveCoverage",
            "experienceStrength", "groundingFindings", "leverageCards");

    /** 追问：向已完成的 run 追加用户消息，重回队列续跑。 */
    @org.springframework.web.bind.annotation.PostMapping("/runs/{runId}/messages")
    public org.springframework.http.ResponseEntity<Start> followUp(
            @org.springframework.web.bind.annotation.PathVariable String runId,
            @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, String> body) {
        AgentRun run = analysisService.followUp(runId, body.get("message"));
        return org.springframework.http.ResponseEntity.accepted()
                .body(new Start(run.getId(), run.getStatus().name()));
    }

    /** 追问消息列表（历史回放用）。 */
    @org.springframework.web.bind.annotation.GetMapping("/runs/{runId}/messages")
    public java.util.List<com.gcll.docagent.api.dto.AnalysisRunDtos.MessageDto> getMessages(
            @org.springframework.web.bind.annotation.PathVariable String runId) {
        return runMessageStore.getMessages(runId).stream()
                .map(m -> new com.gcll.docagent.api.dto.AnalysisRunDtos.MessageDto(
                        m.getTurn(), m.getRole(), m.getContent(), m.getCreatedAt().toString()))
                .toList();
    }

    /** 文档分节视图：右侧文档面板渲染 + 引用点击定位。
     * 三级兜底：内存 LRU → run checkpoint → 简历档案（重启/逐出后正文仍可取回）。 */
    @GetMapping("/runs/{runId}/document")
    public DocumentView getDocument(@PathVariable String runId) {
        requireRun(runId);
        return analysisService.findDocument(runId)
                .map(doc -> new DocumentView(runId, doc.fileName(), doc.fileType(),
                        doc.sections().size(),
                        doc.sections().stream()
                                .map(s -> new DocumentView.SectionDto(
                                        s.id(), s.heading(), s.page(), s.charCount(), s.text()))
                                .toList()))
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND,
                        "文档正文不存在（档案未建立且缓存已过期），仅保留该 run 的大纲与结果"));
    }

    private AgentRun requireRun(String runId) {
        return agentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "run 不存在: " + runId));
    }

    /** 采纳建议：对指定建议做 before→after 替换，返回修改稿（Markdown）与失锚明细。 */
    @PostMapping("/runs/{runId}/apply-suggestions")
    public com.gcll.docagent.analysis.AppliedRevision applySuggestions(
            @PathVariable String runId, @RequestBody ApplyRequest request) {
        return suggestionApplier.applySuggestions(runId, request.indices());
    }

    /** 采纳请求体。 */
    public record ApplyRequest(java.util.List<Integer> indices) {
    }

    // --- 优化历史与交互详情（优化证据链） ---

    /** 优化历史：按时间排序的运行列表，含评分明细 + 交互数量。 */
    @GetMapping("/optimization-history")
    public List<OptimizationHistoryItem> optimizationHistory() {
        return agentRunRepository.findAll().stream()
                .sorted(Comparator.comparing(AgentRun::getCreatedAt).reversed())
                .limit(100)
                .map(run -> {
                    int interactionCount = interactionMapper.selectCount(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LlmInteractionEntity>()
                                    .eq(LlmInteractionEntity::getRunId, run.getId())
                    ).intValue();
                    return new OptimizationHistoryItem(
                            run.getId(), run.getFileName(), run.getSkill(),
                            run.getPromptVersion(), run.getOptimizationNote(),
                            run.getScoreOverall(), run.getScoreDimensions(),
                            run.getExecutionMode(), run.getTokensUsed(),
                            interactionCount, run.getCreatedAt());
                })
                .toList();
    }

    /**
     * 校准对照（53→75 的结构化证据）：同一份简历 v1 vs v2 的漏斗角度并排 diff。
     * 传 runA/runB 直接对比两个 run；或传 fileName+baselineVersion+candidateVersion
     * 自动取各版本最新 COMPLETED run。
     */
    @GetMapping("/optimization-compare")
    public com.gcll.docagent.analysis.CalibrationService.Comparison optimizationCompare(
            @RequestParam(required = false) String runA,
            @RequestParam(required = false) String runB,
            @RequestParam(required = false) String fileName,
            @RequestParam(required = false) String baselineVersion,
            @RequestParam(required = false) String candidateVersion) {
        if (runA != null && runB != null) {
            return calibrationService.compareRuns(runA, runB);
        }
        return calibrationService.compareVersions(fileName, baselineVersion, candidateVersion);
    }

    /**
     * 迭代报告：当本次 run 显式绑定了基线（baseRunId）时，纯代码 diff 出"改了什么、改法是否落地"。
     * 未绑定基线不报错——返回 degraded 报告，前端据此提示"非真实迭代对比"。绝不自动挑基线。
     */
    @GetMapping("/runs/{runId}/iteration")
    public com.gcll.docagent.analysis.IterationReport iterationReport(@PathVariable String runId)
            throws IOException {
        AgentRun next = requireRun(runId);
        String baseRunId = next.getBaseRunId();
        if (baseRunId == null || baseRunId.isBlank() || next.getResultJson() == null) {
            return com.gcll.docagent.analysis.IterationReport.unavailable(baseRunId, runId);
        }
        AgentRun base = agentRunRepository.findById(baseRunId)
                .orElse(null);
        if (base == null || base.getResultJson() == null) {
            return com.gcll.docagent.analysis.IterationReport.unavailable(baseRunId, runId);
        }
        com.gcll.docagent.analysis.AnalysisResult baseResult =
                objectMapper.readValue(base.getResultJson(), com.gcll.docagent.analysis.AnalysisResult.class);
        com.gcll.docagent.analysis.AnalysisResult nextResult =
                objectMapper.readValue(next.getResultJson(), com.gcll.docagent.analysis.AnalysisResult.class);
        return iterationDiffService.diff(baseRunId, runId, baseResult, nextResult, next.getOriginalContent());
    }

    /**
     * 基线候选：某用户最近完成的简历分析（新→旧），供用户在再分析前手动选定"上一版"。
     * 系统不自动绑定——此处仅提供候选列表，选中后由提交时显式回传 baseRunId 才建立血缘。
     */
    @GetMapping("/baseline-candidates")
    public List<BaselineCandidate> baselineCandidates(
            @RequestParam(required = false, defaultValue = "demo-user") String userId,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        return agentRunRepository.findRecentResumeRuns(userId, limit).stream()
                .map(r -> new BaselineCandidate(r.getId(), r.getFileName(), r.getPromptVersion(),
                        r.getScoreOverall(), r.getTargetDirection(), r.getCreatedAt()))
                .toList();
    }

    /** 基线候选条目（供前端选择上一版）。 */
    public record BaselineCandidate(String runId, String fileName, String promptVersion,
                                    Integer scoreOverall, String targetDirection,
                                    java.time.Instant createdAt) {
    }

    /**
     * 单次运行链路诊断：阶段级状态条（哪里断了、为什么断）+ LLM 调用聚合。
     * 数据源：agent_step（阶段）+ llm_interaction（含失败调用）+ run 元信息。
     */
    @GetMapping("/runs/{runId}/pipeline")
    public RunPipelineDto getPipeline(@PathVariable String runId) {
        AgentRun run = requireRun(runId);
        boolean degraded = false;
        if (run.getResultJson() != null) {
            try {
                degraded = objectMapper.readTree(run.getResultJson())
                        .path("funnelVerdict").path("analysisDegraded").asBoolean(false);
            } catch (IOException ignored) {
                // 结果 JSON 损坏时降级标记不可知，保持 false
            }
        }
        List<RunPipelineDto.PipelineStage> stages = agentStepRepository.findByRunId(runId).stream()
                .sorted(Comparator.comparing(AgentStep::getStartedAt))
                .map(s -> new RunPipelineDto.PipelineStage(
                        s.getStepName(), s.getStatus(), s.getOutputSnapshot(),
                        s.getCostMs(), s.getErrorMessage()))
                .toList();
        Map<String, List<LlmInteractionEntity>> grouped = interactionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LlmInteractionEntity>()
                        .eq(LlmInteractionEntity::getRunId, runId)
        ).stream().collect(Collectors.groupingBy(LlmInteractionEntity::getCallSite));
        List<RunPipelineDto.LlmCallGroup> llmCalls = grouped.entrySet().stream()
                .map(e -> new RunPipelineDto.LlmCallGroup(e.getKey(), e.getValue().size(),
                        (int) e.getValue().stream()
                                .filter(i -> !Boolean.TRUE.equals(i.getSuccess())).count()))
                .sorted(Comparator.comparing(RunPipelineDto.LlmCallGroup::callSite))
                .toList();
        return new RunPipelineDto(run.getId(), run.getStatus().name(), run.getExecutionMode(),
                degraded, run.getLastError(), stages, llmCalls);
    }

    /** 单次运行的全部 LLM 交互详情。 */
    @GetMapping("/runs/{runId}/interactions")
    public List<LlmInteractionDto> getInteractions(@PathVariable String runId) {
        requireRun(runId);
        return interactionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LlmInteractionEntity>()
                        .eq(LlmInteractionEntity::getRunId, runId)
                        .orderByAsc(LlmInteractionEntity::getCreatedAt)
        ).stream().map(e -> new LlmInteractionDto(
                e.getId(), e.getCallSite(), e.getModel(),
                e.getPromptTokens(), e.getCompletionTokens(),
                e.getFullPrompt(), e.getFullResponse(),
                e.getDurationMs(), e.getSuccess(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : null
        )).toList();
    }
}
