package com.gcll.docagent.analysis;

import com.gcll.docagent.domain.AgentRun;
import com.gcll.docagent.loop.AgentLoop;
import com.gcll.docagent.parsing.DocSection;
import com.gcll.docagent.parsing.ParsedDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 构建——把 run/文档/简历上下文/已读片段渲染成喂给 LLM 的字符串，以及按配置裁剪技能的工具集与系统提示词。
 * <p>从编排服务剥离：这些方法是无副作用的纯渲染（输入领域对象、输出 prompt 文本），
 * 与执行链的控制流、状态推进、降级决策无关。集中在此让"提示词工程"这一关注点独立可测、可演进。
 */
@Component
public class PromptBuilder {

    private final boolean exportEnabled;

    public PromptBuilder(@Value("${docagent.analysis.export-enabled:true}") boolean exportEnabled) {
        this.exportEnabled = exportEnabled;
    }

    /** 技能实际暴露的工具（压测等场景可关掉 DANGER 导出）。 */
    public List<String> effectiveToolNames(SkillDefinition skill) {
        if (exportEnabled) {
            return skill.toolNames();
        }
        return skill.toolNames().stream().filter(t -> !"export_report".equals(t)).toList();
    }

    /** 工具集与提示词必须一致：剔除导出工具时同步剔除提示词中的导出指引行，
     *  否则模型会按提示调用不在 spec 列表里的工具，导致请求非法。 */
    public String effectiveSystemPrompt(SkillDefinition skill) {
        if (exportEnabled) {
            return skill.reactSystemPrompt();
        }
        return skill.reactSystemPrompt().lines()
                .filter(line -> !line.contains("export_report"))
                .reduce((a, b) -> a + "\n" + b)
                .orElse(skill.reactSystemPrompt());
    }

    public String buildUserMessage(AgentRun run, ParsedDocument doc) {
        return buildUserMessage(run, doc, ResumeContext.empty());
    }

    public String buildUserMessage(AgentRun run, ParsedDocument doc, ResumeContext ctx) {
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
            if (!entities.getProjects().isEmpty()) {
                sb.append("\n项目事实（只可使用 status=explicit 且有 sourceQuote 的事实）：");
                entities.getProjects().forEach(p -> {
                    sb.append("\n  - project=").append(p.projectId()).append(" section=").append(p.sectionId());
                    appendFact(sb, "背景", p.context());
                    appendFact(sb, "问题", p.problem());
                    appendFacts(sb, "职责", p.responsibilities());
                    appendFacts(sb, "技术", p.technologies());
                    appendFact(sb, "AI链路", p.aiPipeline());
                    appendFacts(sb, "决策", p.decisions());
                    appendFact(sb, "结果", p.results());
                    appendFact(sb, "规模", p.scale());
                    appendFact(sb, "上线", p.deployment());
                });
            }
        }

        if (ctx.redFlags() != null && !ctx.redFlags().isEmpty()) {
            sb.append("\n\n## 红旗筛查结果（代码已验证，可直接引用）");
            ctx.redFlags().forEach(f -> sb.append("\n  - [").append(f.severity()).append("] ").append(f.message()));
        }

        TargetProfile target = ctx.targetProfile();
        if (target != null && !target.requirements().isEmpty()) {
            sb.append("\n\n## 标准化岗位要求（逐条建立要求→证据→状态→缺失事实矩阵）");
            sb.append("\n模式：").append(target.mode()).append("；岗位：").append(target.title());
            if (target.summary() != null) sb.append("；目标：").append(target.summary());
            sb.append("\n要求（逐条给 MET/PARTIAL/MISSING + 原文证据）：");
            for (TargetProfile.Requirement r : target.requirements()) {
                sb.append("\n  - [").append(r.id()).append("] ").append(r.requirement())
                        .append("（优先级：").append(r.priority())
                        .append("；淘汰项：").append(r.disqualifier())
                        .append("；预期证据：").append(String.join("、", r.evidenceExpected()))
                        .append("；关键词线索：").append(String.join("、", r.keywords())).append("）");
            }
            if (!target.variants().isEmpty()) {
                sb.append("\n子方向（逐个给 HIGH/MEDIUM/LOW 适配）：");
                for (TargetProfile.Variant v : target.variants()) {
                    sb.append("\n  - [").append(v.id()).append("] ").append(v.name())
                            .append("：").append(String.join("、", v.differentiators()));
                }
            }
            if (!target.screeningQuestions().isEmpty()) {
                sb.append("\n筛选题库（作 leverageCards 的 likelyQuestion 参考）：");
                target.screeningQuestions().forEach(q -> sb.append("\n  - ").append(q));
            }
        } else if (run.getJobDescription() != null && !run.getJobDescription().isBlank()) {
            sb.append("\n\n## 岗位标准化降级：以下为原始 JD，仅作匹配对照，不得补充要求\n")
                    .append(run.getJobDescription());
        }

        return sb.toString();
    }

    private static void appendFact(StringBuilder sb, String label, ResumeProjectFact.Fact fact) {
        if (fact != null && fact.value() != null && !fact.value().isBlank()) {
            sb.append(" ").append(label).append("=").append(fact.value())
                    .append(" [").append(fact.status()).append("]");
        }
    }

    private static void appendFacts(StringBuilder sb, String label, List<ResumeProjectFact.Fact> facts) {
        if (facts != null) {
            facts.forEach(f -> appendFact(sb, label, f));
        }
    }

    /** 把循环已读片段渲染进降级 prompt（每片截断 600 字，总量约 4000 字）。 */
    public String renderObservations(List<AgentLoop.ObservedFragment> observations) {
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

    public String renderWithSectionIds(ParsedDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (DocSection s : doc.sections()) {
            sb.append('[').append(s.id())
              .append(s.heading() != null ? " | " + s.heading() : "").append("]\n")
              .append(s.text()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** 评价专调输入：方向锚点 + 代码预检事实 + 主分析结构化结论 + 简历全文。 */
    public String buildEvaluationInput(AgentRun run, ResumeContext ctx, AnalysisResult result,
                                       LlmFunnelFields funnel,
                                       List<RequirementVerdict> requirementVerdicts) {
        StringBuilder sb = new StringBuilder();
        if (run.getJobDescription() != null && !run.getJobDescription().isBlank()) {
            sb.append("## 目标岗位JD\n").append(run.getJobDescription()).append('\n');
        } else if (ctx.archetype() != null) {
            sb.append("## 目标方向（广撒网画像）\n")
              .append(ctx.archetype().getName()).append("：").append(ctx.archetype().getSummary()).append('\n');
        } else {
            sb.append("## 评价锚点：未填 JD/方向，按简历自身定位评价\n");
        }
        if (ctx.profile() != null) {
            sb.append("\n总工作年限：").append(ctx.profile().yearsOfExperience()).append(" 年\n");
        }
        if (ctx.redFlags() != null && !ctx.redFlags().isEmpty()) {
            sb.append("\n## 代码预检红旗（已验证事实，可直接引用）\n");
            ctx.redFlags().forEach(f -> sb.append("- [").append(f.severity()).append("] ")
                    .append(f.message()).append('\n'));
        }
        if (requirementVerdicts != null && !requirementVerdicts.isEmpty()) {
            sb.append("\n## 岗位要求裁决（唯一有效的匹配判断，不得自行改判）\n");
            requirementVerdicts.forEach(v -> sb.append("- [").append(v.status()).append("] ")
                    .append(v.requirement()).append("；证据等级=").append(v.evidenceLevel())
                    .append("；理由=").append(v.reason()).append('\n'));
        }
        sb.append("\n## 主分析结论（结构化参照，不要照抄）\n")
          .append("摘要：").append(result.summary() == null ? "" : result.summary()).append('\n');
        if (funnel != null && funnel.leverageCards() != null && !funnel.leverageCards().isEmpty()) {
            sb.append("亮点/风险：\n");
            funnel.leverageCards().forEach(c -> sb.append("- [").append(c.kind()).append("] ")
                    .append(c.point()).append('\n'));
        }
        sb.append("\n## 简历全文\n").append(ctx.fullText());
        return sb.toString();
    }
}
