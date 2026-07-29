package com.gcll.ticketagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.gcll.ticketagent.execution.evidence.EvidenceBundle;
import com.gcll.ticketagent.execution.evidence.EvidenceInterpreterService;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.knowledge.KnowledgeHit;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import com.gcll.ticketagent.tool.ToolResult;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Primary
public class LlmRootCauseAnalysisService implements RootCauseAnalysisService {

    private final LlmCallExecutor llmCallExecutor;
    private final StructuredOutputParser parser;
    private final RuleBasedRootCauseAnalysisService fallback;
    private final EvidenceInterpreterService evidenceInterpreterService;

    public LlmRootCauseAnalysisService(
            LlmCallExecutor llmCallExecutor,
            StructuredOutputParser parser,
            RuleBasedRootCauseAnalysisService fallback,
            EvidenceInterpreterService evidenceInterpreterService
    ) {
        this.llmCallExecutor = llmCallExecutor;
        this.parser = parser;
        this.fallback = fallback;
        this.evidenceInterpreterService = evidenceInterpreterService;
    }

    @Override
    public RootCauseResult analyze(TicketExtractResult extract, List<KnowledgeHit> hits, List<ToolResult> toolResults) {
        try {
            String context = buildContext(extract, hits, toolResults);
            CallResult<LlmResponse> result = llmCallExecutor.execute(
                    "llm.root-cause", "root-cause-analysis.txt", context);
            if (!result.success()) {
                return fallback.analyze(extract, hits, toolResults);
            }
            RootCauseJson json = parser.parse(result.value().content(), RootCauseJson.class);
            return new RootCauseResult(
                    blankToDefault(json.hypothesis(), "根因待确认，需进一步排查"),
                    nullToEmpty(json.evidence()),
                    nullToEmpty(json.unknowns()),
                    clampConfidence(json.confidence()),
                    true
            );
        } catch (Exception ex) {
            return fallback.analyze(extract, hits, toolResults);
        }
    }

    private String buildContext(TicketExtractResult extract, List<KnowledgeHit> hits, List<ToolResult> toolResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("工单结构化信息:\n").append(extract).append("\n\n");

        EvidenceBundle evidenceBundle = evidenceInterpreterService.interpret(toolResults);
        sb.append("结构化证据解释:\n");
        sb.append("summary=").append(evidenceBundle.summary()).append("\n");
        appendList(sb, "日志信号", evidenceBundle.logSignals());
        appendList(sb, "指标信号", evidenceBundle.metricSignals());
        appendList(sb, "风险信号", evidenceBundle.riskSignals());
        appendList(sb, "证据缺口", evidenceBundle.unknowns());
        sb.append("\n");

        sb.append("历史案例:\n");
        if (hits == null || hits.isEmpty()) {
            sb.append("无命中\n");
        } else {
            for (KnowledgeHit hit : hits) {
                sb.append("- [").append(hit.sourceType()).append(":").append(hit.sourceId()).append("] ")
                        .append("summary=").append(hit.summary())
                        .append(", resolution=").append(hit.resolution())
                        .append(", score=").append(hit.score())
                        .append("\n");
            }
        }

        sb.append("\nTool 取证结果:\n");
        if (toolResults == null || toolResults.isEmpty()) {
            sb.append("无工具取证结果\n");
        } else {
            for (ToolResult toolResult : toolResults) {
                if (toolResult.success()) {
                    // 成功的工具调用：其 output 是真实证据，可引用
                    sb.append("- [成功] tool=").append(toolResult.toolName())
                            .append(", output=").append(toolResult.output())
                            .append("\n");
                } else {
                    // 失败的工具调用：这是工具本身不可用，【不是】工单的故障原因。
                    // 明确标注，防止 LLM 把工具的 errorMessage 当成根因证据。
                    sb.append("- [失败-工具不可用，非工单证据] tool=").append(toolResult.toolName())
                            .append(", error=").append(toolResult.errorMessage())
                            .append("（此为工具调用失败，不得作为工单根因依据）\n");
                }
            }
        }
        return sb.toString();
    }

    private void appendList(StringBuilder sb, String title, List<String> values) {
        sb.append(title).append(":\n");
        if (values == null || values.isEmpty()) {
            sb.append("- 无\n");
            return;
        }
        for (String value : values) {
            sb.append("- ").append(value).append("\n");
        }
    }

    private List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private double clampConfidence(double confidence) {
        if (confidence < 0) {
            return 0;
        }
        if (confidence > 1) {
            return 1;
        }
        return confidence;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RootCauseJson(
            String hypothesis,
            List<String> evidence,
            List<String> unknowns,
            double confidence
    ) {
    }
}
