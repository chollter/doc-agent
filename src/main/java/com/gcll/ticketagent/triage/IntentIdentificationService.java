package com.gcll.ticketagent.triage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.ticketagent.extract.IssueType;
import com.gcll.ticketagent.extract.TicketExtractResult;
import com.gcll.ticketagent.governance.priority.PriorityEvaluationService;
import com.gcll.ticketagent.governance.priority.PriorityResult;
import com.gcll.ticketagent.governance.priority.TicketPriority;
import com.gcll.ticketagent.resilience.CallResult;
import com.gcll.ticketagent.resilience.LlmCallExecutor;
import com.gcll.ticketagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 意图识别——LLM 分类+粗抽 → 定向精抽。
 * <p>
 * 两步设计：
 * <ol>
 *   <li>粗抽：LLM 快速判断 issueType + 4 个宏观字段（prompt 越聚焦，准确率越高）</li>
 *   <li>精抽：按 issueType 动态选择 prompt，只抽该类型所需的字段（减少噪音）</li>
 * </ol>
 * <p>
 * 相比一次性抽取 12 字段，两步设计的优势：
 * <ul>
 *   <li>粗抽 prompt 更短更聚焦，issueType 判断更准</li>
 *   <li>精抽按类型裁剪字段，避免非 INCIDENT 类型强行抽 severitySignals</li>
 *   <li>两步 LLM 调用总 token 比一次性少（精抽只抽 3-6 字段而非 12 个）</li>
 * </ul>
 */
@Service
public class IntentIdentificationService {

    private static final Logger log = LoggerFactory.getLogger(IntentIdentificationService.class);
    private static final String CLASSIFY_PROMPT = "triage-classify.txt";
    private static final String EXTRACT_PROMPT = "triage-extract.txt";
    private static final String CLASSIFY_CALL_NAME = "llm.triage-classify";
    private static final String EXTRACT_CALL_NAME = "llm.triage-extract";

    private final LlmCallExecutor llmCallExecutor;
    private final PriorityEvaluationService priorityService;
    private final ObjectMapper objectMapper;

    public IntentIdentificationService(LlmCallExecutor llmCallExecutor,
                                       PriorityEvaluationService priorityService,
                                       ObjectMapper objectMapper) {
        this.llmCallExecutor = llmCallExecutor;
        this.priorityService = priorityService;
        this.objectMapper = objectMapper;
    }

    /**
     * 两步意图识别：粗抽 → 精抽。
     *
     * @param content 工单原始内容
     * @param runId   工单运行 ID
     * @return 分诊结果（含 issueType + priority + 精抽字段）
     */
    public TriageResult identify(String content, String runId) {
        // Step 2a: LLM 粗抽——只抽 4 个宏观字段
        ClassifyResult classify = classify(content, runId);
        if (classify == null) {
            // LLM 粗抽失败，降级为 UNKNOWN + P2
            return TriageResult.builder()
                    .issueType(IssueType.UNKNOWN)
                    .priority(TicketPriority.P2)
                    .confidence(0.0)
                    .source(TriageResult.TriageSource.LLM)
                    .followUpRound(0)
                    .build();
        }

        // Step 2b: 定向精抽——按 issueType 抽取该类型所需字段
        Map<String, Object> extractFields = extractByType(content, classify.issueType, classify.affectedSystem, runId);

        // 组装 TicketExtractResult（兼容下游 PriorityEvaluationService）
        TicketExtractResult extract = assembleExtractResult(classify, extractFields);
        PriorityResult priorityResult = priorityService.evaluate(extract);

        boolean needHumanConfirm = priorityResult.priority() == TicketPriority.P0
                || priorityResult.priority() == TicketPriority.P1
                || priorityResult.needHumanConfirm();

        return TriageResult.builder()
                .issueType(classify.issueType)
                .priority(priorityResult.priority())
                .confidence(classify.confidence)
                .source(TriageResult.TriageSource.LLM)
                .affectedSystem(classify.affectedSystem)
                .affectedModule(classify.affectedModule)
                .needHumanConfirm(needHumanConfirm)
                .followUpRound(0)
                .build();
    }

    // --- Step 2a: 粗抽 ---

    private ClassifyResult classify(String content, String runId) {
        CallResult<LlmResponse> result = llmCallExecutor.execute(CLASSIFY_CALL_NAME, CLASSIFY_PROMPT, content, runId);
        if (!result.success() || result.value() == null) {
            log.warn("triage classify LLM failed, runId={}", runId);
            return null;
        }

        try {
            String json = extractJson(result.value().content());
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});

            return new ClassifyResult(
                    parseIssueType(map.get("issueType")),
                    getString(map, "affectedSystem"),
                    getString(map, "affectedModule"),
                    getString(map, "environment"),
                    getDouble(map, "confidence")
            );
        } catch (Exception e) {
            log.warn("triage classify parse failed, runId={}, error={}", runId, e.getMessage());
            return null;
        }
    }

    // --- Step 2b: 定向精抽 ---

    private Map<String, Object> extractByType(String content, IssueType issueType, String affectedSystem, String runId) {
        // 构建 prompt：替换 {issueType} 和 {affectedSystem}
        String promptOverride = null;  // 通过 userContent 前缀注入类型信息
        String enrichedContent = "问题类型: " + issueType.name() + "\n受影响系统: " + (affectedSystem != null ? affectedSystem : "未知") + "\n\n" + content;

        CallResult<LlmResponse> result = llmCallExecutor.execute(EXTRACT_CALL_NAME, EXTRACT_PROMPT, enrichedContent, runId);
        if (!result.success() || result.value() == null) {
            log.warn("triage extract LLM failed, runId={}, issueType={}", runId, issueType);
            return Map.of();
        }

        try {
            String json = extractJson(result.value().content());
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("triage extract parse failed, runId={}, error={}", runId, e.getMessage());
            return Map.of();
        }
    }

    // --- 组装 ---

    private TicketExtractResult assembleExtractResult(ClassifyResult classify, Map<String, Object> fields) {
        return new TicketExtractResult(
                classify.issueType,
                classify.affectedSystem,
                classify.affectedModule,
                getString(fields, "apiName"),
                getString(fields, "errorCode"),
                getString(fields, "errorMessage"),
                classify.environment,
                getString(fields, "impactScope"),
                getString(fields, "timeRange"),
                getString(fields, "businessImpact"),
                parseSeveritySignals(fields.get("severitySignals")),
                classify.confidence
        );
    }

    // --- 工具方法 ---

    private IssueType parseIssueType(Object value) {
        if (value == null) return IssueType.UNKNOWN;
        try {
            return IssueType.valueOf(value.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return IssueType.UNKNOWN;
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private double getDouble(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.doubleValue();
        if (value != null) {
            try { return Double.parseDouble(value.toString()); } catch (Exception ignored) {}
        }
        return 0.7; // 默认置信度
    }

    @SuppressWarnings("unchecked")
    private List<String> parseSeveritySignals(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    private String extractJson(String content) {
        if (content == null) return "{}";
        // 尝试提取 ```json ... ``` 块
        int start = content.indexOf("```json");
        if (start >= 0) {
            start = content.indexOf('\n', start) + 1;
            int end = content.indexOf("```", start);
            if (end > start) return content.substring(start, end).trim();
        }
        // 尝试提取 { ... }
        int braceStart = content.indexOf('{');
        int braceEnd = content.lastIndexOf('}');
        if (braceStart >= 0 && braceEnd > braceStart) {
            return content.substring(braceStart, braceEnd + 1);
        }
        return content.trim();
    }

    // --- 内部类型 ---

    private record ClassifyResult(
            IssueType issueType,
            String affectedSystem,
            String affectedModule,
            String environment,
            double confidence
    ) {}
}
