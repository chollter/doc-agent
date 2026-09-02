package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.parsing.DocumentParsingService;
import com.gcll.docagent.parsing.ParsedDocument;
import com.gcll.docagent.persistence.entity.AgentMessageEntity;
import com.gcll.docagent.persistence.mapper.AgentMessageMapper;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *     失败 → RULE_FALLBACK（纯规则摘要，无 LLM 也能出结果）
 * → CITATION_VERIFY → REPORT（结果+token账单落库 + 清理 checkpoint）
 * </pre>
 */
@Service
public class DocumentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAnalysisService.class);
    private static final int WORKER_THREADS = 3;

    private final DocumentParsingService parsingService;
    private final DocumentStore documentStore;
    private final TraceRecorderFactory traceRecorderFactory;
    private final AgentRunRepository agentRunRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final SkillRegistry skillRegistry;
    private final AgentLoop agentLoop;
    private final LoopCheckpointStore checkpointStore;
    private final AgentMessageMapper agentMessageMapper;
    private final ResumeEntityExtractor entityExtractor;
    private final RedFlagChecker redFlagChecker;
    private final ResumeProfileBuilder profileBuilder;
    private final ArchetypeRegistry archetypeRegistry;
    private final GroundingValidator groundingValidator;
    private final ApplicationEventPublisher eventPublisher;
    private final boolean reactEnabled;
    private final boolean exportEnabled;
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
            AgentMessageMapper agentMessageMapper,
            ResumeEntityExtractor entityExtractor,
            RedFlagChecker redFlagChecker,
            ResumeProfileBuilder profileBuilder,
            ArchetypeRegistry archetypeRegistry,
            GroundingValidator groundingValidator,
            ApplicationEventPublisher eventPublisher,
            @Value("${docagent.analysis.react-enabled:true}") boolean reactEnabled,
            @Value("${docagent.analysis.export-enabled:true}") boolean exportEnabled,
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
        this.agentMessageMapper = agentMessageMapper;
        this.entityExtractor = entityExtractor;
        this.redFlagChecker = redFlagChecker;
        this.profileBuilder = profileBuilder;
        this.archetypeRegistry = archetypeRegistry;
        this.groundingValidator = groundingValidator;
        this.eventPublisher = eventPublisher;
        this.reactEnabled = reactEnabled;
        this.exportEnabled = exportEnabled;
        this.requeueStaleMinutes = requeueStaleMinutes;
        this.defaultPromptVersion = defaultPromptVersion;
        this.reactMaxChars = reactMaxChars;
        this.instanceId = "p" + port + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    /** 提交分析：同步解析 + checkpoint-0 + 入队（QUEUED）。
     * 执行由 RunQueuedEvent 即时触发认领（快路径），RunQueueScheduler 轮询兜底。 */
    public AgentRun start(MultipartFile file, String instruction, String skillName, String jobDescription,
                          String targetDirection, String persona, String promptVersion, String optimizationNote) {
        SkillDefinition skill = skillRegistry.find(skillName)
                .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST,
                        "未知技能: " + skillName + "（可用: " + skillRegistry.list().stream().map(SkillDefinition::name).toList() + ")"));
        String fileName = file.getOriginalFilename();
        ParsedDocument doc;
        try {
            doc = parsingService.parse(fileName, file.getSize(), file.getInputStream());
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取上传文件失败: " + ex.getMessage());
        }

        AgentRun run = new AgentRun(
                "doc-" + UUID.randomUUID(),
                UUID.randomUUID().toString().replace("-", ""),
                "web",
                "demo-user",
                doc.outline());
        run.setFileName(fileName);
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
        if (optimizationNote != null && !optimizationNote.isBlank()) {
            run.setOptimizationNote(optimizationNote.trim());
        }
        run.setSectionCount(doc.sections().size());
        run.setStatus(AgentRunStatus.QUEUED);

        // checkpoint-0 先落（消息+文档快照）——任何实例认领后都能独立执行
        checkpointStore.save(run.getId(), new LoopState(
                skill.name(),
                List.of(
                        LoopMessage.system(effectiveSystemPrompt(skill)),
                        LoopMessage.user(buildUserMessage(run, doc))),
                0, 0, 0, doc));
        agentRunRepository.save(run);
        documentStore.put(run.getId(), doc);
        // 入队即触发认领（快路径）——提交到执行有显式链路，轮询仅作兜底

        log.info("Analysis run queued, runId={}, skill={}, file={}, sections={}",
                run.getId(), skill.name(), fileName, doc.sections().size());
        eventPublisher.publishEvent(new RunQueuedEvent(run.getId()));
        return run;
    }

    private static String buildUserMessage(AgentRun run, ParsedDocument doc) {
        return buildUserMessage(run, doc, ResumeContext.empty());
    }

    private static String buildUserMessage(AgentRun run, ParsedDocument doc, ResumeContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户要求：").append(run.getInstruction());
        sb.append("\n\n文档大纲：\n").append(doc.outline());

        ResumeEntities entities = ctx.entities();
        if (entities != null && !entities.isEmpty()) {
            sb.append("\n\n## 预抽取的简历实体（供参考，无需重新从原文提取）");
            sb.append("\n技能：").append(entities.getSkillNames());

            var metrics = entities.getMetrics();
            if (!metrics.isEmpty()) {
                sb.append("\n量化指标：");
                metrics.forEach(m -> sb.append("\n  - ").append(m.value()));
            }

            var workEntries = entities.getByType(ResumeEntity.EntityType.WORK_ENTRY);
            if (!workEntries.isEmpty()) {
                sb.append("\n工作经历条目（experienceStrength 请逐条对照这些条目评估）：");
                workEntries.forEach(w -> sb.append("\n  - ").append(w.value()));
            }
        }

        if (ctx.redFlags() != null && !ctx.redFlags().isEmpty()) {
            sb.append("\n\n## 红旗筛查结果（代码已验证，可直接引用）");
            ctx.redFlags().forEach(f -> sb.append("\n  - [").append(f.severity()).append("] ").append(f.message()));
        }

        if (run.getJobDescription() != null && !run.getJobDescription().isBlank()) {
            sb.append("\n\n## 目标岗位JD（matchDimensions 必须逐条对照）\n")
              .append(run.getJobDescription());
        } else if (ctx.archetype() != null) {
            Archetype a = ctx.archetype();
            sb.append("\n\n## 方向画像（广撒网模式，mustHaveCoverage/variantFit/positioning 必须对照）");
            sb.append("\n画像：").append(a.getName()).append("（").append(a.getSummary()).append("）");
            sb.append("\n共性要求（逐条给 MET/PARTIAL/MISSING + 证据）：");
            for (Archetype.MustHave m : a.getMustHaves()) {
                sb.append("\n  - [").append(m.getId()).append("] ").append(m.getRequirement())
                  .append("（证据线索：").append(String.join("、", m.getEvidenceHints())).append("）");
            }
            sb.append("\n子方向（逐个给 HIGH/MEDIUM/LOW 适配）：");
            for (Archetype.Variant v : a.getVariants()) {
                sb.append("\n  - [").append(v.getId()).append("] ").append(v.getName())
                  .append("：").append(String.join("、", v.getDifferentiators()));
            }
            sb.append("\n筛选题库（作 leverageCards 的 likelyQuestion 参考）：");
            for (String q : a.getScreeningQuestions()) {
                sb.append("\n  - ").append(q);
            }
        }

        return sb.toString();
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

    /** 简历分析的上下文——实体/红旗/画像/方向画像一次打包，贯穿执行链。 */
    private record ResumeContext(
            ResumeEntities entities,
            List<RedFlag> redFlags,
            ResumeProfile profile,
            Archetype archetype,
            String matchMode,
            boolean degraded,
            String fullText,
            Persona persona,
            boolean ran
    ) {
        static ResumeContext empty() {
            return new ResumeContext(new ResumeEntities(List.of()), List.of(), null, null,
                    FunnelVerdict.MODE_NONE, false, "", Persona.GENERAL, false);
        }
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
        saveMessage(runId, state.turn() + 1, "USER", message.trim());
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

    /** 采纳结果：修改稿 + 成功/失锚明细。 */
    public record AppliedRevision(String revisedMarkdown, int appliedCount, int requestedCount,
                                   List<String> missingBefores, List<String> changedSectionIds) {
    }

    /**
     * 建议采纳引擎（无状态、幂等）：对指定建议做 before→after 替换，产出修改稿（Markdown）。
     * <p>匹配用空白弹性正则（PDF 抽取的换行/多空格不丢锚点）；before 在全文任何节都
     * 定位不到时进 missingBefores——不静默失败。PDF 原件不可编辑，修改稿是文本形态，
     * 用户下载后回填自己的源文件，这是简历修改工具的标准形态。
     */
    public AppliedRevision applySuggestions(String runId, List<Integer> indices) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.AGENT_RUN_NOT_FOUND, "run 不存在: " + runId));
        if (run.getStatus() != AgentRunStatus.COMPLETED || run.getResultJson() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE, "run 未完成，无法采纳建议");
        }
        ParsedDocument doc = documentStore.get(runId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_STATE, "文档快照已过期（服务重启），请重新分析"));
        try {
            AnalysisResult result = objectMapper.readValue(run.getResultJson(), AnalysisResult.class);
            List<ActionableSuggestion> suggestions = result.actionableSuggestions() == null
                    ? List.of() : result.actionableSuggestions();
            List<String> missing = new ArrayList<>();
            List<String> changedSectionIds = new ArrayList<>();
            int applied = 0;
            // section 文本可变副本：逐条建议依次替换（多条建议作用于同一文档时累积生效）
            List<String> texts = new ArrayList<>(doc.sections().stream().map(DocSection::text).toList());
            for (Integer idx : indices == null ? List.<Integer>of() : indices) {
                if (idx == null || idx < 0 || idx >= suggestions.size()) {
                    continue;
                }
                ActionableSuggestion sug = suggestions.get(idx);
                if (sug.before() == null || sug.after() == null) {
                    continue;
                }
                boolean replaced = false;
                for (int i = 0; i < texts.size() && !replaced; i++) {
                    String next = flexibleReplace(texts.get(i), sug.before(), sug.after());
                    if (next != null) {
                        texts.set(i, next);
                        replaced = true;
                        String sectionId = doc.sections().get(i).id();
                        if (!changedSectionIds.contains(sectionId)) {
                            changedSectionIds.add(sectionId);
                        }
                    }
                }
                if (replaced) {
                    applied++;
                } else {
                    missing.add("#" + idx + " " + truncateFor(sug.before()));
                }
            }
            StringBuilder md = new StringBuilder();
            for (int i = 0; i < doc.sections().size(); i++) {
                DocSection sec = doc.sections().get(i);
                if (sec.heading() != null && !sec.heading().isBlank()) {
                    md.append("## ").append(sec.heading()).append("\n\n");
                }
                md.append(texts.get(i)).append("\n\n");
            }
            return new AppliedRevision(md.toString().trim(), applied,
                    indices == null ? 0 : indices.size(), missing, changedSectionIds);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "采纳失败: " + ex.getMessage());
        }
    }

    /** 空白弹性替换：把 before 按空白切段、逐段 Pattern.quote、以 \s+ 连接成模式，命中则替换为 after。 */
    private static String flexibleReplace(String text, String before, String after) {
        String[] tokens = before.strip().split("\s+");
        if (tokens.length == 0) {
            return null;
        }
        StringBuilder pattern = new StringBuilder();
        for (int i = 0; i < tokens.length; i++) {
            if (i > 0) {
                pattern.append("\s+");
            }
            pattern.append(Pattern.quote(tokens[i]));
        }
        try {
            Matcher m = Pattern.compile(pattern.toString()).matcher(text);
            if (m.find()) {
                return new StringBuilder(text).replace(m.start(), m.end(), after).toString();
            }
        } catch (Exception ignored) {
            // 模式异常视为未命中
        }
        return null;
    }

    private static String truncateFor(String s) {
        String t = s.replaceAll("\s+", " ").trim();
        return t.length() > 30 ? t.substring(0, 30) + "…" : t;
    }

    public List<AgentMessageEntity> getMessages(String runId) {
        return agentMessageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AgentMessageEntity>()
                        .eq(AgentMessageEntity::getRunId, runId)
                        .orderByAsc(AgentMessageEntity::getTurn));
    }

    private void saveMessage(String runId, int turn, String role, String content) {
        AgentMessageEntity entity = new AgentMessageEntity();
        entity.setRunId(runId);
        entity.setTurn(turn);
        entity.setRole(role);
        entity.setContent(content);
        entity.setCreatedAt(java.time.LocalDateTime.now());
        agentMessageMapper.insert(entity);
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
            // 简历技能：实体抽取 → 画像构建（供人群推断）→ 红旗筛查（人群调阈值）→ 方向画像解析（仅首轮）
            ResumeContext resumeCtx = ResumeContext.empty();
            if (state.turn() == 0 && state.round() == 0 && "resume-review".equals(skill.name())) {
                String extractStep = tracer.begin("ENTITY_EXTRACT", null);
                tracer.recordMeta(extractStep, true, "LLM");
                try {
                    // 标题行必须并入全文：Markdown 解析把"### 公司·职位 2021.07-至今"存进
                    // heading 而 section.text 只有正文——日期全在标题里，漏掉会让实体抽取、
                    // 红旗扫描、落地校验、词汇 diff 全部拿不到关键信息（md 简历系统性受损）
                    String fullText = doc.sections().stream()
                            .map(sec -> (sec.heading() != null && !sec.heading().isBlank()
                                    ? sec.heading() + "\n" : "") + sec.text())
                            .reduce((a, b) -> a + "\n" + b)
                            .orElse("");
                    ExtractionOutcome outcome = entityExtractor.extract(fullText, doc.fileName(), runId);
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

                    Archetype archetype = resolveArchetype(run);
                    String matchMode = archetype != null ? FunnelVerdict.MODE_DIRECTION
                            : run.getJobDescription() != null ? FunnelVerdict.MODE_JD
                            : FunnelVerdict.MODE_NONE;
                    resumeCtx = new ResumeContext(entities, redFlags, profile, archetype,
                            matchMode, outcome.degraded(), fullText, persona, true);
                } catch (Exception ex) {
                    tracer.end(extractStep, "extraction failed: " + ex.getMessage(), ex.getMessage());
                    log.warn("Entity extraction / profile build failed: {}", ex.getMessage());
                    resumeCtx = ResumeContext.empty();
                }
            }
            analyzeAndFinish(runId, run, doc, skill, state, tracer, resumeCtx,
                    buildUserMessage(run, doc, resumeCtx));
        } catch (Exception fatalEx) {
            fatal(run, runId, tracer, fatalEx);
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
        AnalysisResult result = null;
        LlmFunnelFields funnelFields = LlmFunnelFields.empty();
        String mode = null;
        List<AgentLoop.ObservedFragment> observations = List.of();
        Long tokensUsed = null;

        if (reactEnabled && shouldUseReact(runId, doc, tracer)) {
            LoopOutcome outcome = runLoop(runId, run, doc, skill, resumeFrom, tracer, enrichedUserMessage);
            if (outcome.parsed() != null && outcome.parsed().analysis() != null) {
                result = outcome.parsed().analysis();
                funnelFields = outcome.parsed().funnel();
                mode = "REACT";
                tokensUsed = outcome.tokensUsed();
            } else {
                observations = outcome.observations();
            }
        }
        if (result == null) {
            LlmOutcome llm = runDirectLlm(runId, run, doc, skill, tracer, observations, enrichedUserMessage);
            if (llm.parsed() != null && llm.parsed().analysis() != null) {
                result = llm.parsed().analysis();
                funnelFields = llm.parsed().funnel();
                mode = "LLM";
                tokensUsed = llm.tokensUsed();
            }
        }
        if (result == null) {
            result = ruleFallback(doc, tracer);
            mode = "FALLBACK";
            tokensUsed = 0L;
        }

        result = verifyCitations(result, doc, tracer);

        // 附加实体和红旗结果（简历技能）
        if (!resumeCtx.entities().isEmpty() || !resumeCtx.redFlags().isEmpty()) {
            result = result.withEntitiesAndFindings(resumeCtx.entities().getAll(),
                    resumeCtx.redFlags().stream().map(RedFlag::message).toList());
        }

        // P12 漏斗结论：红旗 + 方向画像 + LLM 五角度输出 → FunnelVerdict
        // 按技能判断而非实体非空：降级时空实体仍需组装（LLM 五角度输出基于直读原文，不该陪葬）
        if ("resume-review".equals(skill.name())) {
            FunnelVerdict verdict = assembleVerdict(resumeCtx, funnelFields,
                    result.actionableSuggestions());
            result = result.withResumeDeepAnalysis(resumeCtx.profile(), null,
                            result.actionableSuggestions(), result.enhancedKeyPoints(), result.enhancedRisks())
                    .withFunnelVerdict(verdict);

            // 兼容映射：DB 列表排序仍用 score_overall，明细记录各角度档位
            run.setScoreOverall(verdict.legacyOverall());
            try {
                Map<String, Object> dims = new LinkedHashMap<>();
                dims.put("strengthBand", verdict.strength() != null ? verdict.strength().band().name() : null);
                dims.put("presentation", verdict.presentation() != null ? verdict.presentation().score() : null);
                dims.put("matchBand", verdict.matchBand().name());
                dims.put("matchMode", verdict.matchMode());
                dims.put("highRedFlag", verdict.hasHighRedFlag());
                dims.put("degraded", verdict.analysisDegraded());
                run.setScoreDimensions(objectMapper.writeValueAsString(dims));
            } catch (Exception ignored) {
                // 序列化失败不影响主流程
            }
        }
        // prompt 版本标记（初始硬编码，后续可从配置读取）
        if (run.getPromptVersion() == null) {
            run.setPromptVersion(skill.name() + "-v1");
        }

        run.setResultJson(objectMapper.writeValueAsString(result));
        run.setExecutionMode(mode);
        run.setCurrentSummary(result.summary());
        run.setTokensUsed(tokensUsed);
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
                ReActContextHolder.set(effectiveSystemPrompt(skill), tracer, stepId);
                AgentLoop.LoopResult loopResult = agentLoop.run(new AgentLoop.LoopContext(
                        runId, skill.name(), effectiveToolNames(skill), effectiveSystemPrompt(skill),
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
        saveMessage(runId, state.turn(), "ASSISTANT", answer);
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

    /** 解析产物：稳定结果 + 五角度漏斗字段（后者只进 FunnelVerdict，不进 AnalysisResult）。 */
    private record ParsedAnalysis(AnalysisResult analysis, LlmFunnelFields funnel) {
        static ParsedAnalysis empty() {
            return new ParsedAnalysis(null, LlmFunnelFields.empty());
        }
    }

    private record LoopOutcome(ParsedAnalysis parsed, List<AgentLoop.ObservedFragment> observations, Long tokensUsed) {
    }

    private record LlmOutcome(ParsedAnalysis parsed, Long tokensUsed) {
    }

    /** 技能实际暴露的工具（压测等场景可关掉 DANGER 导出）。 */
    private List<String> effectiveToolNames(SkillDefinition skill) {
        if (exportEnabled) {
            return skill.toolNames();
        }
        return skill.toolNames().stream().filter(t -> !"export_report".equals(t)).toList();
    }

    /** 工具集与提示词必须一致：剔除导出工具时同步剔除提示词中的导出指引行，
     *  否则模型会按提示调用不在 spec 列表里的工具，导致请求非法。 */
    private String effectiveSystemPrompt(SkillDefinition skill) {
        if (exportEnabled) {
            return skill.reactSystemPrompt();
        }
        return skill.reactSystemPrompt().lines()
                .filter(line -> !line.contains("export_report"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse(skill.reactSystemPrompt());
    }

    /** 自研循环阶段。成功返回解析结果；失败返回 null + 已收集片段（供降级复用）。 */
    private LoopOutcome runLoop(String runId, AgentRun run, ParsedDocument doc,
                                SkillDefinition skill, LoopState resumeFrom, TraceRecorder tracer,
                                String enrichedUserMessage) {
        String stepId = tracer.begin("REACT_ANALYZE", null);
        tracer.recordMeta(stepId, true, "AgentLoop");
        try {
            ToolExecutionHolder.setRunId(runId);
            ReActContextHolder.set(effectiveSystemPrompt(skill), tracer, stepId);
            AgentLoop.LoopResult loopResult = agentLoop.run(new AgentLoop.LoopContext(
                    runId, skill.name(), effectiveToolNames(skill), effectiveSystemPrompt(skill),
                    enrichedUserMessage != null ? enrichedUserMessage : buildUserMessage(run, doc),
                    doc, tracer, stepId, resumeFrom));
            if (loopResult.success()) {
                ParsedAnalysis parsed = parseResult(loopResult.finalAnswer());
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
            String carried = renderObservations(observations);
            String userContent = (enrichedUserMessage != null ? enrichedUserMessage : buildUserMessage(run, doc))
                    + (carried.isEmpty() ? "" : "\n\nAgent 此前已阅读的片段（降级续读，勿重复阅读）：\n" + carried)
                    + "\n\n文档内容（[节ID] 标记了各节，引用时使用节ID）：\n" + renderWithSectionIds(doc);
            LlmResponse response = llmGateway.invoke(
                    "llm." + skill.name(), skill.directPromptFile(), userContent, runId);
            ParsedAnalysis parsed = parseResult(response.content());
            long tokens = response.promptTokens() + response.completionTokens();
            tracer.end(stepId, "direct llm completed, tokens=" + tokens
                    + (carried.isEmpty() ? "" : ", carried=" + observations.size() + " fragments"), null);
            return new LlmOutcome(parsed, tokens);
        } catch (Exception ex) {
            log.warn("Direct LLM failed, falling back to rule, runId={}: {}", runId, ex.getMessage());
            tracer.end(stepId, "direct LLM failed → fallback rule", ex.getMessage());
            return new LlmOutcome(ParsedAnalysis.empty(), null);
        }
    }

    /** 把循环已读片段渲染进降级 prompt（每片截断 600 字，总量约 4000 字）。 */
    private static String renderObservations(List<AgentLoop.ObservedFragment> observations) {
        if (observations == null || observations.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (AgentLoop.ObservedFragment f : observations) {
            if (total > 4000) {
                sb.append("…（其余片段已省略）\n");
                break;
            }
            String body = f.observation() == null ? "" : f.observation();
            if (body.length() > 600) {
                body = body.substring(0, 600) + "…";
            }
            sb.append("· ").append(f.toolName()).append(" ").append(f.args() == null ? "" : f.args())
              .append(" → ").append(body).append('\n');
            total += body.length();
        }
        return sb.toString();
    }

    /** 降级 2：纯规则摘要——无 API Key / LLM 全挂时仍可演示完整链路与 trace。 */
    private AnalysisResult ruleFallback(ParsedDocument doc, TraceRecorder tracer) {
        String stepId = tracer.begin("RULE_FALLBACK", null);
        tracer.recordMeta(stepId, false, null);

        List<DocSection> sections = doc.sections();
        String first = sections.get(0).text();
        String summary = first.length() > 200 ? first.substring(0, 200) + "…" : first;

        List<String> keyPoints = new ArrayList<>();
        List<AnalysisResult.Citation> citations = new ArrayList<>();
        for (int i = 0; i < Math.min(3, sections.size()); i++) {
            DocSection s = sections.get(i);
            String title = s.heading() != null ? s.heading()
                    : s.text().lines().findFirst().orElse("");
            if (!title.isBlank()) {
                keyPoints.add("【" + s.id() + "】" + (title.length() > 60 ? title.substring(0, 60) : title));
            }
            String quote = s.text().replaceAll("\\s+", " ");
            citations.add(new AnalysisResult.Citation(s.id(),
                    quote.length() > 30 ? quote.substring(0, 30) : quote));
        }
        AnalysisResult result = new AnalysisResult(
                "（规则模式）" + summary,
                keyPoints,
                List.of("规则模式不做推断，未识别文档中的风险"),
                List.of("LLM 当前不可用，建议配置 LLM_API_KEY 后重新分析以获得针对性建议"),
                citations, null, null, null, null, null,
                null, null, null, null, null, null);
        tracer.end(stepId, "rule fallback completed", null);
        return result;
    }

    /** 引用校验：sectionId 必须真实存在；quote 尝试定位到真实节；编造的引用剔除。 */
    private AnalysisResult verifyCitations(AnalysisResult result, ParsedDocument doc, TraceRecorder tracer) {
        String stepId = tracer.begin("CITATION_VERIFY", null);
        List<AnalysisResult.Citation> kept = new ArrayList<>();
        int dropped = 0;
        for (AnalysisResult.Citation c : result.citations()) {
            if (c == null || c.sectionId() == null) {
                dropped++;
                continue;
            }
            String normalized = c.sectionId().trim();
            var section = doc.findSection(normalized);
            if (section.isPresent()) {
                kept.add(new AnalysisResult.Citation(normalized, c.quote()));
            } else if (c.quote() != null && !c.quote().isBlank()) {
                List<DocSection> matches = doc.sections().stream()
                        .filter(s -> s.text() != null && s.text().contains(c.quote().trim()))
                        .toList();
                if (matches.size() == 1) {
                    kept.add(new AnalysisResult.Citation(matches.get(0).id(), c.quote()));
                } else {
                    dropped++;
                }
            } else {
                dropped++;
            }
        }
        tracer.end(stepId, "kept=" + kept.size() + ", dropped=" + dropped, null);
        return result.withCitations(List.copyOf(kept));
    }

    // --- 结果解析 ---

    /** 容错解析 LLM 输出：剥代码围栏、截取最外层 JSON 对象。 */
    ParsedAnalysis parseResult(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("empty llm content");
        }
        String json = stripFences(content);
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no json object in llm content");
        }
        try {
            // 容错：先读树清洗（模型偶尔违反对象契约输出字符串数组），再映射为 RawResult，
            // 避免单字段畸形导致整个结果作废落入规则兑底。
            JsonNode tree = objectMapper.readTree(json.substring(start, end + 1));
            sanitizeStringElements(tree);
            RawResult raw = objectMapper.treeToValue(tree, RawResult.class);
            if (raw.summary == null || raw.summary.isBlank()) {
                throw new IllegalArgumentException("missing summary field");
            }
            List<AnalysisResult.Citation> citations = new ArrayList<>();
            if (raw.citations != null) {
                for (RawCitation c : raw.citations) {
                    if (c != null && c.sectionId != null && c.quote != null) {
                        citations.add(new AnalysisResult.Citation(c.sectionId, c.quote));
                    }
                }
            }
            AnalysisResult analysis = new AnalysisResult(
                    raw.summary,
                    raw.keyPoints == null ? List.of() : raw.keyPoints,
                    raw.risks == null ? List.of() : raw.risks,
                    raw.suggestions == null ? List.of() : raw.suggestions,
                    citations,
                    parseMatchDimensions(raw.matchDimensions),
                    parseGaps(raw.gaps),
                    parseInterviewQuestions(raw.interviewQuestions),
                    null, null,
                    null, parseQualityScore(raw.qualityScore),
                    parseActionableSuggestions(raw.actionableSuggestions),
                    parseEnhancedKeyPoints(raw.enhancedKeyPoints),
                    parseEnhancedRisks(raw.enhancedRisks),
                    null);
            LlmFunnelFields funnel = new LlmFunnelFields(
                    parsePresentation(raw.presentation),
                    parseExperienceStrength(raw.experienceStrength),
                    parseLeverageCards(raw.leverageCards),
                    parseCoverage(raw.mustHaveCoverage),
                    parseVariantFit(raw.variantFit),
                    parsePositioning(raw.positioning),
                    parseEvaluation(raw.evaluation));
            return new ParsedAnalysis(analysis, funnel);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid result json: " + ex.getMessage(), ex);
        }
    }

    private static String stripFences(String content) {
        String s = content.trim();
        if (s.startsWith("```")) {
            int firstLineEnd = s.indexOf('\n');
            if (firstLineEnd > 0) {
                s = s.substring(firstLineEnd + 1);
            }
            int fenceEnd = s.lastIndexOf("```");
            if (fenceEnd >= 0) {
                s = s.substring(0, fenceEnd);
            }
        }
        return s.trim();
    }

    private static String renderWithSectionIds(ParsedDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (DocSection s : doc.sections()) {
            sb.append('[').append(s.id())
              .append(s.heading() != null ? " | " + s.heading() : "").append("]\n")
              .append(s.text()).append("\n\n");
        }
        return sb.toString().trim();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 解析容错：模型偶尔把对象数组字段输出为字符串数组（违反 prompt 契约），
     * 逐字段把字符串元素包成对象，让其余字段的分析结果幸存。
     */
    private static void sanitizeStringElements(JsonNode tree) {
        if (tree == null || !tree.isObject()) return;
        wrapStringElements(tree, "matchDimensions", "name");
        wrapStringElements(tree, "gaps", "gap");
        wrapStringElements(tree, "interviewQuestions", "question");
        wrapStringElements(tree, "mustHaveCoverage", "requirement");
        wrapStringElements(tree, "variantFit", "name");
    }

    private static void wrapStringElements(JsonNode root, String field, String targetKey) {
        JsonNode arr = root.get(field);
        if (arr == null || !arr.isArray()) return;
        ArrayNode array = (ArrayNode) arr;
        for (int i = 0; i < array.size(); i++) {
            JsonNode el = array.get(i);
            if (el.isTextual()) {
                ObjectNode wrapped = new ObjectNode(JsonNodeFactory.instance);
                wrapped.put(targetKey, el.asText());
                array.set(i, wrapped);
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawResult {
        public String summary;
        public List<String> keyPoints;
        public List<String> risks;
        public List<String> suggestions;
        public List<RawCitation> citations;
        public List<RawMatchDimension> matchDimensions;
        public List<RawGap> gaps;
        public List<RawInterviewQuestion> interviewQuestions;
        // 简历深度分析（P11 兼容）
        public RawQualityScore qualityScore;
        public List<RawActionableSuggestion> actionableSuggestions;
        public List<RawEnhancedKeyPoint> enhancedKeyPoints;
        public List<RawEnhancedRisk> enhancedRisks;
        // P12 五角度漏斗字段
        public RawPresentation presentation;
        public List<RawExperienceStrength> experienceStrength;
        public List<RawLeverageCard> leverageCards;
        public List<RawCoverage> mustHaveCoverage;
        public List<RawVariantFit> variantFit;
        public RawPositioning positioning;
        public RawEvaluation evaluation;
    }

    /** P12 LLM 五角度输出——只进 FunnelVerdict，不进 AnalysisResult。 */
    private record LlmFunnelFields(
            Presentation presentation,
            List<ExperienceStrength> experienceStrength,
            List<LeverageCard> leverageCards,
            List<MustHaveCoverage> mustHaveCoverage,
            List<VariantFit> variantFit,
            PositioningCheck positioning,
            FunnelVerdict.Evaluation evaluation
    ) {
        static LlmFunnelFields empty() {
            return new LlmFunnelFields(null, List.of(), List.of(), List.of(), List.of(), null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawPresentation {
        public Integer score;
        public List<String> issues;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawExperienceStrength {
        public String sectionId;
        public String entryRef;
        public Map<String, Boolean> star;
        public String resultQuality;
        public String attribution;
        public String concern;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawLeverageCard {
        public String kind;
        public String point;
        public String sectionId;
        public String likelyQuestion;
        public String prepHint;
        public String defenseStrategy;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawCoverage {
        public String requirementId;
        public String requirement;
        public String status;
        public String evidence;
        public String sectionId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawVariantFit {
        public String variantId;
        public String name;
        public String fit;
        public String reason;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawPositioning {
        public Boolean anchored;
        public String currentAnchor;
        public String suggestedAnchor;
        public String comment;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawEvaluation {
        public String overall;
        public List<RawDimensionComment> dimensions;
        public List<String> strengths;
        public List<String> weaknesses;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawDimensionComment {
        public String dimension;
        public String comment;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawQualityScore {
        public Integer clarity;
        public Integer credibility;
        public Integer professionalism;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawActionableSuggestion {
        public String severity;
        public String target;
        public String sectionId;
        public String before;
        public String after;
        public String reason;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawEnhancedKeyPoint {
        public String point;
        public String evidence;
        public String sectionId;
        public String interviewValue;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawEnhancedRisk {
        public String risk;
        public String detail;
        public String sectionId;
        public String challengeAngle;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawMatchDimension { public String name; public String level; public String reason; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawGap { public String requirement; public String gap; public String suggestion; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawInterviewQuestion { public String question; public String intent; public String suggestedAnswer; public Boolean isGapPrep; }

    private static List<AnalysisResult.MatchDimension> parseMatchDimensions(List<RawMatchDimension> raw) {
        if (raw == null) return null;
        return raw.stream().filter(Objects::nonNull).map(m -> new AnalysisResult.MatchDimension(m.name, m.level, m.reason)).toList();
    }

    private static List<AnalysisResult.Gap> parseGaps(List<RawGap> raw) {
        if (raw == null) return null;
        return raw.stream().filter(Objects::nonNull).map(g -> new AnalysisResult.Gap(g.requirement, g.gap, g.suggestion)).toList();
    }

    private static List<AnalysisResult.InterviewQuestion> parseInterviewQuestions(List<RawInterviewQuestion> raw) {
        if (raw == null) return null;
        return raw.stream().filter(Objects::nonNull).map(q -> new AnalysisResult.InterviewQuestion(q.question, q.intent, q.suggestedAnswer, Boolean.TRUE.equals(q.isGapPrep))).toList();
    }

    private static QualityScore parseQualityScore(RawQualityScore raw) {
        if (raw == null) return null;
        Map<String, Integer> dims = new LinkedHashMap<>();
        if (raw.clarity != null) dims.put(QualityScore.DIM_CLARITY, raw.clarity);
        if (raw.credibility != null) dims.put(QualityScore.DIM_CREDIBILITY, raw.credibility);
        if (raw.professionalism != null) dims.put(QualityScore.DIM_PROFESSIONALISM, raw.professionalism);
        if (dims.isEmpty()) return null;
        return new QualityScore(QualityScore.computeOverall(dims), dims);
    }

    private static Presentation parsePresentation(RawPresentation raw) {
        if (raw == null || raw.score == null) return null;
        return Presentation.of(raw.score, raw.issues);
    }

    private static List<ExperienceStrength> parseExperienceStrength(List<RawExperienceStrength> raw) {
        if (raw == null) return List.of();
        List<ExperienceStrength> out = new ArrayList<>();
        for (RawExperienceStrength r : raw) {
            if (r == null || r.star == null) continue;
            try {
                out.add(new ExperienceStrength(
                        r.sectionId, r.entryRef, r.star,
                        r.resultQuality == null ? ExperienceStrength.ResultQuality.NONE
                                : ExperienceStrength.ResultQuality.valueOf(r.resultQuality.toUpperCase()),
                        r.attribution == null ? ExperienceStrength.Attribution.PARTICIPANT
                                : ExperienceStrength.Attribution.valueOf(r.attribution.toUpperCase()),
                        r.concern));
            } catch (IllegalArgumentException ignored) {
                // 枚举值非法的条目丢弃，不影响其余
            }
        }
        return out;
    }

    private static List<LeverageCard> parseLeverageCards(List<RawLeverageCard> raw) {
        if (raw == null) return List.of();
        List<LeverageCard> out = new ArrayList<>();
        for (RawLeverageCard r : raw) {
            if (r == null || r.point == null || r.point.isBlank()) continue;
            LeverageCard.Kind kind;
            try {
                kind = r.kind == null ? LeverageCard.Kind.STRENGTH
                        : LeverageCard.Kind.valueOf(r.kind.toUpperCase());
            } catch (IllegalArgumentException e) {
                kind = "RISK".equalsIgnoreCase(r.kind) ? LeverageCard.Kind.RISK : LeverageCard.Kind.STRENGTH;
            }
            out.add(new LeverageCard(kind, r.point, r.sectionId,
                    r.likelyQuestion, r.prepHint, r.defenseStrategy));
        }
        return out;
    }

    private static List<MustHaveCoverage> parseCoverage(List<RawCoverage> raw) {
        if (raw == null) return List.of();
        List<MustHaveCoverage> out = new ArrayList<>();
        for (RawCoverage r : raw) {
            if (r == null || r.requirementId == null || r.status == null) continue;
            try {
                out.add(new MustHaveCoverage(r.requirementId, r.requirement,
                        MustHaveCoverage.Status.valueOf(r.status.toUpperCase()),
                        r.evidence, r.sectionId));
            } catch (IllegalArgumentException ignored) {
                // 非法状态值丢弃
            }
        }
        return out;
    }

    private static List<VariantFit> parseVariantFit(List<RawVariantFit> raw) {
        if (raw == null) return List.of();
        List<VariantFit> out = new ArrayList<>();
        for (RawVariantFit r : raw) {
            if (r == null || r.variantId == null || r.fit == null) continue;
            try {
                out.add(new VariantFit(r.variantId, r.name,
                        VariantFit.Fit.valueOf(r.fit.toUpperCase()), r.reason));
            } catch (IllegalArgumentException ignored) {
                // 非法适配值丢弃
            }
        }
        return out;
    }

    private static PositioningCheck parsePositioning(RawPositioning raw) {
        if (raw == null || raw.anchored == null) return null;
        return new PositioningCheck(raw.anchored, raw.currentAnchor, raw.suggestedAnchor, raw.comment);
    }

    /** 定性评价（v6）：清洗空白条目；全部为空时返回 null（历史 run/LLM 未产出时前端优雅跳过）。 */
    private static FunnelVerdict.Evaluation parseEvaluation(RawEvaluation raw) {
        if (raw == null) return null;
        String overall = raw.overall != null && !raw.overall.isBlank() ? raw.overall.trim() : null;
        List<FunnelVerdict.Evaluation.DimensionComment> dims = raw.dimensions == null ? List.of()
                : raw.dimensions.stream()
                        .filter(d -> d != null && d.dimension != null && !d.dimension.isBlank()
                                && d.comment != null && !d.comment.isBlank())
                        .map(d -> new FunnelVerdict.Evaluation.DimensionComment(
                                d.dimension.trim(), d.comment.trim()))
                        .toList();
        List<String> strengths = cleanStrings(raw.strengths);
        List<String> weaknesses = cleanStrings(raw.weaknesses);
        if (overall == null && dims.isEmpty() && strengths.isEmpty() && weaknesses.isEmpty()) {
            return null;
        }
        return new FunnelVerdict.Evaluation(overall, dims, strengths, weaknesses);
    }

    private static List<String> cleanStrings(List<String> list) {
        if (list == null) return List.of();
        return list.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
    }

    /**
     * 组装漏斗结论：LLM 输出 + 代码事实（红旗/词汇diff/画像定义）合成。
     * 部分降级：实体抽取失败只损失实体级精度——红旗已切换为文本级粗查（executeClaimed）、
     * 画像缺失，LLM 直读原文产出的五角度保留，degraded 标记显式告知。
     * 落地性校验（反编造/引文锚定）无论降级与否都执行——不变量不允许豁免。
     */
    private FunnelVerdict assembleVerdict(ResumeContext ctx, LlmFunnelFields fields,
                                          List<ActionableSuggestion> suggestions) {
        List<RedFlag> redFlags = ctx.redFlags();
        List<MustHaveCoverage> coverage = mergeCoverage(ctx.archetype(), fields.mustHaveCoverage());
        List<VariantFit> variantFit = mergeVariants(ctx.archetype(), fields.variantFit());
        List<VocabularyGap> vocabGaps = ctx.archetype() != null
                ? ctx.archetype().findVocabularyGaps(ctx.fullText()) : List.of();
        return new FunnelVerdict(
                redFlags,
                ctx.matchMode(),
                ctx.archetype() != null ? ctx.archetype().getId() : null,
                coverage, variantFit, vocabGaps, fields.positioning(),
                fields.experienceStrength(),
                StrengthStats.from(fields.experienceStrength()),
                fields.presentation(),
                fields.leverageCards(),
                ctx.degraded(),
                groundingValidator.validate(suggestions, ctx.fullText()),
                fields.evaluation());
    }

    /** 要求文本以画像定义为准（LLM 只给 id/status/evidence），防转录走样。 */
    private static List<MustHaveCoverage> mergeCoverage(Archetype archetype, List<MustHaveCoverage> llm) {
        if (archetype == null) {
            return llm;
        }
        var byId = archetype.getMustHaves().stream()
                .collect(java.util.stream.Collectors.toMap(Archetype.MustHave::getId, m -> m, (a, b) -> a));
        List<MustHaveCoverage> merged = new ArrayList<>();
        for (MustHaveCoverage c : llm) {
            Archetype.MustHave def = byId.get(c.requirementId());
            merged.add(new MustHaveCoverage(c.requirementId(),
                    def != null ? def.getRequirement() : c.requirement(),
                    c.status(), c.evidence(), c.sectionId()));
        }
        return merged;
    }

    private static List<VariantFit> mergeVariants(Archetype archetype, List<VariantFit> llm) {
        if (archetype == null) {
            return llm;
        }
        var byId = archetype.getVariants().stream()
                .collect(java.util.stream.Collectors.toMap(Archetype.Variant::getId, v -> v, (a, b) -> a));
        List<VariantFit> merged = new ArrayList<>();
        for (VariantFit v : llm) {
            Archetype.Variant def = byId.get(v.variantId());
            merged.add(new VariantFit(v.variantId(),
                    def != null ? def.getName() : v.name(),
                    v.fit(), v.reason()));
        }
        return merged;
    }

    private static List<ActionableSuggestion> parseActionableSuggestions(List<RawActionableSuggestion> raw) {
        if (raw == null) return null;
        return raw.stream().filter(Objects::nonNull)
                .map(s -> new ActionableSuggestion(
                        s.severity, s.target, s.sectionId, s.before, s.after, s.reason))
                .toList();
    }

    private static List<AnalysisResult.EnhancedKeyPoint> parseEnhancedKeyPoints(List<RawEnhancedKeyPoint> raw) {
        if (raw == null) return null;
        return raw.stream().filter(Objects::nonNull)
                .map(k -> new AnalysisResult.EnhancedKeyPoint(k.point, k.evidence, k.sectionId, k.interviewValue))
                .toList();
    }

    private static List<AnalysisResult.EnhancedRisk> parseEnhancedRisks(List<RawEnhancedRisk> raw) {
        if (raw == null) return null;
        return raw.stream().filter(Objects::nonNull)
                .map(r -> new AnalysisResult.EnhancedRisk(r.risk, r.detail, r.sectionId, r.challengeAngle))
                .toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawCitation {
        public String sectionId;
        public String quote;
    }
}
