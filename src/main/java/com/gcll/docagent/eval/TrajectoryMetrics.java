package com.gcll.docagent.eval;

import com.gcll.docagent.domain.AgentStep;
import com.gcll.docagent.tool.ToolResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轨迹质量指标——从 agent_step 与 tool_execution_log 提取计划质量的量化视图：
 * <ul>
 *   <li>llmRounds：ReAct 推理轮数（REACT_LLM_RESPONSE 步数）</li>
 *   <li>toolCalls：工具调用总次数</li>
 *   <li>outlineFirst：首个工具调用是否为 get_document_outline（计划性的信号）</li>
 *   <li>redundantReads：重复读取同一节的次数（浪费信号）</li>
 *   <li>citationKept / citationDropped：引用校验保留/剔除数（防幻觉信号）</li>
 * </ul>
 */
public record TrajectoryMetrics(
        int llmRounds,
        int toolCalls,
        boolean outlineFirst,
        int redundantReads,
        int citationKept,
        int citationDropped,
        boolean evidenceExploreTriggered,
        boolean evidenceExploreSucceeded,
        long evidenceExploreCostMs,
        long reactCostMs,
        long totalCostMs
) {

    private static final Pattern SECTION_ID = Pattern.compile("sectionId=(sec-\\d+)");
    private static final Pattern KEPT_DROPPED = Pattern.compile("kept=(\\d+), dropped=(\\d+)");

    public static TrajectoryMetrics of(List<AgentStep> steps, List<ToolResult> toolResults, long totalCostMs) {
        long reactCost = steps.stream()
                .filter(s -> "REACT_ANALYZE".equals(s.getStepName()))
                .mapToLong(AgentStep::getCostMs)
                .sum();

        String firstTool = steps.stream()
                .map(AgentStep::getStepName)
                .filter(n -> n != null && n.startsWith("REACT_TOOL_CALL: "))
                .findFirst()
                .map(n -> n.substring("REACT_TOOL_CALL: ".length()))
                .orElse(null);

        // 冗余读取：同一节被 read_section 读超过一次
        Map<String, Integer> readCounts = new HashMap<>();
        int redundant = 0;
        for (ToolResult result : toolResults) {
            if (!"read_section".equals(result.toolName()) || result.input() == null) {
                continue;
            }
            Matcher m = SECTION_ID.matcher(result.input());
            if (m.find()) {
                int seen = readCounts.merge(m.group(1), 1, Integer::sum);
                if (seen > 1) {
                    redundant++;
                }
            }
        }

        int kept = -1;
        int dropped = -1;
        for (AgentStep step : steps) {
            if ("CITATION_VERIFY".equals(step.getStepName()) && step.getOutputSnapshot() != null) {
                Matcher m = KEPT_DROPPED.matcher(step.getOutputSnapshot());
                if (m.find()) {
                    kept = Integer.parseInt(m.group(1));
                    dropped = Integer.parseInt(m.group(2));
                    break;
                }
            }
        }

        AgentStep evidenceStep = steps.stream()
                .filter(s -> "EVIDENCE_EXPLORE".equals(s.getStepName()))
                .findFirst().orElse(null);
        boolean evidenceTriggered = evidenceStep != null;
        boolean evidenceSucceeded = evidenceStep != null
                && evidenceStep.getErrorMessage() == null
                && evidenceStep.getOutputSnapshot() != null
                && !evidenceStep.getOutputSnapshot().contains("evidence=0");

        return new TrajectoryMetrics(
                (int) steps.stream().filter(s -> "REACT_LLM_RESPONSE".equals(s.getStepName())).count(),
                (int) steps.stream().filter(s -> s.getStepName() != null && s.getStepName().startsWith("REACT_TOOL_CALL: ")).count(),
                "get_document_outline".equals(firstTool),
                redundant,
                kept,
                dropped,
                evidenceTriggered,
                evidenceSucceeded,
                evidenceStep == null ? 0 : evidenceStep.getCostMs(),
                reactCost,
                totalCostMs);
    }
}
