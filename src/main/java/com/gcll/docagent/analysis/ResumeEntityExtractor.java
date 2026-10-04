package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历语义实体抽取：从任意格式简历中提取结构化实体。
 * 不假设简历格式，只提取"简历里出现了什么"。
 * <p>调用路径：走 LlmGateway 新路径（callName 路由 + 工厂自建 DashScopeChatModel），
 * 与 DIRECT_LLM 同一客户端装配。旧路径（自动装配 ChatModel）在 spring-ai-alibaba
 * M6.1 与 GA BOM 混用下反序列化必失败（content-type application/octet-stream），
 * 曾导致实体抽取 100% 降级——禁止回迁。
 */
@Service
public class ResumeEntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(ResumeEntityExtractor.class);
    private static final String PROMPT_FILE = "resume-entity-extract.txt";
    /** ModelRouter 路由用调用点名（当前无映射，回落默认模型；留名供后续按调用点配模型）。 */
    private static final String CALL_NAME = "llm.entity-extract";

    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final ObjectMapper objectMapper;
    private final EntityNormalizer entityNormalizer;

    public ResumeEntityExtractor(ObjectProvider<LlmGateway> llmGatewayProvider,
                                 ObjectMapper objectMapper,
                                 EntityNormalizer entityNormalizer) {
        this.llmGatewayProvider = llmGatewayProvider;
        this.objectMapper = objectMapper;
        this.entityNormalizer = entityNormalizer;
    }

    /**
     * 从简历文本中提取语义实体。
     * 返回携带来源标记——LLM 不可用时降级，调用方据此决定是否出分。
     *
     * @param runId 运行 ID：透传给网关作交互留痕关联（不参与记忆，单轮调用）
     */
    public ExtractionOutcome extract(String resumeText, String fileName, String runId) {
        if (resumeText == null || resumeText.isBlank()) {
            return ExtractionOutcome.fallback(new ResumeEntities(List.of()));
        }

        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            log.warn("LLM not available, falling back to basic entity extraction");
            return ExtractionOutcome.fallback(fallbackExtract(resumeText, fileName));
        }

        try {
            LlmResponse response = llmGateway.invoke(CALL_NAME, PROMPT_FILE, resumeText, runId);
            return ExtractionOutcome.llm(
                    entityNormalizer.normalize(parseEntities(response.content(), resumeText)));
        } catch (Exception first) {
            // 瞬时故障（提供商响应异常/网络抖动）再试一次，减少误降级横幅；
            // 两次都失败才降级——降级标记机制本身保留（显式告知而非静默）。
            log.warn("Entity extraction failed once, retrying: {}", first.getMessage());
            try {
                LlmResponse response = llmGateway.invoke(CALL_NAME, PROMPT_FILE, resumeText, runId);
                return ExtractionOutcome.llm(
                        entityNormalizer.normalize(parseEntities(response.content(), resumeText)));
            } catch (Exception ex) {
                log.warn("Entity extraction failed, using fallback: {}", ex.getMessage());
                return ExtractionOutcome.fallback(fallbackExtract(resumeText, fileName));
            }
        }
    }

    /**
     * 宽容解析（2026-09-18 事故修复）：树解析 + 逐实体/逐项目隔离失败。
     * 此前整棵 DTO 反序列化，单个字段形态违规（如 results 被模型输出为数组）
     * 会炸掉整份抽取 → 空实体降级 → 误导性“不通过”评测。
     */
    private ResumeEntities parseEntities(String json, String sourceText) {
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "")
                        .replaceAll("\\s*```$", "");
            }

            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("entity extraction response has no JSON object");
            }

            JsonNode tree = objectMapper.readTree(cleaned.substring(start, end + 1));

            List<ResumeEntity> entities = new ArrayList<>();
            JsonNode entitiesNode = tree.get("entities");
            if (entitiesNode != null && entitiesNode.isArray()) {
                for (JsonNode node : entitiesNode) {
                    try {
                        RawEntity raw = objectMapper.treeToValue(node, RawEntity.class);
                        if (raw.type != null && raw.value != null) {
                            ResumeEntity.EntityType type = ResumeEntity.EntityType.valueOf(raw.type.toUpperCase());
                            Map<String, String> attrs = raw.attributes != null
                                    ? raw.attributes
                                    : Map.of();
                            entities.add(new ResumeEntity(type, raw.value, raw.context, attrs));
                        }
                    } catch (Exception perEntity) {
                        // 单实体畸形只丢弃该实体，不炸整份抽取
                        log.debug("Skip malformed entity: {}", perEntity.getMessage());
                    }
                }
            }

            List<ResumeProjectFact> projects = new ArrayList<>();
            JsonNode projectsNode = tree.get("projects");
            if (projectsNode != null && projectsNode.isArray()) {
                for (JsonNode node : projectsNode) {
                    try {
                        RawProject raw = objectMapper.treeToValue(node, RawProject.class);
                        projects.add(toProjectFact(raw, sourceText));
                    } catch (Exception perProject) {
                        // 单项目畸形只丢弃该项目（salvage），其余幸存
                        log.warn("Skip malformed project (salvage others): {}", perProject.getMessage());
                    }
                }
            }

            if (entities.isEmpty() && projects.isEmpty()) {
                throw new IllegalArgumentException("entity extraction response contains no usable entities or projects");
            }
            return new ResumeEntities(entities, projects);
        } catch (Exception ex) {
            log.warn("Failed to parse entity extraction result: {}", ex.getMessage());
            throw new IllegalArgumentException("invalid entity extraction response", ex);
        }
    }

    /**
     * 降级抽取：LLM 不可用时不产出任何实体（旧实现把文件名当 ORGANIZATION，
     * 导致画像工作时间线显示文件名这类垃圾数据）——空实体 + 降级标记，由调用方显式决策。
     */
    private ResumeEntities fallbackExtract(String resumeText, String fileName) {
        return new ResumeEntities(List.of());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EntityExtractionResult {
        public List<RawEntity> entities;
        public List<RawProject> projects;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawEntity {
        public String type;
        public String value;
        public String context;
        public Map<String, String> attributes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawProject {
        public String projectId;
        public String sectionId;
        public RawFact context;
        public RawFact problem;
        public List<RawFact> responsibilities;
        public List<RawFact> technologies;
        public RawFact aiPipeline;
        public List<RawFact> decisions;
        public RawFact results;
        public RawFact scale;
        public RawFact deployment;
    }

    /**
     * 单事实字段：value/status/sourceQuote。
     * <p>宽容反序列化（2026-09-18 事故修复）：模型偶尔把本应为对象的字段输出为数组
     * （如 results），旧代码整份解析直接炸。现在数组取首个对象元素，空数组/裸字符串置空
     * （该事实记 missing），不炸整份抽取。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonDeserialize(using = ResumeEntityExtractor.RawFactDeserializer.class)
    static class RawFact {
        public String value;
        public String status;
        public String sourceQuote;
    }

    /** RawFact 的宽容反序列化器：接受对象/数组/空值，其余形态不炸。 */
    static class RawFactDeserializer extends JsonDeserializer<RawFact> {
        @Override
        public RawFact deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonNode node = p.readValueAsTree();
            if (node == null || node.isNull()) {
                return null;
            }
            if (node.isArray()) {
                // 契约外形态（log.txt 事故：results 输出为数组）——取首个对象元素，无则置空
                JsonNode first = node.size() > 0 ? node.get(0) : null;
                if (first == null || !first.isObject()) {
                    return null;
                }
                node = first;
            }
            if (!node.isObject()) {
                return null;
            }
            RawFact fact = new RawFact();
            fact.value = textOf(node, "value");
            fact.status = textOf(node, "status");
            fact.sourceQuote = textOf(node, "sourceQuote");
            return fact;
        }

        private static String textOf(JsonNode node, String field) {
            JsonNode v = node.get(field);
            return v != null && v.isValueNode() ? v.asText() : null;
        }
    }

    private static ResumeProjectFact toProjectFact(RawProject p, String sourceText) {
        return new ResumeProjectFact(
                p.projectId, p.sectionId, fact(p.context, sourceText), fact(p.problem, sourceText),
                facts(p.responsibilities, sourceText), facts(p.technologies, sourceText), fact(p.aiPipeline, sourceText),
                facts(p.decisions, sourceText), fact(p.results, sourceText), fact(p.scale, sourceText), fact(p.deployment, sourceText));
    }

    private static ResumeProjectFact.Fact fact(RawFact raw, String sourceText) {
        if (raw == null) return ResumeProjectFact.Fact.missing();
        boolean grounded = raw.sourceQuote != null && !raw.sourceQuote.isBlank()
                && containsNormalized(sourceText, raw.sourceQuote);
        if (!grounded) return new ResumeProjectFact.Fact(null, "missing", null);
        return new ResumeProjectFact.Fact(raw.value, raw.status, raw.sourceQuote);
    }

    private static List<ResumeProjectFact.Fact> facts(List<RawFact> raw, String sourceText) {
        return raw == null ? List.of() : raw.stream().filter(r -> r != null).map(r -> fact(r, sourceText)).toList();
    }

    private static boolean containsNormalized(String source, String quote) {
        return source != null && quote != null
                && source.replaceAll("\\s+", "").contains(quote.replaceAll("\\s+", ""));
    }
}
