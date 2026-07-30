package com.gcll.ticketagent.execution.tool;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.agent.planner.AgentPlan;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.llm.StepOutcome;
import com.gcll.ticketagent.llm.StructuredOutputParser;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import com.gcll.ticketagent.tool.ToolRegistry;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Primary
public class SpringAiToolSelector implements ToolSelector {

    private static final Set<String> ALLOWED_TOOLS = Set.of(
            "searchSimilarCases", "query_logs", "notifyOncall", "executeRemediation"
    );

    private final LlmCallExecutor llmCallExecutor;
    private final StructuredOutputParser parser;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final RuleBasedToolSelector fallback;

    public SpringAiToolSelector(
            LlmCallExecutor llmCallExecutor,
            StructuredOutputParser parser,
            ObjectMapper objectMapper,
            ToolRegistry toolRegistry,
            RuleBasedToolSelector fallback
    ) {
        this.llmCallExecutor = llmCallExecutor;
        this.parser = parser;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.fallback = fallback;
    }

    @Override
    public StepOutcome<ToolSelection> select(String userContent, TicketExtractResult extract, AgentPlan plan) {
        try {
            String input = buildInput(userContent, extract, plan);
            CallResult<LlmResponse> result = llmCallExecutor.execute(
                    "llm.tool-select", "tool-select.txt", input);
            if (!result.success()) {
                return fallback.select(userContent, extract, plan);
            }
            SelectJson json = parser.parse(result.value().content(), SelectJson.class);
            LinkedHashSet<String> selected = new LinkedHashSet<>();
            if (json.selectedToolNames() != null) {
                for (String toolName : json.selectedToolNames()) {
                    if (ALLOWED_TOOLS.contains(toolName) && toolRegistry.find(toolName).isPresent()) {
                        selected.add(toolName);
                    }
                }
            }
            if (selected.isEmpty()) {
                return fallback.select(userContent, extract, plan);
            }
            Map<String, String> parameters = json.parameters() == null ? Map.of() : json.parameters();
            return StepOutcome.llm(new ToolSelection(
                    List.copyOf(selected),
                    parameters,
                    json.reason() == null ? "LLM tool selection" : json.reason(),
                    true
            ), result.durationMs());
        } catch (Exception ex) {
            return fallback.select(userContent, extract, plan);
        }
    }

    private String buildInput(String userContent, TicketExtractResult extract, AgentPlan plan) throws Exception {
        return """
                AgentPlan：
                %s

                可用工具白名单：
                %s

                抽取结果 JSON：
                %s

                用户原文：
                %s
                """.formatted(
                plan.auditSummary(),
                toolRegistry.descriptors(),
                objectMapper.writeValueAsString(extract),
                userContent
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SelectJson(List<String> selectedToolNames, Map<String, String> parameters, String reason) {
    }
}
