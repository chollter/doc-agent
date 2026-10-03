package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.llm.context.ContextAssembler;
import com.gcll.docagent.observability.trace.TraceRecorder;
import com.gcll.docagent.parsing.ParsedDocument;
import com.gcll.docagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 结果装配——把 LLM 候选结论与代码确定性事实（对齐矩阵/证据评估/红旗/画像定义）合成为最终漏斗裁决。
 * <p>从编排服务剥离：装配是"多源事实合并 + 反编造校验"的独立关注点，与降级链的控制流无关。
 * 落地性校验（grounding/引文锚定）无论降级与否都执行——不变量不允许豁免。
 */
@Component
public class ResultAssembler {

    private static final Logger log = LoggerFactory.getLogger(ResultAssembler.class);

    private final AlignmentAnalyzer alignmentAnalyzer;
    private final EvidenceAssessmentService evidenceAssessmentService;
    private final DirectionRecommender directionRecommender;
    private final GroundingValidator groundingValidator;
    private final ExperienceEvidenceEnricher evidenceEnricher;
    private final FunnelFieldsMapper funnelFieldsMapper;
    private final PromptBuilder promptBuilder;
    private final ContextAssembler contextAssembler;
    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final ObjectMapper objectMapper;

    public ResultAssembler(AlignmentAnalyzer alignmentAnalyzer,
                           EvidenceAssessmentService evidenceAssessmentService,
                           DirectionRecommender directionRecommender,
                           GroundingValidator groundingValidator,
                           ExperienceEvidenceEnricher evidenceEnricher,
                           FunnelFieldsMapper funnelFieldsMapper,
                           PromptBuilder promptBuilder,
                           ContextAssembler contextAssembler,
                           ObjectProvider<LlmGateway> llmGatewayProvider,
                           ObjectMapper objectMapper) {
        this.alignmentAnalyzer = alignmentAnalyzer;
        this.evidenceAssessmentService = evidenceAssessmentService;
        this.directionRecommender = directionRecommender;
        this.groundingValidator = groundingValidator;
        this.evidenceEnricher = evidenceEnricher;
        this.funnelFieldsMapper = funnelFieldsMapper;
        this.promptBuilder = promptBuilder;
        this.contextAssembler = contextAssembler;
        this.llmGatewayProvider = llmGatewayProvider;
        this.objectMapper = objectMapper;
    }

    public AnalysisResult assembleResumeResult(String runId, AgentRun run, ParsedDocument document,
                                                ResumeContext resumeCtx,
                                                TraceRecorder tracer, AnalysisResult result,
                                                LlmFunnelFields funnelFields) {
        // P12 漏斗结论：红旗 + 方向画像 + LLM 五角度输出 → FunnelVerdict
        // 按技能判断而非实体非空：降级时空实体仍需组装（LLM 五角度输出基于直读原文，不该陪葬）
        {
            // 新增：对齐分析（优先使用对齐矩阵，降级时回退到LLM五角度）
            List<AlignmentEntry> alignmentMatrix = List.of();
            if (resumeCtx.targetProfile() != null && !resumeCtx.targetProfile().requirements().isEmpty()) {
                String alignStep = tracer.begin("ALIGNMENT_ANALYSIS", null);
                tracer.recordMeta(alignStep, true, "AlignmentAnalyzer");
                try {
                    alignmentMatrix = alignmentAnalyzer.align(
                            resumeCtx.targetProfile(),
                            resumeCtx.entities(),
                            resumeCtx.fullText(),
                            runId
                    );
                    tracer.end(alignStep, "aligned " + alignmentMatrix.size() + " requirements", null);
                } catch (Exception ex) {
                    tracer.end(alignStep, "alignment failed: " + ex.getMessage(), ex.getMessage());
                    log.warn("Alignment analysis failed, using LLM fallback, runId={}: {}", runId, ex.getMessage());
                }
            }

            List<EvidenceAssessment> evidenceAssessments = evidenceEnricher.enrich(
                    runId, document, tracer,
                    evidenceAssessmentService.assess(resumeCtx.entities(), resumeCtx.fullText()),
                    funnelFields.experienceStrength());

            // 从对齐矩阵转换为现有的coverage格式（兼容现有流程）
            List<MustHaveCoverage> coverage = !alignmentMatrix.isEmpty()
                    ? alignmentMatrix.stream()
                            .map(entry -> new MustHaveCoverage(
                                    entry.requirementId(),
                                    entry.requirement(),
                                    entry.status(),
                                    entry.evidence().isEmpty() ? null : entry.evidence().get(0).claim(),
                                    entry.evidence().isEmpty() ? null : entry.evidence().get(0).sectionId()
                            ))
                            .toList()
                    : mergeCoverage(resumeCtx.targetProfile(), funnelFields.mustHaveCoverage());

            List<RequirementVerdict> requirementVerdicts = evidenceAssessmentService
                    .assessRequirements(coverage, evidenceAssessments, resumeCtx.targetProfile());

            FunnelVerdict.Evaluation evaluation = evaluateResume(
                    run, resumeCtx, result, funnelFields, requirementVerdicts, tracer);

            if (evaluation != null && evaluation.overall() != null && !evaluation.overall().isBlank()) {
                // 对外总评使用裁决之后生成的文本，避免候选分析阶段的乐观判断成为最终结论。
                result = result.withSummary(evaluation.overall().trim());
            }

            // 从对齐矩阵生成建议（优先级已排序）
            List<ActionableSuggestion> suggestions = !alignmentMatrix.isEmpty()
                    ? alignmentMatrix.stream()
                            .filter(entry -> entry.fix() != null)
                            .sorted(java.util.Comparator.comparingInt(e -> -e.fix().roiScore()))
                            .map(entry -> new ActionableSuggestion(
                                    entry.status() == MustHaveCoverage.Status.MISSING ? "HIGH" : "MEDIUM",
                                    entry.requirement(),
                                    entry.evidence().isEmpty() ? null : entry.evidence().get(0).sectionId(),
                                    entry.fix().before(),
                                    entry.fix().after(),
                                    entry.fix().reason(),
                                    entry.fix().roiScore(),
                                    entry.fix().type().name(),
                                    entry.fix().effort()
                            ))
                            .toList()
                    : (result.actionableSuggestions() == null ? List.of() : result.actionableSuggestions());

            result = result.withActionableSuggestions(suggestions);

            FunnelVerdict verdict = assembleVerdict(resumeCtx, funnelFields,
                    suggestions, evaluation, evidenceAssessments, requirementVerdicts);
            result = result.withProfile(resumeCtx.profile())
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
        return result;
    }

    /**
     * 组装漏斗结论：LLM 输出 + 代码事实（红旗/词汇diff/画像定义）合成。
     * 部分降级：实体抽取失败只损失实体级精度——红旗已切换为文本级粗查（executeClaimed）、
     * 画像缺失，LLM 直读原文产出的五角度保留，degraded 标记显式告知。
     * 落地性校验（反编造/引文锚定）无论降级与否都执行——不变量不允许豁免。
     */
    private FunnelVerdict assembleVerdict(ResumeContext ctx, LlmFunnelFields fields,
                                          List<ActionableSuggestion> suggestions,
                                          FunnelVerdict.Evaluation evaluation,
                                          List<EvidenceAssessment> evidenceAssessments,
                                          List<RequirementVerdict> requirementVerdicts) {
        List<RedFlag> redFlags = ctx.redFlags();
        // 方向建议:锚定简历自身证据,grounding 校验 + tier 由代码判定(不依赖岗位基准)
        List<DirectionRecommendation> recommendedDirections = directionRecommender.recommend(
                fields.directionProposals(), ctx.fullText(), evidenceAssessments);

        return new FunnelVerdict(
                redFlags,
                ctx.matchMode(),
                ctx.archetype() != null ? ctx.archetype().getId() : null,
                requirementVerdicts, fields.positioning(),
                fields.experienceStrength(),
                StrengthStats.from(fields.experienceStrength()),
                fields.presentation(),
                fields.leverageCards(),
                ctx.degraded(),
                groundingValidator.validate(suggestions, ctx.fullText()),
                evaluation,
                evidenceAssessments,
                recommendedDirections);
    }

    /**
     * 评价专调（v7）：简历全文 + 代码事实 + 主分析结论 → 深度定性评价。
     * <p>v6 把 evaluation 塞进主分析大 JSON——十几个字段挤一次输出，评价被契约
     * 压成每条 30 字的一句话敷衍（用户实测反馈）。v7 拆专职调用：全文输入、
     * 单一职责、每维 80-150 字且必须引原文。
     * <p>失败不级联：无网关/异常/空产出时返回 null，前端容忍缺字段（与历史 v5 run 同形态）。
     */
    private FunnelVerdict.Evaluation evaluateResume(AgentRun run, ResumeContext ctx, AnalysisResult result,
                                                    LlmFunnelFields funnel,
                                                    List<RequirementVerdict> requirementVerdicts,
                                                    TraceRecorder tracer) {
        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null || ctx.fullText() == null || ctx.fullText().isBlank()) {
            return null;
        }
        String stepId = tracer.begin("EVALUATION_LLM", null);
        tracer.recordMeta(stepId, true, "SpringAI");
        try {
            // 预算与 DIRECT_LLM 同策略：配置正数覆盖，否则按目标模型窗口派生
            int budget = contextAssembler.budgetOverride() > 0 ? contextAssembler.budgetOverride()
                    : Math.max(0, llmGateway.budgetForCall("llm.resume-evaluation", "resume-evaluation.txt"));
            ContextAssembler.AssembledContext assembled = contextAssembler.assemble("EVALUATION",
                    promptBuilder.buildEvaluationSegments(run, ctx, result, funnel, requirementVerdicts), budget);
            tracer.recordInput(stepId, assembled.snapshot().describe());
            LlmResponse response = llmGateway.invoke("llm.resume-evaluation", "resume-evaluation.txt",
                    assembled.text(), run.getId());
            FunnelVerdict.Evaluation evaluation = funnelFieldsMapper.parseEvaluationJson(response.content());
            tracer.end(stepId, evaluation != null ? "evaluation generated" : "evaluation empty", null);
            return evaluation;
        } catch (Exception ex) {
            tracer.end(stepId, "evaluation failed: " + ex.getMessage(), ex.getMessage());
            log.warn("Evaluation LLM failed, runId={}: {}", run.getId(), ex.getMessage());
            return null;
        }
    }

    /** 要求文本以画像定义为准（LLM 只给 id/status/evidence），防转录走样。 */
    private static List<MustHaveCoverage> mergeCoverage(TargetProfile target, List<MustHaveCoverage> llm) {
        if (target == null) {
            return llm;
        }
        return CoverageMerge.merge(target, llm);
    }
}
