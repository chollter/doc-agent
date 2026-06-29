package com.gcll.ticketagent.agent.workflow;

import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 计划驱动执行的工作区（Plan-Execute 重构）：替代 execute 方法内的局部变量传数据。
 *
 * <p>计划里的 4 种 action（KNOWLEDGE_SEARCH/SIMILAR_CASE_SEARCH/QUERY_LOGS/QUERY_METRIC）
 * 各自往这个工作区写入证据，后处理步骤（rootcause/routing/suggestion）从这里读。
 *
 * <p>这是"计划真正驱动执行"的关键——各步骤不再用局部变量传参，而是共享工作区，
 * 这样才能遍历计划步骤、按 action 分派执行、逐个标记状态。
 */
public class StepExecutionContext {

    /** 知识/案例检索产出。 */
    private final List<KnowledgeHit> hits = new ArrayList<>();

    /** 日志/指标工具产出。 */
    private final List<ToolResult> toolResults = new ArrayList<>();

    /** 证据汇总文本（供 rootcause prompt 用）。 */
    private String evidenceSummary = "";

    public void addHits(List<KnowledgeHit> newHits) {
        if (newHits != null) {
            hits.addAll(newHits);
        }
    }

    public void addToolResults(List<ToolResult> results) {
        if (results != null) {
            toolResults.addAll(results);
        }
    }

    public void setEvidenceSummary(String summary) {
        this.evidenceSummary = summary == null ? "" : summary;
    }

    public List<KnowledgeHit> hits() {
        return Collections.unmodifiableList(hits);
    }

    public List<ToolResult> toolResults() {
        return Collections.unmodifiableList(toolResults);
    }

    public String evidenceSummary() {
        return evidenceSummary;
    }
}
