package com.gcll.ticketagent.triage;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.priority.PriorityEvaluationService;
import com.gcll.ticketagent.governance.priority.PriorityResult;
import com.gcll.ticketagent.governance.priority.TicketPriority;
import com.gcll.ticketagent.governance.routing.RoutingPolicyEngine;
import com.gcll.ticketagent.governance.routing.RoutingSuggestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 分诊 Pipeline——阶段1 的核心入口。
 * <p>
 * 目标：秒级出结果，回答"这是什么？多急？路由给谁？"。
 * <p>
 * Pipeline 四步：
 * <ol>
 *   <li>规则前置：关键词/正则 → 零LLM，命中直接返回</li>
 *   <li>LLM 分类+粗抽：宏观判断 issueType + 关键字段粗抽取</li>
 *   <li>定向精抽：按 issueType 动态 prompt 抽取该类型所需的精确字段</li>
 *   <li>规则校验：一致性检查 + 矛盾检测 → 修正或标记低置信度</li>
 * </ol>
 * <p>
 * 优先级在抽取后立即评估（纯规则，毫秒级），不依赖根因分析结果。
 * 追问四层兜底：P0不追问 / 非P0追问 / 超时5分钟强制 / 最多2轮。
 */
@Service
public class TriagePipeline {

    private static final Logger log = LoggerFactory.getLogger(TriagePipeline.class);
    private static final int MAX_FOLLOW_UP_ROUNDS = 2;

    private final RuleBasedTriageEngine ruleEngine;
    private final PriorityEvaluationService priorityService;
    private final RoutingPolicyEngine routingPolicyEngine;
    // LLM 分类+粗抽 和 定向精抽 将在 Day6 实现，当前为预留接口

    public TriagePipeline(RuleBasedTriageEngine ruleEngine,
                          PriorityEvaluationService priorityService,
                          RoutingPolicyEngine routingPolicyEngine) {
        this.ruleEngine = ruleEngine;
        this.priorityService = priorityService;
        this.routingPolicyEngine = routingPolicyEngine;
    }

    /**
     * 执行分诊。
     *
     * @param content  工单原始内容
     * @param extract  结构化抽取结果（由 TicketExtractService 产出）
     * @param runId    工单运行 ID
     * @param followUpRound 当前追问轮次（0=首次，1=第一次追问后，2=第二次追问后）
     * @return 分诊结果
     */
    public TriageResult execute(String content, TicketExtractResult extract, String runId, int followUpRound) {
        log.info("triage pipeline started, runId={}, round={}", runId, followUpRound);

        // Step 1: 规则前置（零LLM）
        TriageResult ruleResult = ruleEngine.triage(content);
        if (ruleResult != null) {
            log.info("triage rule hit, runId={}, issueType={}, priority={}", runId, ruleResult.issueType(), ruleResult.priority());
            // 规则命中后补全优先级评估（以抽取结果为准，更精确）
            return enrichWithExtract(ruleResult, extract, runId, followUpRound);
        }

        // Step 2-3: LLM 分类+粗抽 → 定向精抽（Day6 实现）
        // 当前降级：从 extract 直接取
        TriageResult result = buildFromExtract(extract, runId, followUpRound);

        // Step 4: 规则校验（一致性检查 + 矛盾检测）
        result = validate(result, extract);

        log.info("triage completed, runId={}, issueType={}, priority={}, source={}",
                runId, result.issueType(), result.priority(), result.source());
        return result;
    }

    /**
     * 追问策略——四层兜底。
     * <p>
     * 决策表：
     * <ul>
     *   <li>P0 → 不追问，直接进排查（时间优先）</li>
     *   <li>非P0 + 关键字段缺失 → 追问（最多2轮）</li>
     *   <li>追问超时5分钟 → 强制推进（不再等）</li>
     *   <li>追问满2轮 → 强制推进（不管是否补全）</li>
     * </ul>
     */
    public FollowUpDecision decideFollowUp(TriageResult triageResult, TicketExtractResult extract) {
        // P0 不追问
        if (triageResult.priority() == TicketPriority.P0) {
            return FollowUpDecision.noFollowUp("P0 不追问，直接排查");
        }

        // 达到最大追问轮次
        if (triageResult.followUpRound() >= MAX_FOLLOW_UP_ROUNDS) {
            return FollowUpDecision.noFollowUp("已追问" + triageResult.followUpRound() + "轮，强制推进");
        }

        // 关键字段缺失检查（INCIDENT 需要 subject + symptom）
        boolean missingCritical = isMissingCriticalFields(triageResult.issueType(), extract);
        if (missingCritical) {
            return FollowUpDecision.needFollowUp(
                    "关键信息缺失，需要追问补全",
                    triageResult.followUpRound() + 1
            );
        }

        // 信息完整，不需要追问
        return FollowUpDecision.noFollowUp("信息完整，无需追问");
    }

    // --- 内部方法 ---

    /**
     * 规则命中后，用 extract 补全优先级和路由。
     */
    private TriageResult enrichWithExtract(TriageResult ruleResult, TicketExtractResult extract,
                                            String runId, int followUpRound) {
        // 优先级以 extract 为准（更精确的字段级信息）
        PriorityResult priorityResult = priorityService.evaluate(extract);
        boolean needHumanConfirm = priorityResult.priority() == TicketPriority.P0
                || priorityResult.priority() == TicketPriority.P1
                || priorityResult.needHumanConfirm();

        // 路由建议
        String routedTeam = resolveRoutedTeam(extract, priorityResult);

        return TriageResult.builder()
                .issueType(extract.issueType() != IssueType.UNKNOWN ? extract.issueType() : ruleResult.issueType())
                .priority(priorityResult.priority())
                .confidence(ruleResult.confidence())
                .source(ruleResult.source())
                .affectedSystem(extract.affectedSystem() != null ? extract.affectedSystem() : ruleResult.affectedSystem())
                .affectedModule(extract.affectedModule() != null ? extract.affectedModule() : ruleResult.affectedModule())
                .routedTeam(routedTeam)
                .needHumanConfirm(needHumanConfirm)
                .followUpRound(followUpRound)
                .build();
    }

    /**
     * 降级路径：从 extract 直接构建 TriageResult（LLM 分类未就绪时使用）。
     */
    private TriageResult buildFromExtract(TicketExtractResult extract, String runId, int followUpRound) {
        PriorityResult priorityResult = priorityService.evaluate(extract);
        boolean needHumanConfirm = priorityResult.priority() == TicketPriority.P0
                || priorityResult.priority() == TicketPriority.P1
                || priorityResult.needHumanConfirm();

        String routedTeam = resolveRoutedTeam(extract, priorityResult);

        return TriageResult.builder()
                .issueType(extract.issueType())
                .priority(priorityResult.priority())
                .confidence(extract.confidence())
                .source(TriageResult.TriageSource.LLM)
                .affectedSystem(extract.affectedSystem())
                .affectedModule(extract.affectedModule())
                .routedTeam(routedTeam)
                .needHumanConfirm(needHumanConfirm)
                .followUpRound(followUpRound)
                .build();
    }

    /**
     * 规则校验：一致性检查 + 矛盾检测。
     * <p>
     * 校验逻辑：
     * <ul>
     *   <li>INCIDENT 类型的 priority 不应为 P3（故障最低 P2）</li>
     *   <li>CONSULT 类型的 priority 不应为 P0（咨询最高 P2）</li>
     *   <li>confidence 低于 0.5 时降级为 UNKNOWN</li>
     * </ul>
     */
    private TriageResult validate(TriageResult result, TicketExtractResult extract) {
        IssueType issueType = result.issueType();
        TicketPriority priority = result.priority();
        TriageResult.TriageSource source = result.source();
        boolean modified = false;

        // INCIDENT 不应有 P3
        if (issueType == IssueType.INCIDENT && priority == TicketPriority.P3) {
            priority = TicketPriority.P2;
            modified = true;
            log.info("triage validation: INCIDENT priority P3→P2");
        }

        // CONSULT 不应有 P0
        if (issueType == IssueType.CONSULT && priority == TicketPriority.P0) {
            priority = TicketPriority.P2;
            modified = true;
            log.info("triage validation: CONSULT priority P0→P2");
        }

        // 低置信度降级
        if (result.confidence() < 0.5 && issueType != IssueType.UNKNOWN) {
            issueType = IssueType.UNKNOWN;
            modified = true;
            log.info("triage validation: confidence {} too low, issueType→UNKNOWN", result.confidence());
        }

        if (modified) {
            return TriageResult.builder()
                    .issueType(issueType)
                    .priority(priority)
                    .confidence(result.confidence())
                    .source(TriageResult.TriageSource.RULE_VALIDATED)
                    .affectedSystem(result.affectedSystem())
                    .affectedModule(result.affectedModule())
                    .routedTeam(result.routedTeam())
                    .needFollowUp(result.needFollowUp())
                    .followUpReason(result.followUpReason())
                    .followUpRound(result.followUpRound())
                    .needHumanConfirm(result.needHumanConfirm())
                    .build();
        }

        return result;
    }

    private String resolveRoutedTeam(TicketExtractResult extract, PriorityResult priorityResult) {
        // 简单规则路由：按 systemName 映射团队
        // 后续由 RoutingPolicyEngine 做规则+LLM置信度分流
        String system = extract.affectedSystem();
        if (system != null) {
            return system + "团队";
        }
        return null;
    }

    private boolean isMissingCriticalFields(IssueType issueType, TicketExtractResult extract) {
        if (issueType == IssueType.INCIDENT) {
            // INCIDENT 需要 affectedSystem + errorMessage 至少一个
            return (extract.affectedSystem() == null || extract.affectedSystem().isBlank())
                    && (extract.errorMessage() == null || extract.errorMessage().isBlank());
        }
        // 其他类型不强制追问
        return false;
    }

    // --- 追问决策结果 ---

    public record FollowUpDecision(
            boolean needFollowUp,
            String reason,
            int nextRound
    ) {
        static FollowUpDecision noFollowUp(String reason) {
            return new FollowUpDecision(false, reason, 0);
        }

        static FollowUpDecision needFollowUp(String reason, int nextRound) {
            return new FollowUpDecision(true, reason, nextRound);
        }
    }
}
