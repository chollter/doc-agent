package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.gcll.docagent.persistence.repository.AgentRunRepository;
import com.gcll.docagent.resilience.LlmResponse;
import com.gcll.docagent.tool.ToolExecutionHolder;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Executors;

/**
 * 文档分析编排——DB 队列驱动的多实例架构：
 * <pre>
 * start(): 同步解析 → checkpoint-0（消息+文档快照）→ run=QUEUED
 * RunQueueScheduler 认领循环: 空闲容量内 CAS 认领（多实例天然互斥）→ 本实例执行
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
    private final boolean reactEnabled;
    private final boolean exportEnabled;
    private final int requeueStaleMinutes;
    private final String instanceId;

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
            @Value("${docagent.analysis.react-enabled:true}") boolean reactEnabled,
            @Value("${docagent.analysis.export-enabled:true}") boolean exportEnabled,
            @Value("${docagent.analysis.dispatcher.requeue-stale-minutes:15}") int requeueStaleMinutes,
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
        this.reactEnabled = reactEnabled;
        this.exportEnabled = exportEnabled;
        this.requeueStaleMinutes = requeueStaleMinutes;
        this.instanceId = "p" + port + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    /** 提交分析：同步解析 + checkpoint-0 + 入队（QUEUED）。执行由调度器异步认领。 */
    public AgentRun start(MultipartFile file, String instruction, String skillName) {
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

        log.info("Analysis run queued, runId={}, skill={}, file={}, sections={}",
                run.getId(), skill.name(), fileName, doc.sections().size());
        return run;
    }

    private static String buildUserMessage(AgentRun run, ParsedDocument doc) {
        return "用户要求：" + run.getInstruction() + "\n\n文档大纲：\n" + doc.outline();
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
            if (state.round() > 0) {
                String stepId = tracer.begin("LOOP_RESUME", null);
                tracer.recordInput(stepId, "from round " + state.round()
                        + ", messages=" + state.messages().size()
                        + ", resumedBy=" + instanceId);
                tracer.end(stepId, "队列自愈：从 checkpoint 续跑", null);
            } else {
                traceParse(tracer, doc);
            }
            analyzeAndFinish(runId, run, doc, skill, state, tracer);
        } catch (Exception fatalEx) {
            fatal(run, runId, tracer, fatalEx);
        }
    }

    /** 降级链主流程：循环 →（失败带片段）直连 →（失败）规则 → 引用校验 → 报告。 */
    private void analyzeAndFinish(String runId, AgentRun run, ParsedDocument doc,
                                  SkillDefinition skill, LoopState resumeFrom, TraceRecorder tracer) throws Exception {
        AnalysisResult result = null;
        String mode = null;
        List<AgentLoop.ObservedFragment> observations = List.of();
        Long tokensUsed = null;

        if (reactEnabled) {
            LoopOutcome outcome = runLoop(runId, run, doc, skill, resumeFrom, tracer);
            if (outcome.result() != null) {
                result = outcome.result();
                mode = "REACT";
                tokensUsed = outcome.tokensUsed();
            } else {
                observations = outcome.observations();
            }
        }
        if (result == null) {
            LlmOutcome llm = runDirectLlm(runId, run, doc, skill, tracer, observations);
            if (llm.result() != null) {
                result = llm.result();
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

        run.setResultJson(objectMapper.writeValueAsString(result));
        run.setExecutionMode(mode);
        run.setCurrentSummary(result.summary());
        run.setTokensUsed(tokensUsed);
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setFinishedAt(Instant.now());
        agentRunRepository.save(run);
        checkpointStore.delete(runId);

        String reportStep = tracer.begin("REPORT", null);
        tracer.end(reportStep, "executionMode=" + mode + ", citations=" + result.citations().size()
                + (tokensUsed != null ? ", tokens=" + tokensUsed : "") + ", instance=" + instanceId, null);
        log.info("Analysis run completed, runId={}, mode={}, tokens={}, instance={}",
                runId, mode, tokensUsed, instanceId);
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

    private record LoopOutcome(AnalysisResult result, List<AgentLoop.ObservedFragment> observations, Long tokensUsed) {
    }

    private record LlmOutcome(AnalysisResult result, Long tokensUsed) {
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
                                SkillDefinition skill, LoopState resumeFrom, TraceRecorder tracer) {
        String stepId = tracer.begin("REACT_ANALYZE", null);
        tracer.recordMeta(stepId, true, "AgentLoop");
        try {
            ToolExecutionHolder.setRunId(runId);
            ReActContextHolder.set(effectiveSystemPrompt(skill), tracer, stepId);
            AgentLoop.LoopResult loopResult = agentLoop.run(new AgentLoop.LoopContext(
                    runId, skill.name(), effectiveToolNames(skill), effectiveSystemPrompt(skill),
                    buildUserMessage(run, doc), doc, tracer, stepId, resumeFrom));
            if (loopResult.success()) {
                AnalysisResult result = parseResult(loopResult.finalAnswer());
                tracer.end(stepId, "loop completed: rounds=" + loopResult.rounds()
                        + ", toolCalls=" + loopResult.toolCalls()
                        + ", tokens=" + loopResult.tokensUsed()
                        + ", citations=" + result.citations().size(), null);
                return new LoopOutcome(result, loopResult.observations(), loopResult.tokensUsed());
            }
            log.warn("Loop stopped, runId={}, reason={}, observations={}",
                    runId, loopResult.stopReason(), loopResult.observations().size());
            tracer.end(stepId, "loop stopped(" + loopResult.stopReason() + "), carrying "
                    + loopResult.observations().size() + " fragments → fallback", loopResult.stopReason());
            return new LoopOutcome(null, loopResult.observations(), loopResult.tokensUsed());
        } catch (Exception ex) {
            log.warn("Loop analysis failed, falling back, runId={}: {}", runId, ex.getMessage());
            tracer.end(stepId, "loop failed → fallback direct LLM", ex.getMessage());
            return new LoopOutcome(null, List.of(), null);
        } finally {
            ReActContextHolder.clear();
            ToolExecutionHolder.clear();
        }
    }

    /** 降级 1：单次 LLM 调用。若循环已读片段则一并携带——降级不丢上下文。 */
    private LlmOutcome runDirectLlm(String runId, AgentRun run, ParsedDocument doc,
                                    SkillDefinition skill, TraceRecorder tracer,
                                    List<AgentLoop.ObservedFragment> observations) {
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            return new LlmOutcome(null, null);
        }
        String stepId = tracer.begin("DIRECT_LLM", null);
        tracer.recordMeta(stepId, true, "SpringAI");
        try {
            String carried = renderObservations(observations);
            String userContent = "用户要求：" + run.getInstruction()
                    + (carried.isEmpty() ? "" : "\n\nAgent 此前已阅读的片段（降级续读，勿重复阅读）：\n" + carried)
                    + "\n\n文档内容（[节ID] 标记了各节，引用时使用节ID）：\n" + renderWithSectionIds(doc);
            LlmResponse response = llmGateway.invoke(
                    "llm." + skill.name(), skill.directPromptFile(), userContent, runId);
            AnalysisResult result = parseResult(response.content());
            long tokens = response.promptTokens() + response.completionTokens();
            tracer.end(stepId, "direct llm completed, tokens=" + tokens
                    + (carried.isEmpty() ? "" : ", carried=" + observations.size() + " fragments"), null);
            return new LlmOutcome(result, tokens);
        } catch (Exception ex) {
            log.warn("Direct LLM failed, falling back to rule, runId={}: {}", runId, ex.getMessage());
            tracer.end(stepId, "direct LLM failed → fallback rule", ex.getMessage());
            return new LlmOutcome(null, null);
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
                citations);
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
        return new AnalysisResult(result.summary(), result.keyPoints(), result.risks(),
                result.suggestions(), List.copyOf(kept));
    }

    // --- 结果解析 ---

    /** 容错解析 LLM 输出：剥代码围栏、截取最外层 JSON 对象。 */
    AnalysisResult parseResult(String content) {
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
            RawResult raw = objectMapper.readValue(json.substring(start, end + 1), RawResult.class);
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
            return new AnalysisResult(
                    raw.summary,
                    raw.keyPoints == null ? List.of() : raw.keyPoints,
                    raw.risks == null ? List.of() : raw.risks,
                    raw.suggestions == null ? List.of() : raw.suggestions,
                    citations);
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawResult {
        public String summary;
        public List<String> keyPoints;
        public List<String> risks;
        public List<String> suggestions;
        public List<RawCitation> citations;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawCitation {
        public String sectionId;
        public String quote;
    }
}
