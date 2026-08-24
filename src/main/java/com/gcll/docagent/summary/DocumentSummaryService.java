package com.gcll.docagent.summary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.api.BusinessException;
import com.gcll.docagent.api.ErrorCode;
import com.gcll.docagent.api.dto.DocumentSummaryResponse;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.resilience.LlmResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentSummaryService {
    private static final int MAX_BYTES = 512 * 1024;
    private final ObjectMapper objectMapper;
    private final LlmGateway llmGateway;

    public DocumentSummaryService(ObjectMapper objectMapper, @org.springframework.beans.factory.annotation.Autowired(required = false) LlmGateway llmGateway) {
        this.objectMapper = objectMapper;
        this.llmGateway = llmGateway;
    }

    public DocumentSummaryResponse summarize(MultipartFile file, String instruction) throws IOException {
        validate(file);
        String text = normalize(new String(file.getBytes(), StandardCharsets.UTF_8));
        String runId = "summary-" + UUID.randomUUID();
        List<DocumentSummaryResponse.SummaryStep> steps = new ArrayList<>();
        steps.add(new DocumentSummaryResponse.SummaryStep("PARSE", "executed", "已解析 " + file.getOriginalFilename()));
        steps.add(new DocumentSummaryResponse.SummaryStep("SUMMARIZE", "requested", "等待生成结构化摘要"));

        if (llmGateway != null) {
            try {
                String prompt = "请总结以下资料。输出严格 JSON，字段为 summary(字符串), keyPoints(字符串数组), risks(字符串数组), todos(字符串数组), citations(字符串数组)。" +
                        " 用户要求：" + (instruction == null ? "提取核心内容、风险和待办" : instruction) + "\n资料：\n" + text;
                LlmResponse response = llmGateway.invoke("llm.document-summary", "document-summary.txt", prompt, runId);
                Map<String, Object> result = objectMapper.readValue(response.content(), new TypeReference<>() {});
                steps.set(1, new DocumentSummaryResponse.SummaryStep("SUMMARIZE", "executed", "LLM 已生成结构化结果"));
                return new DocumentSummaryResponse(runId, "DocumentSummary", "COMPLETED", "LLM", string(result, "summary"), list(result, "keyPoints"), list(result, "risks"), list(result, "todos"), list(result, "citations"), steps);
            } catch (Exception ex) {
                steps.set(1, new DocumentSummaryResponse.SummaryStep("SUMMARIZE", "fallback_succeeded", "LLM 不可用，已切换规则摘要：" + ex.getClass().getSimpleName()));
            }
        } else {
            steps.set(1, new DocumentSummaryResponse.SummaryStep("SUMMARIZE", "fallback_succeeded", "未配置 LLM，使用规则摘要"));
        }
        steps.add(new DocumentSummaryResponse.SummaryStep("CITATION", "executed", "引用首段和标题作为可回溯证据"));
        return fallback(runId, text, file.getOriginalFilename(), steps);
    }

    private DocumentSummaryResponse fallback(String runId, String text, String filename, List<DocumentSummaryResponse.SummaryStep> steps) {
        String[] paragraphs = text.split("\\n\\s*\\n");
        String summary = paragraphs.length == 0 ? text : paragraphs[0];
        if (summary.length() > 500) summary = summary.substring(0, 500) + "…";
        List<String> points = new ArrayList<>();
        for (int i = 0; i < Math.min(3, paragraphs.length); i++) {
            if (!paragraphs[i].isBlank()) points.add(paragraphs[i].length() > 180 ? paragraphs[i].substring(0, 180) + "…" : paragraphs[i]);
        }
        return new DocumentSummaryResponse(runId, "DocumentSummary", "COMPLETED", "FALLBACK", summary, points,
                List.of("规则模式不会推断资料中不存在的风险"), List.of("请基于摘要结果人工复核关键结论"),
                List.of(filename + "：首段内容", filename + "：前 " + Math.min(3, paragraphs.length) + " 个段落"), steps);
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException(ErrorCode.BAD_REQUEST, "资料不能为空");
        if (file.getSize() > MAX_BYTES) throw new BusinessException(ErrorCode.BAD_REQUEST, "资料不能超过 512KB");
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!(name.endsWith(".md") || name.endsWith(".txt"))) throw new BusinessException(ErrorCode.BAD_REQUEST, "当前仅支持 Markdown 和 TXT");
    }

    private String normalize(String text) { return text.replace("\r\n", "\n").replace('\r', '\n').trim(); }
    private String string(Map<String, Object> map, String key) { return String.valueOf(map.getOrDefault(key, "")); }
    private List<String> list(Map<String, Object> map, String key) { Object value = map.get(key); return value instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of(); }
}
