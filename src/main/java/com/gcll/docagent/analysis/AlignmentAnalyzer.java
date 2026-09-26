package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 对齐分析器：将JD/方向要求与简历能力逐条对齐。
 * <p>核心流程：
 * <pre>
 * 1. 对每条要求，在简历实体中找候选证据（关键词匹配）
 * 2. 用EvidenceAssessmentService评估证据强度
 * 3. 调LLM判断覆盖状态+生成差距描述
 * 4. 生成改进建议（ENHANCE/ADD/REFRAME）+ 计算ROI
 * </pre>
 */
@Service
public class AlignmentAnalyzer {

    private static final Logger log = LoggerFactory.getLogger(AlignmentAnalyzer.class);

    private final EvidenceAssessmentService evidenceService;
    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final ObjectMapper objectMapper;

    public AlignmentAnalyzer(EvidenceAssessmentService evidenceService,
                            ObjectProvider<LlmGateway> llmGatewayProvider,
                            ObjectMapper objectMapper) {
        this.evidenceService = evidenceService;
        this.llmGatewayProvider = llmGatewayProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 核心方法：逐条对齐要求与简历能力。
     */
    public List<AlignmentEntry> align(TargetProfile targetProfile,
                                     ResumeEntities entities,
                                     String fullText,
                                     String runId) {
        if (targetProfile == null || targetProfile.requirements().isEmpty()) {
            log.warn("Target profile empty, skip alignment, runId={}", runId);
            return List.of();
        }

        List<AlignmentEntry> matrix = new ArrayList<>();
        for (TargetProfile.Requirement req : targetProfile.requirements()) {
            AlignmentEntry entry = alignOne(req, entities, fullText, runId);
            matrix.add(entry);
        }

        log.info("Alignment completed, runId={}, requirements={}, full={}, partial={}, missing={}",
                runId, matrix.size(),
                matrix.stream().filter(e -> e.status() == MustHaveCoverage.Status.MET).count(),
                matrix.stream().filter(e -> e.status() == MustHaveCoverage.Status.PARTIAL).count(),
                matrix.stream().filter(e -> e.status() == MustHaveCoverage.Status.MISSING).count());

        return matrix;
    }

    /**
     * 对齐单条要求。
     */
    private AlignmentEntry alignOne(TargetProfile.Requirement req,
                                   ResumeEntities entities,
                                   String fullText,
                                   String runId) {
        // 1. 找候选证据
        List<EvidenceCandidate> candidates = findEvidence(req, entities, fullText);

        // 2. 评估证据强度
        List<EvidenceAssessment> assessed = candidates.stream()
                .map(c -> evidenceService.assessProject(c.project(), fullText))
                .toList();

        // 3. 判断覆盖度
        CoverageJudgment judgment = judgeCoverage(req, assessed, fullText, runId);

        // 4. 生成改进建议
        AlignmentEntry.SuggestionFix fix = generateFix(req, judgment, assessed, candidates);

        return new AlignmentEntry(
                req.id(),
                req.requirement(),
                req.priority(),
                judgment.status(),
                assessed,
                judgment.coverage(),
                judgment.gap(),
                fix
        );
    }

    /**
     * 找候选证据：在项目中匹配关键词，按匹配度排序。
     */
    private List<EvidenceCandidate> findEvidence(TargetProfile.Requirement req,
                                                 ResumeEntities entities,
                                                 String fullText) {
        List<String> keywords = new ArrayList<>();
        keywords.addAll(req.keywords());
        keywords.addAll(req.evidenceExpected());

        List<EvidenceCandidate> candidates = new ArrayList<>();

        for (ResumeProjectFact project : entities.getProjects()) {
            int matches = countKeywordMatches(project, keywords);
            if (matches > 0) {
                candidates.add(new EvidenceCandidate(project, matches));
            }
        }

        // 按匹配度排序，取top 3
        return candidates.stream()
                .sorted(Comparator.comparingInt(c -> -c.matchScore()))
                .limit(3)
                .toList();
    }

    /**
     * 统计关键词在项目中的匹配数。
     */
    private int countKeywordMatches(ResumeProjectFact project, List<String> keywords) {
        String projectText = buildProjectText(project).toLowerCase(Locale.ROOT);
        int count = 0;
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank()
                    && projectText.contains(keyword.toLowerCase(Locale.ROOT))) {
                count++;
            }
        }
        return count;
    }

    private String buildProjectText(ResumeProjectFact project) {
        StringBuilder sb = new StringBuilder();
        if (project.projectId() != null) sb.append(project.projectId()).append(" ");
        appendFact(sb, project.context());
        appendFact(sb, project.problem());
        appendFacts(sb, project.responsibilities());
        appendFacts(sb, project.technologies());
        appendFact(sb, project.aiPipeline());
        appendFacts(sb, project.decisions());
        appendFact(sb, project.results());
        appendFact(sb, project.scale());
        appendFact(sb, project.deployment());
        return sb.toString();
    }

    private void appendFact(StringBuilder sb, ResumeProjectFact.Fact fact) {
        if (fact != null && fact.value() != null) sb.append(fact.value()).append(" ");
    }

    private void appendFacts(StringBuilder sb, List<ResumeProjectFact.Fact> facts) {
        if (facts != null) facts.forEach(f -> appendFact(sb, f));
    }

    /**
     * LLM判断覆盖度：给定要求+简历证据 → 状态+差距描述。
     */
    private CoverageJudgment judgeCoverage(TargetProfile.Requirement req,
                                          List<EvidenceAssessment> evidence,
                                          String fullText,
                                          String runId) {
        if (evidence.isEmpty()) {
            return CoverageJudgment.missing("简历未提及「" + req.requirement() + "」相关经历");
        }

        LlmGateway gateway = llmGatewayProvider.getIfAvailable();
        if (gateway == null) {
            // 降级：基于证据等级简单判断
            return fallbackJudgment(evidence);
        }

        try {
            String prompt = buildCoveragePrompt(req, evidence);
            LlmResponse resp = gateway.invoke("llm.coverage-judge", "coverage-judge.txt", prompt, runId);
            return parseCoverageResponse(resp.content());
        } catch (Exception ex) {
            log.warn("Coverage judgment LLM failed, using fallback, req={}: {}",
                    req.id(), ex.getMessage());
            return fallbackJudgment(evidence);
        }
    }

    private String buildCoveragePrompt(TargetProfile.Requirement req,
                                      List<EvidenceAssessment> evidence) {
        StringBuilder sb = new StringBuilder();
        sb.append("岗位要求：").append(req.requirement()).append("\n");
        sb.append("优先级：").append(req.priority()).append("\n");
        if (!req.evidenceExpected().isEmpty()) {
            sb.append("期望证据：").append(String.join("、", req.evidenceExpected())).append("\n");
        }
        sb.append("\n简历相关内容：\n");
        for (int i = 0; i < evidence.size(); i++) {
            EvidenceAssessment e = evidence.get(i);
            sb.append(i + 1).append(". [").append(e.sectionId()).append("] ")
              .append(e.claim()).append("\n");
            sb.append("   证据等级：").append(e.evidenceLevel()).append("\n");
            if (!e.evidenceFound().isEmpty()) {
                sb.append("   原文片段：").append(truncate(e.evidenceFound().get(0), 100)).append("\n");
            }
        }

        sb.append("\n请判断该要求的覆盖情况，输出JSON：\n");
        sb.append("{\n");
        sb.append("  \"status\": \"FULL|PARTIAL|MISSING\",\n");
        sb.append("  \"coverage\": 0.0-1.0,\n");
        sb.append("  \"gap\": \"如果非FULL，说明具体缺少什么\"\n");
        sb.append("}\n");
        sb.append("\n判断规则：\n");
        sb.append("- FULL：简历明确覆盖该要求，有具体案例+可核验证据\n");
        sb.append("- PARTIAL：简历提到相关内容但不完整，缺细节或深度不够\n");
        sb.append("- MISSING：简历未提及或仅有空话关键词\n");
        sb.append("- gap字段必须具体说明缺什么（如\"缺服务拆分细节\"），不要泛泛而谈");

        return sb.toString();
    }

    private CoverageJudgment parseCoverageResponse(String content) throws Exception {
        String json = stripFences(content);
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON in coverage response");
        }

        RawCoverageJudgment raw = objectMapper.readValue(
                json.substring(start, end + 1), RawCoverageJudgment.class);

        if (raw.status == null || raw.status.isBlank()) {
            throw new IllegalArgumentException("Missing status field");
        }

        MustHaveCoverage.Status status;
        try {
            status = MustHaveCoverage.Status.valueOf(raw.status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Invalid status: " + raw.status);
        }

        double coverage = raw.coverage == null ? 0.0 : Math.max(0, Math.min(1, raw.coverage));
        String gap = raw.gap == null ? "" : raw.gap.trim();

        return new CoverageJudgment(status, coverage, gap);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawCoverageJudgment {
        public String status;
        public Double coverage;
        public String gap;
    }

    private CoverageJudgment fallbackJudgment(List<EvidenceAssessment> evidence) {
        // 降级逻辑：有L3+证据→FULL，有L2证据→PARTIAL，否则→MISSING
        EvidenceLevel maxLevel = evidence.stream()
                .map(EvidenceAssessment::evidenceLevel)
                .max(Comparator.naturalOrder())
                .orElse(EvidenceLevel.L0_KEYWORD);

        if (maxLevel.ordinal() >= EvidenceLevel.L3_RESULT.ordinal()) {
            return CoverageJudgment.full(0.9);
        } else if (maxLevel.ordinal() >= EvidenceLevel.L2_METHOD.ordinal()) {
            return CoverageJudgment.partial(0.6, "有相关经历但缺少结果数据");
        } else if (maxLevel.ordinal() >= EvidenceLevel.L1_ACTIVITY.ordinal()) {
            return CoverageJudgment.partial(0.3, "仅提到相关活动，缺乏具体细节");
        } else {
            return CoverageJudgment.missing("简历中仅有关键词，无实质性证据");
        }
    }

    /**
     * 生成改进建议 + 计算ROI。
     */
    private AlignmentEntry.SuggestionFix generateFix(TargetProfile.Requirement req,
                                                     CoverageJudgment judgment,
                                                     List<EvidenceAssessment> evidence,
                                                     List<EvidenceCandidate> candidates) {
        if (judgment.status() == MustHaveCoverage.Status.MET) {
            return null;  // 已满足，无需建议
        }

        // 计算ROI：优先级权重 × (1 - 覆盖度) × 可修复性
        int priorityWeight = "MUST".equals(req.priority()) ? 15
                : "IMPORTANT".equals(req.priority()) ? 10 : 5;
        int coverageGap = (int)((1.0 - judgment.coverage()) * priorityWeight);

        boolean hasEvidence = !evidence.isEmpty();
        boolean constrainedPhase = hasEvidence && hasConstraintPhase(evidence, candidates);
        AlignmentEntry.FixType type;
        String effort;
        int roi;

        if (constrainedPhase) {
            // 简历已写明建设期/移交等约束——业务指标不存在也不该编，改口径而非索要数字
            type = AlignmentEntry.FixType.REFRAME;
            effort = "LOW";
            roi = coverageGap / 2;  // 只能改表述，覆盖度提升有限
        } else if (hasEvidence) {
            // 有证据但不足 → ENHANCE（补充细节）
            type = AlignmentEntry.FixType.ENHANCE;
            effort = "LOW";  // 现有内容加细节，5-10分钟
            roi = coverageGap;  // 全部ROI
        } else {
            // 无证据 → ADD（需要新案例）
            type = AlignmentEntry.FixType.ADD;
            effort = "HIGH";  // 需要新经历，可能做不到
            roi = coverageGap / 3;  // ROI打折（可能补不了）
        }

        String before = hasEvidence ? evidence.get(0).claim() : "";
        String after = generateAfterExample(req, judgment.gap(), hasEvidence, constrainedPhase);

        return new AlignmentEntry.SuggestionFix(
                type, before, after, roi, effort, judgment.gap());
    }

    /**
     * 生成改写示例（简化版，真实场景可调LLM生成）。
     */
    private String generateAfterExample(TargetProfile.Requirement req,
                                       String gap,
                                       boolean hasEvidence,
                                       boolean constrainedPhase) {
        // after 会被采纳引擎直接替换进修改稿，必须是可粘贴文稿；缺的事实用【填：…】槽位
        // （GroundingValidator 豁免【】内数字），不把"补充xx"这类指令句混进文稿。
        if (constrainedPhase) {
            return "写明项目阶段与个人边界：项目处于【填：阶段，如建设期/试点期】，"
                    + "本人完成【填：已交付的工作】后移交【填：接手方】，在线业务指标由后续阶段产生";
        }
        if (hasEvidence) {
            return "保留既有事实并补上可验证结果：【填：结果指标与具体数值】";
        } else {
            return "补一段「" + req.requirement() + "」的真实案例：【填：案例背景、你的角色、可验证结果】";
        }
    }

    /**
     * 约束阶段口径：简历已说明项目未上线/建设期/移交——业务指标事实上不存在，
     * 再出【填：结果指标】就是逼用户编数字（违反 grounding 原则），应改为写清约束。
     */
    private static final List<String> CONSTRAINT_PHASE_MARKERS = List.of(
            "未上线", "建设期", "移交", "交接给", "交付后", "试点阶段", "开发阶段", "尚未上线");

    private boolean hasConstraintPhase(List<EvidenceAssessment> evidence, List<EvidenceCandidate> candidates) {
        boolean inEvidence = evidence.stream().anyMatch(e -> {
            String text = (e.claim() == null ? "" : e.claim())
                    + " " + (e.sourceQuote() == null ? "" : e.sourceQuote());
            return containsMarker(text);
        });
        // 约束口径常写在 results/context 槽位而非 claim——项目全文兜底
        boolean inProjectText = candidates.stream()
                .anyMatch(c -> containsMarker(buildProjectText(c.project())));
        return inEvidence || inProjectText;
    }

    private static boolean containsMarker(String text) {
        return text != null && CONSTRAINT_PHASE_MARKERS.stream().anyMatch(text::contains);
    }

    private String stripFences(String content) {
        String s = content == null ? "" : content.trim();
        if (s.startsWith("```")) {
            int firstLineEnd = s.indexOf('\n');
            if (firstLineEnd > 0) s = s.substring(firstLineEnd + 1);
            int fenceEnd = s.lastIndexOf("```");
            if (fenceEnd >= 0) s = s.substring(0, fenceEnd);
        }
        return s.trim();
    }

    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    /**
     * 证据候选：项目+匹配分数。
     */
    record EvidenceCandidate(ResumeProjectFact project, int matchScore) {}
}
