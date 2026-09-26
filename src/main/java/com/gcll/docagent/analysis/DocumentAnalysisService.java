package com.gcll.docagent.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.agent.AgentStepEventPublisher;
import com.gcll.docagent.api.BusinessException;
import com.gcll.docagent.api.ErrorCode;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;
import com.gcll.docagent.langchain4j.ReActContextHolder;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.loop.AgentLoop;
import com.gcll.docagent.loop.LoopCheckpointStore;
import com.gcll.docagent.loop.LoopMessage;
import com.gcll.docagent.loop.LoopState;
import com.gcll.docagent.observability.trace.TraceRecorder;
import com.gcll.docagent.observability.trace.TraceRecorderFactory;
import com.gcll.docagent.parsing.DocumentParsingService;
import com.gcll.docagent.parsing.ParsedDocument;
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import com.gcll.docagent.resilience.LlmResponse;
import com.gcll.docagent.tool.ToolExecutionHolder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;

/**
 * 文档分析编排——DB 队列驱动的多实例架构：
 * <pre>
 * start(): 同步解析 → checkpoint-0（消息+文档快照）→ run=QUEUED → 发布 RunQueuedEvent
 * RunQueueScheduler: 事件快路径即时认领 + 每秒轮询兜底（空闲容量内 CAS 认领，多实例天然互斥）→ 本实例执行
 * executeClaimed(): 统一执行路径——round=0 走 PARSE 起新循环，round>0 记 LOOP_RESUME 从断点续跑
 * 自愈循环: 超时未推进的 run 重新入队，任意实例续跑（崩溃恢复持续化，不只在启动时）
 * </pre>
 * 执行链（单次认领内）：
 * <pre>
 * REACT_ANALYZE（自研 AgentLoop：状态机 + 每轮 checkpoint + 预算硬顶）
 *   失败 → DIRECT_LLM（携带循环已读片段——降级不丢上下文）
 *     无有效模型结果 → RUN_FAILED（不生成规则摘要，避免被误当成分析报告）
 * → CITATION_VERIFY → REPORT（结果+token账单落库 + 清理 checkpoint）
 * </pre>
 */
@Service
public class DocumentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAnalysisService.class);
    private static final int WORKER_THREADS = 3;
    /** 演示模式的追问回答 */
    private static final String MOCK_FOLLOW_UP_ANSWER =
            "（演示模式）这是一条模拟回答：真实模式下会由 LLM 结合简历原文回答你的问题。"
                    + "当前分析结论为预置演示数据，全程未调用任何模型。";

    private final DocumentParsingService parsingService;
    private final DocumentStore documentStore;
    private final TraceRecorderFactory traceRecorderFactory;
    private final AgentRunRepository agentRunRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final SkillRegistry skillRegistry;
    private final AgentLoop agentLoop;
    private final LoopCheckpointStore checkpointStore;
    private final RunMessageStore runMessageStore;
    private final ResumeEntityExtractor entityExtractor;
    private final RedFlagChecker redFlagChecker;
    private final ResumeProfileBuilder profileBuilder;
    private final ArchetypeRegistry archetypeRegistry;
    private final JobProfileExtractor jobProfileExtractor;
    private final ResumeCacheService resumeCacheService;
    private final ApplicationEventPublisher eventPublisher;
    private final AgentStepEventPublisher stepEventPublisher;
    private final FunnelFieldsMapper funnelFieldsMapper;
    private final CitationVerifier citationVerifier;
    private final PromptBuilder promptBuilder;
    private final ResultAssembler resultAssembler;
    private final boolean reactEnabled;
    private final boolean mockResultEnabled;
    private final AnalysisExecutionMode executionMode;
    private final String evalReplayResource;
    private final int requeueStaleMinutes;
    private final String instanceId;
    private final String defaultPromptVersion;
    private final int reactMaxChars;

    private final ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(WORKER_THREADS, r -> {
        Thread t = new Thread(r, "doc-analysis");
        t.setDaemon(true);
        return t;
    });

    public DocumentAnalysisService(
            DocumentParsingService parsingService,
            DocumentStore documentStore,
            TraceRecorderFactory traceRecorderFactory,
            AgentRunRepository agentRunRepository,
            ObjectMapper objectMapper,
            ObjectProvider<LlmGateway> llmGatewayProvider,
            SkillRegistry skillRegistry,
            AgentLoop agentLoop,
            LoopCheckpointStore checkpointStore,
            RunMessageStore runMessageStore,
            ResumeEntityExtractor entityExtractor,
            RedFlagChecker redFlagChecker,
            ResumeProfileBuilder profileBuilder,
            ArchetypeRegistry archetypeRegistry,
            JobProfileExtractor jobProfileExtractor,
            ResumeCacheService resumeCacheService,
            ApplicationEventPublisher eventPublisher,
            AgentStepEventPublisher stepEventPublisher,
            FunnelFieldsMapper funnelFieldsMapper,
            CitationVerifier citationVerifier,
            PromptBuilder promptBuilder,
            ResultAssembler resultAssembler,
            @Value("${docagent.analysis.react-enabled:true}") boolean reactEnabled,
            @Value("${docagent.analysis.mock-result-enabled:false}") boolean mockResultEnabled,
            @Value("${docagent.analysis.execution-mode:MOCK}") String executionMode,
            @Value("${docagent.analysis.eval-replay-resource:/mock/resume-review-mock.json}") String evalReplayResource,
            @Value("${docagent.analysis.dispatcher.requeue-stale-minutes:15}") int requeueStaleMinutes,
            @Value("${docagent.prompt-version:}") String defaultPromptVersion,
            @Value("${docagent.analysis.react-max-chars:0}") int reactMaxChars,
            @Value("${server.port:0}") int port) {
        this.parsingService = parsingService;
        this.documentStore = documentStore;
        this.traceRecorderFactory = traceRecorderFactory;
        this.agentRunRepository = agentRunRepository;
        this.objectMapper = objectMapper;
        this.llmGatewayProvider = llmGatewayProvider;
        this.skillRegistry = skillRegistry;
        this.agentLoop = agentLoop;
        this.checkpointStore = checkpointStore;
        this.runMessageStore = runMessageStore;
        this.entityExtractor = entityExtractor;
        this.redFlagChecker = redFlagChecker;
        this.profileBuilder = profileBuilder;
        this.archetypeRegistry = archetypeRegistry;
        this.jobProfileExtractor = jobProfileExtractor;
        this.resumeCacheService = resumeCacheService;
        this.eventPublisher = eventPublisher;
        this.stepEventPublisher = stepEventPublisher;
        this.funnelFieldsMapper = funnelFieldsMapper;
        this.citationVerifier = citationVerifier;
        this.promptBuilder = promptBuilder;
        this.resultAssembler = resultAssembler;
        this.reactEnabled = reactEnabled;
        this.mockResultEnabled = mockResultEnabled;
        // 兼容旧配置：显式开启 mock-result-enabled 时仍保持演示行为。
        AnalysisExecutionMode parsedMode = AnalysisExecutionMode.parse(executionMode, AnalysisExecutionMode.MOCK);
        this.executionMode = mockResultEnabled ? AnalysisExecutionMode.MOCK : parsedMode;
        this.evalReplayResource = evalReplayResource;
        this.requeueStaleMinutes = requeueStaleMinutes;
        this.defaultPromptVersion = defaultPromptVersion;
        this.reactMaxChars = reactMaxChars;
        this.instanceId = "p" + port + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    /** 提交分析（上传文件）：同步解析 → 建档 → 缓存探测（命中即完成） → checkpoint-0 + 入队（QUEUED）。
     * 执行由 RunQueuedEvent 即时触发认领（快路径），RunQueueScheduler 轮询兜底。 */
    public AgentRun start(MultipartFile file, String instruction, String skillName, String jobDescription,
                          String targetDirection, String persona, String promptVersion, String optimizationNote,
                          boolean forceRefresh) {
        String fileName = file.getOriginalFilename();
        ParsedDocument doc;
        try {
            doc = parsingService.parse(fileName, file.getSize(), file.getInputStream());
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取上传文件失败: " + ex.getMessage());
        }
        return beginRun(doc, instruction, skillName, jobDescription, targetDirection,
                persona, promptVersion, optimizationNote, forceRefresh);
    }

    /** 提交分析（免上传）：从简历档案取已存简历直接进入分析流程。 */
    public AgentRun startFromResume(String resumeId, String instruction, String skillName, String jobDescription,
                                    String targetDirection, String persona, String promptVersion,
                                    String optimizationNote, boolean forceRefresh) {
        ParsedDocument doc = resumeCacheService.loadDocument(resumeId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "历史简历不存在或已失效: " + resumeId));
        return beginRun(doc, instruction, skillName, jobDescription, targetDirection,
                persona, promptVersion, optimizationNote, forceRefresh);
    }

    private AgentRun beginRun(ParsedDocument doc, String instruction, String skillName, String jobDescription,
                              String targetDirection, String persona, String promptVersion, String optimizationNote,
                              boolean forceRefresh) {
        SkillDefinition skill = skillRegistry.find(skillName)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "未知技能: " + skillName + "（可用: " + skillRegistry.list().stream().map(SkillDefinition::name).toList() + ")"));

        AgentRun run = new AgentRun(
                "doc-" + UUID.randomUUID(),
                UUID.randomUUID().toString().replace("-", ""),
                "web",
                "demo-user",
                doc.outline());
        run.setFileName(doc.fileName());
        run.setFileType(doc.fileType());
        run.setSkill(skill.name());
        run.setInstruction(instruction == null || instruction.isBlank()
                ? skill.defaultInstruction() : instruction.trim());
        if (jobDescription != null && !jobDescription.isBlank()) {
            run.setJobDescription(jobDescription.trim());
        }
        if (targetDirection != null && !targetDirection.isBlank()) {
            run.setTargetDirection(targetDirection.trim());
        }
        if (persona != null && !persona.isBlank()) {
            run.setPersona(persona.trim());
        }
        if (promptVersion != null && !promptVersion.isBlank()) {
            run.setPromptVersion(promptVersion.trim());
        } else if (defaultPromptVersion != null && !defaultPromptVersion.isBlank()) {
            // 未手填时自动带上当前 prompt 版本，保证版本链不断裂（手填优先）
            run.setPromptVersion(defaultPromptVersion.trim());
        }
        if (run.getPromptVersion() == null || run.getPromptVersion().isBlank()) {
            // 兜底提前到提交时：缓存键需要稳定的 prompt 版本（执行侧兜底保留为防御）
            run.setPromptVersion(skill.name() + "-v1");
        }
        if (optimizationNote != null && !optimizationNote.isBlank()) {
            run.setOptimizationNote(optimizationNote.trim());
        }
        run.setSectionCount(doc.sections().size());
        run.setStatus(AgentRunStatus.QUEUED);
        // 在提交阶段就记录请求模式，便于历史列表和故障排查区分 MOCK/REAL/EVAL。
        run.setExecutionMode(executionMode.name());

        // 简历建档（按内容哈希去重，不受缓存开关影响——免上传再分析依赖它）
        ResumeCacheService.ProfileRef profile = resumeCacheService.upsertProfile(doc);
        run.setContentHash(profile.contentHash());

        // 演示/回放模式：不调 LLM，直接使用固定结果（优先于缓存探测，也不污染真实结论缓存）。
        if ((executionMode == AnalysisExecutionMode.MOCK || executionMode == AnalysisExecutionMode.EVAL)
                && "resume-review".equals(skill.name())) {
            return completeFromReplay(run, skill, doc, executionMode);
        }

        // 结论缓存探测：同简历 + 同分析输入 → 直接复制历史结论，跳过整条 LLM 流水线
        if ("resume-review".equals(skill.name()) && resumeCacheService.cacheEnabled() && !forceRefresh) {
            String cacheKey = ResumeCacheService.buildCacheKey(run.getContentHash(), run.getSkill(),
                    run.getInstruction(), run.getJobDescription(), run.getTargetDirection(),
                    run.getPersona(), run.getPromptVersion());
            Optional<ResumeCacheService.CachedResult> cached = resumeCacheService.findCached(cacheKey);
            if (cached.isPresent()) {
                return completeFromCache(run, skill, doc, cached.get());
            }
        }

        // checkpoint-0 先落（消息+文档快照）——任何实例认领后都能独立执行
        checkpointStore.save(run.getId(), new LoopState(
                skill.name(),
                List.of(
                        LoopMessage.system(promptBuilder.effectiveSystemPrompt(skill)),
                        LoopMessage.user(promptBuilder.buildUserMessage(run, doc))),
                0, 0, 0, doc));
        agentRunRepository.save(run);
        documentStore.put(run.getId(), doc);
        // 入队即触发认领（快路径）——提交到执行有显式链路，轮询仅作兜底

        log.info("Analysis run queued, runId={}, skill={}, file={}, sections={}",
                run.getId(), skill.name(), doc.fileName(), doc.sections().size());
        eventPublisher.publishEvent(new RunQueuedEvent(run.getId()));
        return run;
    }

    /** 缓存命中：新 run 直接复制历史结论完成。不发布执行事件（无事可执行），链路留一条 CACHE_REUSED 审计步。 */
    private AgentRun completeFromCache(AgentRun run, SkillDefinition skill, ParsedDocument doc,
                                       ResumeCacheService.CachedResult cached) {
        run.setResultJson(cached.resultJson());
        run.setCurrentSummary(extractSummary(cached.resultJson()));
        run.setScoreOverall(cached.scoreOverall());
        run.setScoreDimensions(cached.scoreDimensions());
        run.setExecutionMode("CACHE_HIT");
        run.setTokensUsed(0L);
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setFinishedAt(Instant.now());
        // checkpoint-0 照常落库：追问与文档面板与常规 run 完全一致
        checkpointStore.save(run.getId(), new LoopState(
                skill.name(),
                List.of(
                        LoopMessage.system(promptBuilder.effectiveSystemPrompt(skill)),
                        LoopMessage.user(promptBuilder.buildUserMessage(run, doc))),
                0, 0, 0, doc));
        agentRunRepository.save(run);
        documentStore.put(run.getId(), doc);
        TraceRecorder tracer = traceRecorderFactory.create(run);
        String stepId = tracer.begin("CACHE_REUSED", null);
        tracer.recordMeta(stepId, false, "sourceRun=" + cached.sourceRunId());
        tracer.end(stepId, "命中分析缓存，复用历史结论（未调用 LLM）", null);
        log.info("Analysis run completed from cache, runId={}, sourceRun={}", run.getId(), cached.sourceRunId());
        return run;
    }

    /**
     * 演示/回放模式：预置结果立即完成——不调 LLM、不写结论缓存。
     * MOCK 面向前端展示；EVAL_REPLAY 面向可重复回放，二者在 run/trace 中明确区分。
     */
    private AgentRun completeFromReplay(AgentRun run, SkillDefinition skill, ParsedDocument doc,
                                        AnalysisExecutionMode mode) {
        String resultJson = mode == AnalysisExecutionMode.EVAL
                ? replayResultJson(evalReplayResource)
                : mockResultJson();
        run.setResultJson(resultJson);
        run.setCurrentSummary(extractSummary(resultJson));
        run.setScoreOverall(66);
        run.setExecutionMode(mode == AnalysisExecutionMode.EVAL ? "EVAL_REPLAY" : "MOCK");
        run.setTokensUsed(0L);
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setFinishedAt(Instant.now());
        checkpointStore.save(run.getId(), new LoopState(
                skill.name(),
                List.of(
                        LoopMessage.system(promptBuilder.effectiveSystemPrompt(skill)),
                        LoopMessage.user(promptBuilder.buildUserMessage(run, doc))),
                0, 0, 0, doc));
        agentRunRepository.save(run);
        documentStore.put(run.getId(), doc);
        TraceRecorder tracer = traceRecorderFactory.create(run);
        String stepId = tracer.begin(mode == AnalysisExecutionMode.EVAL ? "EVAL_REPLAY" : "MOCK_RESULT", null);
        tracer.recordMeta(stepId, false, mode == AnalysisExecutionMode.EVAL
                ? "resource=" + evalReplayResource : "preset");
        tracer.end(stepId, mode == AnalysisExecutionMode.EVAL
                ? "评测回放：返回固定结果（未调用 LLM）"
                : "演示模式：返回预置分析结论（未调用 LLM）", null);
        log.info("Analysis run completed with replay result, runId={}, mode={}", run.getId(), run.getExecutionMode());
        return run;
    }

    private String mockResultJson() {
        // 不缓存：演示数据改动应下次运行即生效，重启才刷新是纯陷阱；文件几 KB，每次读盘可忽略
        try (java.io.InputStream in = getClass().getResourceAsStream("/mock/resume-review-mock.json")) {
            if (in == null) {
                throw new IllegalStateException("演示数据缺失: /mock/resume-review-mock.json");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("读取演示数据失败: " + ex.getMessage(), ex);
        }
    }

    private String replayResultJson(String resource) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalStateException("EVAL 回放资源未配置");
        }
        try (java.io.InputStream in = getClass().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("EVAL 回放资源不存在: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("读取 EVAL 回放资源失败: " + resource, ex);
        }
    }

    private String extractSummary(String resultJson) {
        try {
            JsonNode node = objectMapper.readTree(resultJson);
            return node.hasNonNull("summary") ? node.get("summary").asText() : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /** run 文档的三级来源：内存 LRU → run checkpoint → 简历档案（按内容哈希）。 */
    public Optional<ParsedDocument> findDocument(String runId) {
        ParsedDocument cached = documentStore.get(runId).orElse(null);
        if (cached != null) {
            return Optional.of(cached);
        }
        LoopState state = checkpointStore.find(runId).orElse(null);
        if (state != null && state.document() != null) {
            return Optional.of(state.document());
        }
        AgentRun run = agentRunRepository.findById(runId).orElse(null);
        if (run != null && run.getContentHash() != null) {
            return resumeCacheService.findDocumentByHash(run.getContentHash());
        }
        return Optional.empty();
    }

    /** 人群：用户显式指定优先，否则按画像年限推断。 */
    private static Persona resolvePersona(AgentRun run, ResumeProfile profile) {
        if (run.getPersona() != null && !run.getPersona().isBlank()) {
            try {
                return Persona.valueOf(run.getPersona().trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // 非法值回落到推断
            }
        }
        int years = profile != null ? profile.yearsOfExperience() : 0;
        return Persona.infer(years, false);
    }

    /** 方向画像：JD 未提供且用户填了方向时解析（代码 alias 优先）。 */
    private Archetype resolveArchetype(AgentRun run) {
        if (run.getJobDescription() != null || run.getTargetDirection() == null) {
            return null;
        }
        return archetypeRegistry.resolve(run.getTargetDirection()).orElse(null);
    }

    private TargetProfile resolveTargetProfile(AgentRun run, Archetype archetype) {
        if (run.getJobDescription() != null && !run.getJobDescription().isBlank()) {
            return jobProfileExtractor.extract(run.getJobDescription(), run.getId()).profile();
        }
        return TargetProfile.fromArchetype(archetype);
    }

    public AgentRun followUp(String runId, String message) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "run 不存在: " + runId));
        if (run.getStatus() != AgentRunStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "run 状态 " + run.getStatus() + "，仅 COMPLETED 可追问");
        }
        if (message == null || message.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "追问内容不能为空");
        }
        LoopState state = checkpointStore.find(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE, "会话状态已过期"));
        runMessageStore.saveMessage(runId, state.turn() + 1, "USER", message.trim());
        // 演示模式：不重跑队列，直接给预置回答（run 保持 COMPLETED）
        if (mockResultEnabled && "resume-review".equals(run.getSkill())) {
            runMessageStore.saveMessage(runId, state.turn() + 1, "ASSISTANT", MOCK_FOLLOW_UP_ANSWER);
            log.info("Follow-up answered by mock, runId={}, turn={}", runId, state.turn() + 1);
            return run;
        }
        List<LoopMessage> messages = new ArrayList<>(state.messages());
        messages.add(LoopMessage.user("[追问轮] 请直接用自然语言回答以下问题，不要输出JSON格式。引用原文时用 [节ID] 标注。\n\n" + message.trim()));
        checkpointStore.save(runId, state.nextTurn(List.copyOf(messages)));
        run.setStatus(AgentRunStatus.QUEUED);
        run.setFinishedAt(null);
        agentRunRepository.save(run);
        log.info("Follow-up queued, runId={}, turn={}", runId, state.turn() + 1);
        eventPublisher.publishEvent(new RunQueuedEvent(runId));
        return run;
    }

    // --- 队列调度（由 RunQueueScheduler 驱动） ---

    /** 按空闲 worker 容量认领 QUEUED run（CAS，多实例互斥），提交本实例执行。 */
    public void claimQueuedRuns() {
        int free = WORKER_THREADS - executor.getActiveCount();
        if (free <= 0) {
            return;
        }
        List<String> candidates = agentRunRepository.findQueuedIds(free);
        for (String runId : candidates) {
            if (agentRunRepository.claim(runId, instanceId)) {
                log.info("Claimed run {}, instance={}", runId, instanceId);
                executor.submit(() -> executeClaimed(runId));
            }
        }
    }

    /** 自愈：超时未推进的 run 重新入队，由任意实例从 checkpoint 续跑。 */
    public void requeueStaleRuns() {
        Instant cutoff = Instant.now().minusSeconds(requeueStaleMinutes * 60L);
        for (AgentRun run : agentRunRepository.findAll()) {
            if (run.getStatus() != AgentRunStatus.ANALYZING
                    && run.getStatus() != AgentRunStatus.WAIT_HUMAN_CONFIRM) {
                continue;
            }
            Instant heartbeat = run.getUpdatedAt() != null ? run.getUpdatedAt() : run.getCreatedAt();
            if (heartbeat.isAfter(cutoff)) {
                continue;
            }
            if (checkpointStore.find(run.getId()).map(s -> s.document() != null).orElse(false)) {
                boolean requeued = agentRunRepository.requeue(run.getId(), run.getStatus().name());
                log.warn("Requeued stale run {} (status={}, lastUpdate={}, requeued={})",
                        run.getId(), run.getStatus(), heartbeat, requeued);
            } else {
                run.setStatus(AgentRunStatus.FAILED);
                run.setLastError("执行中断且无检查点，无法恢复");
                run.setFinishedAt(Instant.now());
                agentRunRepository.save(run);
            }
        }
    }

    // --- 认领后的统一执行路径 ---

    private void executeClaimed(String runId) {
        AgentRun run = agentRunRepository.findById(runId).orElse(null);
        if (run == null) {
            log.error("Claimed run disappeared, runId={}", runId);
            return;
        }
        LoopState state = checkpointStore.find(runId).orElse(null);
        if (state == null || state.document() == null) {
            fatal(run, runId, traceRecorderFactory.create(run),
                    new IllegalStateException("认领的 run 缺少检查点/文档快照"));
            return;
        }
        ParsedDocument doc = state.document();
        documentStore.put(runId, doc);
        SkillDefinition skill = skillRegistry.find(state.skillName()).orElseGet(skillRegistry::defaultSkill);

        TraceRecorder tracer = traceRecorderFactory.create(run);
        try {
            if (state.turn() > 0) {
                String stepId = tracer.begin("FOLLOW_UP", null);
                tracer.recordInput(stepId, "turn=" + state.turn()
                        + ", messages=" + state.messages().size());
                tracer.end(stepId, "追问轮续跑", null);
            } else if (state.round() > 0) {
                String stepId = tracer.begin("LOOP_RESUME", null);
                tracer.recordInput(stepId, "from round " + state.round()
                        + ", messages=" + state.messages().size()
                        + ", resumedBy=" + instanceId);
                tracer.end(stepId, "队列自愈：从 checkpoint 续跑", null);
            } else {
                traceParse(tracer, doc);
            }
            ResumeContext resumeCtx = prepareResumeContext(runId, run, doc, skill, state, tracer);
            analyzeAndFinish(runId, run, doc, skill, state, tracer, resumeCtx,
                    promptBuilder.buildUserMessage(run, doc, resumeCtx));
        } catch (Exception fatalEx) {
            fatal(run, runId, tracer, fatalEx);
        }
    }

    private ResumeContext prepareResumeContext(String runId, AgentRun run, ParsedDocument doc,
                                               SkillDefinition skill, LoopState state, TraceRecorder tracer) {
        if (state.turn() != 0 || state.round() != 0 || !"resume-review".equals(skill.name())) {
            return ResumeContext.empty();
        }
        String fullText = doc.sections().stream()
                .map(sec -> (sec.heading() != null && !sec.heading().isBlank()
                        ? sec.heading() + "\n" : "") + sec.text())
                .reduce((a, b) -> a + "\n" + b).orElse("");
        Archetype archetype = resolveArchetype(run);
        if (run.getJobDescription() == null || run.getJobDescription().isBlank()) {
            List<RedFlag> redFlags = redFlagChecker.checkFromText(fullText);
            TargetProfile targetProfile = resolveTargetProfile(run, archetype);
            String matchMode = targetProfile != null && TargetProfile.MODE_DIRECTION.equals(targetProfile.mode())
                    ? FunnelVerdict.MODE_DIRECTION : FunnelVerdict.MODE_NONE;
            String stepId = tracer.begin("RESUME_ORIGINAL_TEXT", null);
            tracer.recordMeta(stepId, false, "QUALITY_FIRST");
            tracer.end(stepId, "semantic extraction skipped (no JD)", null);
            return new ResumeContext(new ResumeEntities(List.of()), redFlags, null, archetype,
                    targetProfile, matchMode, false, fullText, Persona.GENERAL, true);
        }
        return prepareJdResumeContext(runId, run, doc, tracer, fullText, archetype);
    }

    private ResumeContext prepareJdResumeContext(String runId, AgentRun run, ParsedDocument doc,
                                                  TraceRecorder tracer, String fullText, Archetype archetype) {
        String extractStep = tracer.begin("ENTITY_EXTRACT", null);
        tracer.recordMeta(extractStep, true, "LLM");
        try {
            // Markdown 标题保存在 heading，抽取时必须与节 ID 一起传入。
            String extractionText = doc.sections().stream()
                    .map(sec -> "[SECTION_ID=" + sec.id() + "]\n"
                            + (sec.heading() != null && !sec.heading().isBlank()
                            ? sec.heading() + "\n" : "") + sec.text())
                    .reduce((a, b) -> a + "\n" + b).orElse("");
            ExtractionOutcome outcome = entityExtractor.extract(extractionText, doc.fileName(), runId);
            ResumeEntities entities = outcome.entities();
            tracer.end(extractStep, "extracted " + entities.getAll().size()
                    + " entities" + (outcome.degraded() ? " (DEGRADED)" : ""), null);

            String profileStep = tracer.begin("PROFILE_BUILD", null);
            tracer.recordMeta(profileStep, false, null);
            ResumeProfile profile = profileBuilder.build(entities);
            tracer.end(profileStep, "profile built", null);

            Persona persona = resolvePersona(run, profile);
            List<RedFlag> redFlags = outcome.degraded()
                    ? redFlagChecker.checkFromText(fullText)
                    : redFlagChecker.check(entities, fullText, persona);
            String checkStep = tracer.begin("RED_FLAG_CHECK", null);
            tracer.recordMeta(checkStep, false, null);
            tracer.end(checkStep, "found " + redFlags.size() + " red flags, persona=" + persona, null);

            return new ResumeContext(entities, redFlags, profile, archetype,
                    resolveTargetProfile(run, archetype), FunnelVerdict.MODE_JD,
                    outcome.degraded(), fullText, persona, true);
        } catch (Exception ex) {
            tracer.end(extractStep, "extraction failed: " + ex.getMessage(), ex.getMessage());
            log.warn("Entity extraction / profile build failed: {}", ex.getMessage());
            return new ResumeContext(new ResumeEntities(List.of()), redFlagChecker.checkFromText(fullText),
                    null, archetype, resolveTargetProfile(run, archetype), FunnelVerdict.MODE_JD,
                    true, fullText, Persona.GENERAL, true);
        }
    }

    /** 降级链主流程：循环 →（失败带片段）直连 →（失败）规则 → 引用校验 → 漏斗组装 → 报告。 */
    private void analyzeAndFinish(String runId, AgentRun run, ParsedDocument doc,
                                  SkillDefinition skill, LoopState resumeFrom, TraceRecorder tracer,
                                  ResumeContext resumeCtx,
                                  String enrichedUserMessage) throws Exception {
        if (resumeFrom.turn() > 0) {
            analyzeFollowUp(runId, run, doc, skill, resumeFrom, tracer);
            return;
        }
        boolean resumeReview = "resume-review".equals(skill.name());
        CandidateAnalysis candidate = generateCandidate(runId, run, doc, skill, resumeFrom,
                tracer, enrichedUserMessage);
        AnalysisResult result = citationVerifier.verify(candidate.result(), doc, tracer);
        if (resumeReview) {
            result = resultAssembler.assembleResumeResult(
                    runId, run, doc, resumeCtx, tracer, result, candidate.funnelFields());
        }
        completeAnalysis(runId, run, doc, skill, tracer, result, candidate.mode(), candidate.tokensUsed(), resumeReview);
    }

    private record CandidateAnalysis(AnalysisResult result, LlmFunnelFields funnelFields,
                                     String mode, Long tokensUsed) {
    }

    private CandidateAnalysis generateCandidate(String runId, AgentRun run, ParsedDocument doc,
                                                SkillDefinition skill, LoopState resumeFrom,
                                                TraceRecorder tracer, String enrichedUserMessage) {
        boolean resumeReview = "resume-review".equals(skill.name());
        List<AgentLoop.ObservedFragment> observations = List.of();
        if (reactEnabled && !resumeReview && shouldUseReact(runId, doc, tracer)) {
            LoopOutcome outcome = runLoop(runId, run, doc, skill, resumeFrom, tracer, enrichedUserMessage);
            if (outcome.parsed() != null && outcome.parsed().analysis() != null) {
                return new CandidateAnalysis(outcome.parsed().analysis(), outcome.parsed().funnel(),
                        "REACT", outcome.tokensUsed());
            }
            observations = outcome.observations();
        }
        LlmOutcome llm = runDirectLlm(runId, run, doc, skill, tracer, observations, enrichedUserMessage);
        if (llm.parsed() != null && llm.parsed().analysis() != null) {
            return new CandidateAnalysis(llm.parsed().analysis(), llm.parsed().funnel(),
                    "LLM", llm.tokensUsed());
        }
        throw new IllegalStateException("LLM_UNAVAILABLE: 未能生成分析报告。请检查 API Key、模型配置或服务可用性后重试。");
    }

    private void completeAnalysis(String runId, AgentRun run, ParsedDocument doc, SkillDefinition skill,
                                  TraceRecorder tracer, AnalysisResult result, String mode,
                                  Long tokensUsed, boolean resumeReview) throws JsonProcessingException {
        // prompt 版本标记（初始硬编码，后续可从配置读取）
        if (run.getPromptVersion() == null) {
            run.setPromptVersion(skill.name() + "-v1");
        }

        run.setResultJson(objectMapper.writeValueAsString(result));
        run.setExecutionMode(mode);
        run.setCurrentSummary(result.summary());
        run.setTokensUsed(tokensUsed);
        // 结论落缓存先于置 COMPLETED——run 对外可见完成时缓存必已就绪，提交侧不会竞态漏命中
        if (resumeReview) {
            resumeCacheService.storeResult(run, doc);
        }
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setFinishedAt(Instant.now());
        agentRunRepository.save(run);
        // checkpoint 保留供追问

        String reportStep = tracer.begin("REPORT", null);
        tracer.end(reportStep, "executionMode=" + mode + ", citations=" + result.citations().size()
                + (tokensUsed != null ? ", tokens=" + tokensUsed : "") + ", instance=" + instanceId, null);
        log.info("Analysis run completed, runId={}, mode={}, tokens={}, instance={}",
                runId, mode, tokensUsed, instanceId);
    }

    /** 追问轮：自由文本回答，不覆写首轮报告。 */
    private void analyzeFollowUp(String runId, AgentRun run, ParsedDocument doc,
                                 SkillDefinition skill, LoopState state, TraceRecorder tracer) throws Exception {
        String answer = null;
        String mode = "REACT";
        if (reactEnabled) {
            String stepId = tracer.begin("REACT_ANALYZE", null);
            tracer.recordMeta(stepId, true, "AgentLoop");
            try {
                ToolExecutionHolder.setRunId(runId);
                ReActContextHolder.set(promptBuilder.effectiveSystemPrompt(skill), tracer, stepId);
                AgentLoop.LoopResult loopResult = agentLoop.run(new AgentLoop.LoopContext(
                        runId, skill.name(), promptBuilder.effectiveToolNames(skill), promptBuilder.effectiveSystemPrompt(skill),
                        "", doc, tracer, stepId, state));
                if (loopResult.success()) {
                    answer = loopResult.finalAnswer();
                    tracer.end(stepId, "follow-up loop: rounds=" + loopResult.rounds(), null);
                } else {
                    tracer.end(stepId, "loop stopped(" + loopResult.stopReason() + ")", loopResult.stopReason());
                }
            } catch (Exception ex) {
                tracer.end(stepId, "loop failed: " + ex.getMessage(), ex.getMessage());
            } finally {
                ReActContextHolder.clear();
                ToolExecutionHolder.clear();
            }
        }
        if (answer == null || answer.isBlank()) {
            LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
            if (llmGateway != null) {
                try {
                    String flat = state.messages().stream()
                            .filter(m -> m.text() != null && !m.text().isBlank())
                            .map(m -> (m.role().equals("USER") ? "[User] " : "[Assistant] ") + m.text())
                            .reduce((a, b) -> a + "\\n" + b).orElse("");
                    LlmResponse resp = llmGateway.invoke("llm." + skill.name(), skill.directPromptFile(), flat, runId);
                    answer = resp.content();
                    mode = "LLM";
                } catch (Exception ex) { log.warn("FollowUp LLM failed: {}", ex.getMessage()); }
            }
        }
        if (answer == null || answer.isBlank()) { answer = "抱歉，本轮无法生成回答。"; mode = "FALLBACK"; }
        runMessageStore.saveMessage(runId, state.turn(), "ASSISTANT", answer);
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setFinishedAt(Instant.now());
        agentRunRepository.save(run);
        String s2 = tracer.begin("FOLLOW_UP_ANSWER", null);
        tracer.end(s2, "mode=" + mode, null);
        log.info("FollowUp completed, runId={}, turn={}, mode={}", runId, state.turn(), mode);
    }

    private void fatal(AgentRun run, String runId, TraceRecorder tracer, Exception fatal) {
        log.error("Analysis run failed, runId={}", runId, fatal);
        run.setStatus(AgentRunStatus.FAILED);
        run.setLastError(fatal.getClass().getSimpleName() + ": " + fatal.getMessage());
        run.setFinishedAt(Instant.now());
        agentRunRepository.save(run);
        String failStep = tracer.begin("RUN_FAILED", null);
        tracer.end(failStep, run.getLastError(), run.getLastError());
    }

    private void traceParse(TraceRecorder tracer, ParsedDocument doc) {
        String step = tracer.begin("PARSE", null);
        tracer.recordInput(step, "file=" + doc.fileName() + ", type=" + doc.fileType());
        tracer.end(step, "sections=" + doc.sections().size() + ", chars=" + doc.totalChars(), null);
    }

    private record LoopOutcome(ParsedAnalysis parsed, List<AgentLoop.ObservedFragment> observations, Long tokensUsed) {
    }

    private record LlmOutcome(ParsedAnalysis parsed, Long tokensUsed) {
    }

    /** 自研循环阶段。成功返回解析结果；失败返回 null + 已收集片段（供降级复用）。 */
    private LoopOutcome runLoop(String runId, AgentRun run, ParsedDocument doc,
                                SkillDefinition skill, LoopState resumeFrom, TraceRecorder tracer,
                                String enrichedUserMessage) {
        String stepId = tracer.begin("REACT_ANALYZE", null);
        tracer.recordMeta(stepId, true, "AgentLoop");
        try {
            ToolExecutionHolder.setRunId(runId);
            ReActContextHolder.set(promptBuilder.effectiveSystemPrompt(skill), tracer, stepId);
            AgentLoop.LoopResult loopResult = agentLoop.run(new AgentLoop.LoopContext(
                    runId, skill.name(), promptBuilder.effectiveToolNames(skill), promptBuilder.effectiveSystemPrompt(skill),
                    enrichedUserMessage != null ? enrichedUserMessage : promptBuilder.buildUserMessage(run, doc),
                    doc, tracer, stepId, resumeFrom));
            if (loopResult.success()) {
                ParsedAnalysis parsed = funnelFieldsMapper.parseResult(loopResult.finalAnswer());
                tracer.end(stepId, "loop completed: rounds=" + loopResult.rounds()
                        + ", toolCalls=" + loopResult.toolCalls()
                        + ", tokens=" + loopResult.tokensUsed()
                        + ", citations=" + parsed.analysis().citations().size(), null);
                return new LoopOutcome(parsed, loopResult.observations(), loopResult.tokensUsed());
            }
            log.warn("Loop stopped, runId={}, reason={}, observations={}",
                    runId, loopResult.stopReason(), loopResult.observations().size());
            tracer.end(stepId, "loop stopped(" + loopResult.stopReason() + "), carrying "
                    + loopResult.observations().size() + " fragments → fallback", loopResult.stopReason());
            return new LoopOutcome(ParsedAnalysis.empty(), loopResult.observations(), loopResult.tokensUsed());
        } catch (Exception ex) {
            log.warn("Loop analysis failed, falling back, runId={}: {}", runId, ex.getMessage());
            tracer.end(stepId, "loop failed → fallback direct LLM", ex.getMessage());
            return new LoopOutcome(ParsedAnalysis.empty(), List.of(), null);
        } finally {
            ReActContextHolder.clear();
            ToolExecutionHolder.clear();
        }
    }

    /**
     * 文档路由：短文档（低于阈值）迭代精读无信息增量——全文一次就能塞进 prompt，
     * 实测反而烧大量 token 且易陷入循环不收敛（BUDGET_ROUNDS），故直接走单轮分析。
     * 阈值 ≤0 表示关闭路由，恢复全量走循环。决策记入 trace，链路诊断可见。
     */
    private boolean shouldUseReact(String runId, ParsedDocument doc, TraceRecorder tracer) {
        if (reactMaxChars <= 0) {
            return true;
        }
        int chars = doc.totalChars();
        if (chars >= reactMaxChars) {
            return true;
        }
        String stepId = tracer.begin("ROUTING", null);
        tracer.recordMeta(stepId, false, null);
        tracer.end(stepId, "short doc direct: " + chars + " < " + reactMaxChars + " → skip ReAct", null);
        log.info("Routing to direct LLM (short doc), runId={}, chars={}, threshold={}",
                runId, chars, reactMaxChars);
        return false;
    }

    /** 降级 1：单次 LLM 调用。若循环已读片段则一并携带——降级不丢上下文。 */
    private LlmOutcome runDirectLlm(String runId, AgentRun run, ParsedDocument doc,
                                    SkillDefinition skill, TraceRecorder tracer,
                                    List<AgentLoop.ObservedFragment> observations,
                                    String enrichedUserMessage) {
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            return new LlmOutcome(ParsedAnalysis.empty(), null);
        }
        String stepId = tracer.begin("DIRECT_LLM", null);
        tracer.recordMeta(stepId, true, "SpringAI");
        try {
            String carried = promptBuilder.renderObservations(observations);
            String userContent = (enrichedUserMessage != null ? enrichedUserMessage : promptBuilder.buildUserMessage(run, doc))
                    + (carried.isEmpty() ? "" : "\n\nAgent 此前已阅读的片段（降级续读，勿重复阅读）：\n" + carried)
                    + "\n\n文档内容（[节ID] 标记了各节，引用时使用节ID）：\n" + promptBuilder.renderWithSectionIds(doc);
            // 流式输出（2026-09-18）：主分析改 invokeStream，增量经节流后推给 SSE 订阅者，
            // 前端“实时生成”面板逐字显示；总耗时不变但感知等待大幅缩短
            StringBuilder liveBuffer = new StringBuilder();
            LlmResponse response = llmGateway.invokeStream(
                    "llm." + skill.name(), skill.directPromptFile(), userContent, runId,
                    delta -> {
                        liveBuffer.append(delta);
                        // 48 字符节流：token 级推送事件量过大，攒小段再推（同时也是 SSE 保活）
                        if (liveBuffer.length() >= 48) {
                            stepEventPublisher.publishToken(runId, stepId, liveBuffer.toString());
                            liveBuffer.setLength(0);
                        }
                    });
            if (!liveBuffer.isEmpty()) {
                stepEventPublisher.publishToken(runId, stepId, liveBuffer.toString());
            }
            ParsedAnalysis parsed = funnelFieldsMapper.parseResult(response.content());
            long tokens = response.promptTokens() + response.completionTokens();
            tracer.end(stepId, "direct llm completed, tokens=" + tokens
                    + (carried.isEmpty() ? "" : ", carried=" + observations.size() + " fragments"), null);
            return new LlmOutcome(parsed, tokens);
        } catch (Exception ex) {
            log.warn("Direct LLM failed, caller decides whether fallback is allowed, runId={}: {}", runId, ex.getMessage());
            tracer.end(stepId, "direct LLM failed", ex.getMessage());
            return new LlmOutcome(ParsedAnalysis.empty(), null);
        }
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

}
