package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.api.BusinessException;
import com.gcll.docagent.api.ErrorCode;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.domain.AgentRunStatus;
import com.gcll.docagent.langchain4j.ReActContextHolder;
import com.gcll.docagent.langchain4j.SkillAssistant;
import com.gcll.docagent.langchain4j.SkillAssistantFactory;
import com.gcll.docagent.llm.LlmGateway;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文档分析编排——一次 run 的完整生命周期：
 * <pre>
 * PARSE（同步，失败直接 400）
 *   → REACT_ANALYZE（LangChain4j 工具循环，LLM 自主读文档）
 *     失败 → DIRECT_LLM（Spring AI 单次调用，全文截断进上下文）
 *       失败 → RULE_FALLBACK（纯规则摘要，无 LLM 也能出结果）
 *   → CITATION_VERIFY（引用与真实节对齐，剔除编造引用）
 *   → REPORT（结果落库 + 终态）
 * </pre>
 * 每一步经 {@link TraceRecorder} 落 agent_step 表并推 SSE；三级降级体现在 executionMode
 * （REACT / LLM / FALLBACK），是"LLM 不可用也能演示"的保底设计。
 */
@Service
public class DocumentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(DocumentAnalysisService.class);

    private final DocumentParsingService parsingService;
    private final DocumentStore documentStore;
    private final TraceRecorderFactory traceRecorderFactory;
    private final AgentRunRepository agentRunRepository;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final SkillRegistry skillRegistry;
    private final SkillAssistantFactory assistantFactory;
    private final boolean reactEnabled;

    private final ExecutorService executor = Executors.newFixedThreadPool(3, r -> {
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
            SkillAssistantFactory assistantFactory,
            @Value("${docagent.analysis.react-enabled:true}") boolean reactEnabled) {
        this.parsingService = parsingService;
        this.documentStore = documentStore;
        this.traceRecorderFactory = traceRecorderFactory;
        this.agentRunRepository = agentRunRepository;
        this.objectMapper = objectMapper;
        this.llmGatewayProvider = llmGatewayProvider;
        this.skillRegistry = skillRegistry;
        this.assistantFactory = assistantFactory;
        this.reactEnabled = reactEnabled;
    }

    /** 提交分析：同步解析 + 建档，异步执行。返回 runId 供轮询/SSE 订阅。 */
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
        agentRunRepository.save(run);
        documentStore.put(run.getId(), doc);

        executor.submit(() -> executeRun(run.getId(), doc, skill));
        log.info("Analysis run submitted, runId={}, skill={}, file={}, sections={}",
                run.getId(), skill.name(), fileName, doc.sections().size());
        return run;
    }

    // --- 异步执行 ---

    private void executeRun(String runId, ParsedDocument doc, SkillDefinition skill) {
        AgentRun run = agentRunRepository.findById(runId).orElse(null);
        if (run == null) {
            log.error("Run disappeared before execution, runId={}", runId);
            return;
        }
        TraceRecorder tracer = traceRecorderFactory.create(run);
        try {
            run.setStatus(AgentRunStatus.ANALYZING);
            agentRunRepository.save(run);

            traceParse(tracer, doc);

            AnalysisResult result = null;
            String mode = null;

            // ReAct 阶段
            if (reactEnabled) {
                AnalysisResult r = runReact(runId, run, doc, skill, tracer);
                if (r != null) {
                    result = r;
                    mode = "REACT";
                }
            }
            // 降级 1：直连 LLM（单次调用，全文进上下文）
            if (result == null) {
                AnalysisResult r = runDirectLlm(runId, run, doc, skill, tracer);
                if (r != null) {
                    result = r;
                    mode = "LLM";
                }
            }
            // 降级 2：纯规则摘要（无 LLM 也能出结果）
            if (result == null) {
                result = ruleFallback(doc, tracer);
                mode = "FALLBACK";
            }

            // 引用校验：剔除指向不存在节的引用
            result = verifyCitations(result, doc, tracer);

            run.setResultJson(objectMapper.writeValueAsString(result));
            run.setExecutionMode(mode);
            run.setCurrentSummary(result.summary());
            run.setStatus(AgentRunStatus.COMPLETED);
            run.setFinishedAt(Instant.now());
            agentRunRepository.save(run);

            String reportStep = tracer.begin("REPORT", null);
            tracer.end(reportStep, "executionMode=" + mode + ", citations=" + result.citations().size(), null);
            log.info("Analysis run completed, runId={}, mode={}, cost summary chars={}",
                    runId, mode, result.summary().length());
        } catch (Exception fatal) {
            log.error("Analysis run failed, runId={}", runId, fatal);
            run.setStatus(AgentRunStatus.FAILED);
            run.setLastError(fatal.getClass().getSimpleName() + ": " + fatal.getMessage());
            run.setFinishedAt(Instant.now());
            agentRunRepository.save(run);
            String failStep = tracer.begin("RUN_FAILED", null);
            tracer.end(failStep, run.getLastError(), run.getLastError());
        }
    }

    private void traceParse(TraceRecorder tracer, ParsedDocument doc) {
        String step = tracer.begin("PARSE", null);
        tracer.recordInput(step, "file=" + doc.fileName() + ", type=" + doc.fileType());
        tracer.end(step, "sections=" + doc.sections().size() + ", chars=" + doc.totalChars(), null);
    }

    /** ReAct 循环：LLM 通过技能声明的工具集自主阅读与分析。 */
    private AnalysisResult runReact(String runId, AgentRun run, ParsedDocument doc, SkillDefinition skill, TraceRecorder tracer) {
        SkillAssistant assistant = assistantFactory.assistantFor(skill);
        String stepId = tracer.begin("REACT_ANALYZE", null);
        tracer.recordMeta(stepId, true, "LangChain4j");
        try {
            ToolExecutionHolder.setRunId(runId);
            ReActContextHolder.set(skill.reactSystemPrompt(), tracer, stepId);
            String userMessage = "用户要求：" + run.getInstruction() + "\n\n文档大纲：\n" + doc.outline();
            String answer = assistant.analyze(userMessage);
            AnalysisResult result = parseResult(answer);
            tracer.end(stepId, "react completed, citations=" + result.citations().size(), null);
            return result;
        } catch (Exception ex) {
            log.warn("ReAct analysis failed, falling back to direct LLM, runId={}: {}", runId, ex.getMessage());
            tracer.end(stepId, "ReAct failed → fallback direct LLM", ex.getMessage());
            return null;
        } finally {
            ReActContextHolder.clear();
            ToolExecutionHolder.clear();
        }
    }

    /** 降级 1：单次 LLM 调用，全文（按模型窗口截断）+ 要求进上下文。 */
    private AnalysisResult runDirectLlm(String runId, AgentRun run, ParsedDocument doc, SkillDefinition skill, TraceRecorder tracer) {
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            return null;
        }
        String stepId = tracer.begin("DIRECT_LLM", null);
        tracer.recordMeta(stepId, true, "SpringAI");
        try {
            String userContent = "用户要求：" + run.getInstruction()
                    + "\n\n文档内容（[节ID] 标记了各节，引用时使用节ID）：\n" + renderWithSectionIds(doc);
            LlmResponse response = llmGateway.invoke(
                    "llm." + skill.name(), skill.directPromptFile(), userContent, runId);
            AnalysisResult result = parseResult(response.content());
            tracer.end(stepId, "direct llm completed", null);
            return result;
        } catch (Exception ex) {
            log.warn("Direct LLM failed, falling back to rule, runId={}: {}", runId, ex.getMessage());
            tracer.end(stepId, "direct LLM failed → fallback rule", ex.getMessage());
            return null;
        }
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
                // 节ID无效但引文能定位到唯一节：重新挂到真实节
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
