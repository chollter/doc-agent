package com.gcll.docagent.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.langchain4j.LangChainToolDelegator;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 循环工具规格注册表——工具名 → 模型可见的 ToolSpecification + 执行入口。
 * <p>与 ToolRegistry（治理/审计）互补：本类只负责"告诉模型有哪些工具、参数长什么样"，
 * 真正执行仍经 {@link LangChainToolDelegator} 走 ToolRuntime 统一治理。
 */
@Component
public class LoopToolSpecs {

    private static final Logger log = LoggerFactory.getLogger(LoopToolSpecs.class);

    private final LangChainToolDelegator delegator;
    private final ObjectMapper objectMapper;

    private final Map<String, ToolSpecification> specs = new LinkedHashMap<>();

    public LoopToolSpecs(LangChainToolDelegator delegator, ObjectMapper objectMapper) {
        this.delegator = delegator;
        this.objectMapper = objectMapper;

        specs.put("get_document_outline", ToolSpecification.builder()
                .name("get_document_outline")
                .description("获取文档大纲：每节一行的结构清单（节ID | 标题 | 字数 | 页码）。分析开始时先调用它了解文档全貌，再按需阅读具体节。")
                .build());
        specs.put("read_section", ToolSpecification.builder()
                .name("read_section")
                .description("按节ID阅读文档正文。sectionId 来自 get_document_outline 返回的节ID（如 sec-1）。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("sectionId", "节ID，如 sec-1")
                        .required("sectionId")
                        .build())
                .build());
        specs.put("search_document", ToolSpecification.builder()
                .name("search_document")
                .description("在全文中按关键词检索，返回命中节ID和上下文片段。用于快速定位与要求相关的内容。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("keyword", "检索关键词")
                        .required("keyword")
                        .build())
                .build());
        specs.put("export_report", ToolSpecification.builder()
                .name("export_report")
                .description("将最终分析报告导出为 Markdown 文件。高危操作：调用后会被拦截，等待人工确认才真正写入。分析完成后的最后一步调用它，传入 filename（不含路径）和 content（完整 Markdown 报告）。")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("filename", "导出文件名，如 report.md，不含路径")
                        .addStringProperty("content", "完整报告内容（Markdown）")
                        .required("filename", "content")
                        .build())
                .build());
    }

    public List<ToolSpecification> specsFor(List<String> toolNames) {
        return toolNames.stream()
                .map(name -> {
                    ToolSpecification spec = specs.get(name);
                    if (spec == null) {
                        throw new IllegalArgumentException("Skill 引用了循环未知工具: " + name);
                    }
                    return spec;
                })
                .toList();
    }

    /** 执行一次工具调用（治理与 trace 在 delegator 内），返回给模型的观察文本。 */
    public String execute(String runId, String toolName, String argumentsJson) {
        Map<String, String> params = parseArguments(argumentsJson);
        return delegator.delegate(toolName, params);
    }

    private Map<String, String> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(argumentsJson, new TypeReference<>() {
            });
            Map<String, String> params = new LinkedHashMap<>();
            raw.forEach((k, v) -> params.put(k, v == null ? null : String.valueOf(v)));
            return params;
        } catch (Exception ex) {
            log.warn("工具参数解析失败（按空参继续）: {}", argumentsJson);
            return Map.of();
        }
    }
}
