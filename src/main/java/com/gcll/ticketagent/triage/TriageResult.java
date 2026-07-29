package com.gcll.ticketagent.triage;

import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.governance.priority.TicketPriority;

/**
 * 分诊结果——分诊阶段的唯一输出。
 * <p>
 * 分诊目标：快速回答"这是什么？多急？路由给谁？"，秒级出结果，不阻塞工单流转。
 * 后续排查阶段从本结果读取 issueType/priority/routedTeam，不重复判断。
 *
 * @param issueType        问题类型
 * @param priority         优先级（纯规则评估，毫秒级）
 * @param confidence       置信度（0-1，规则命中=1.0，LLM 命中<1.0）
 * @param source           判定来源：RULE（规则前置零LLM）/ LLM（LLM分类）/ RULE_VALIDATED（规则校验修正）
 * @param affectedSystem   受影响系统
 * @param affectedModule   受影响模块
 * @param routedTeam       路由目标团队（可为null，排查阶段补全）
 * @param needFollowUp     是否需要追问
 * @param followUpReason   追问原因
 * @param followUpRound    当前追问轮次（0=未追问，1=首次，2=二次...最多2轮）
 * @param needHumanConfirm 是否需人工确认（P0/P1 或 DANGER 工具后）
 */
public record TriageResult(
        IssueType issueType,
        TicketPriority priority,
        double confidence,
        TriageSource source,
        String affectedSystem,
        String affectedModule,
        String routedTeam,
        boolean needFollowUp,
        String followUpReason,
        int followUpRound,
        boolean needHumanConfirm
) {
    public enum TriageSource {
        RULE,           // 规则前置零LLM
        LLM,            // LLM分类+粗抽
        RULE_VALIDATED  // 规则校验修正
    }

    /**
     * 构建器：方便分步骤组装 TriageResult。
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private IssueType issueType;
        private TicketPriority priority;
        private double confidence;
        private TriageSource source;
        private String affectedSystem;
        private String affectedModule;
        private String routedTeam;
        private boolean needFollowUp;
        private String followUpReason;
        private int followUpRound;
        private boolean needHumanConfirm;

        public Builder issueType(IssueType v) { this.issueType = v; return this; }
        public Builder priority(TicketPriority v) { this.priority = v; return this; }
        public Builder confidence(double v) { this.confidence = v; return this; }
        public Builder source(TriageSource v) { this.source = v; return this; }
        public Builder affectedSystem(String v) { this.affectedSystem = v; return this; }
        public Builder affectedModule(String v) { this.affectedModule = v; return this; }
        public Builder routedTeam(String v) { this.routedTeam = v; return this; }
        public Builder needFollowUp(boolean v) { this.needFollowUp = v; return this; }
        public Builder followUpReason(String v) { this.followUpReason = v; return this; }
        public Builder followUpRound(int v) { this.followUpRound = v; return this; }
        public Builder needHumanConfirm(boolean v) { this.needHumanConfirm = v; return this; }

        public TriageResult build() {
            return new TriageResult(
                    issueType, priority, confidence, source,
                    affectedSystem, affectedModule, routedTeam,
                    needFollowUp, followUpReason, followUpRound, needHumanConfirm
            );
        }
    }
}
