package com.gcll.docagent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.analysis.DocumentStore;
import com.gcll.docagent.analysis.DocumentAnalysisService;
import com.gcll.docagent.api.dto.AnalysisRunDtos.Detail;
import com.gcll.docagent.api.dto.HumanActionDto;
import com.gcll.docagent.api.dto.AnalysisRunDtos.DocumentView;
import com.gcll.docagent.api.dto.AnalysisRunDtos.LlmInteractionDto;
import com.gcll.docagent.api.dto.AnalysisRunDtos.OptimizationHistoryItem;
import com.gcll.docagent.api.dto.AnalysisRunDtos.Start;
import com.gcll.docagent.api.dto.AnalysisRunDtos.Summary;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.human.PendingAction;
import com.gcll.docagent.persistence.entity.LlmInteractionEntity;
import com.gcll.docagent.persistence.mapper.LlmInteractionMapper;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import com.gcll.docagent.persistence.repository.PendingActionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/analysis")
public class DocumentAnalysisController {

    private final DocumentAnalysisService analysisService;
    private final AgentRunRepository agentRunRepository;
    private final DocumentStore documentStore;
    private final ObjectMapper objectMapper;
    private final PendingActionRepository pendingActionRepository;
    private final LlmInteractionMapper interactionMapper;
    private final com.gcll.docagent.analysis.CalibrationService calibrationService;

    public DocumentAnalysisController(DocumentAnalysisService analysisService,
                                      AgentRunRepository agentRunRepository,
                                      DocumentStore documentStore,
                                      ObjectMapper objectMapper,
                                      PendingActionRepository pendingActionRepository,
                                      LlmInteractionMapper interactionMapper,
                                      com.gcll.docagent.analysis.CalibrationService calibrationService) {
        this.analysisService = analysisService;
        this.agentRunRepository = agentRunRepository;
        this.documentStore = documentStore;
        this.objectMapper = objectMapper;
        this.pendingActionRepository = pendingActionRepository;
        this.interactionMapper = interactionMapper;
        this.calibrationService = calibrationService;
    }

    /** 提交分析：同步解析建档（解析错误直接 400），异步执行（SSE/轮询获取进度）。 */
    @PostMapping(path = "/runs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Start submit(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String instruction,
            @RequestParam(required = false) String skill,
            @RequestParam(required = false) String jobDescription,
            @RequestParam(required = false) String targetDirection,
            @RequestParam(required = false) String persona,
            @RequestParam(required = false) String promptVersion,
            @RequestParam(required = false) String optimizationNote) {
        AgentRun run = analysisService.start(file, instruction, skill, jobDescription,
                targetDirection, persona, promptVersion, optimizationNote);
        return new Start(run.getId(), run.getStatus().name());
    }

    /** 历史列表（新→旧）。 */
    @GetMapping("/runs")
    public List<Summary> listRuns() {        return agentRunRepository.findAll().stream()
                .sorted(Comparator.comparing(AgentRun::getCreatedAt).reversed())
                .limit(50)
                .map(run -> new Summary(
                        run.getId(), run.getFileName(), run.getFileType(), run.getSkill(), run.getInstruction(),
                        run.getStatus().name(), run.getExecutionMode(), run.getSectionCount(),
                        run.getTokensUsed(), run.getCreatedAt(), run.getFinishedAt()))
                .toList();
    }

    /** run 详情：状态 + 结构化结果。 */
    @GetMapping("/runs/{runId}")
    public Detail getRun(@PathVariable String runId) {
        AgentRun run = requireRun(runId);
        JsonNode result = null;
        if (run.getResultJson() != null) {
            try {
                result = objectMapper.readTree(run.getResultJson());
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
                run.getId(), run.getFileName(), run.getFileType(), run.getSkill(), run.getInstruction(),
                run.getStatus().name(), run.getExecutionMode(), run.getSectionCount(),
                run.getCurrentSummary(), result, run.getLastError(),
                run.getTokensUsed(), run.getClaimedBy(), pending,
                run.getCreatedAt(), run.getFinishedAt());
    }

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
        return analysisService.getMessages(runId).stream()
                .map(m -> new com.gcll.docagent.api.dto.AnalysisRunDtos.MessageDto(
                        m.getTurn(), m.getRole(), m.getContent(), m.getCreatedAt().toString()))
                .toList();
    }

    /** 文档分节视图：右侧文档面板渲染 + 引用点击定位。缓存过期后返回 404（历史 run 的正文不再保留）。 */
    @GetMapping("/runs/{runId}/document")
    public DocumentView getDocument(@PathVariable String runId) {
        requireRun(runId);
        return documentStore.get(runId)
                .map(doc -> new DocumentView(runId, doc.fileName(), doc.fileType(),
                        doc.sections().size(),
                        doc.sections().stream()
                                .map(s -> new DocumentView.SectionDto(
                                        s.id(), s.heading(), s.page(), s.charCount(), s.text()))
                                .toList()))
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND,
                        "文档缓存已过期（服务重启或超过容量），仅保留该 run 的大纲与结果"));
    }

    private AgentRun requireRun(String runId) {
        return agentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "run 不存在: " + runId));
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
