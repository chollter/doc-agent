package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gcll.docagent.llm.LlmGateway;
import com.gcll.docagent.resilience.LlmResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

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
                    entityNormalizer.normalize(parseEntities(response.content())));
        } catch (Exception first) {
            // 瞬时故障（提供商响应异常/网络抖动）再试一次，减少误降级横幅；
            // 两次都失败才降级——降级标记机制本身保留（显式告知而非静默）。
            log.warn("Entity extraction failed once, retrying: {}", first.getMessage());
            try {
                LlmResponse response = llmGateway.invoke(CALL_NAME, PROMPT_FILE, resumeText, runId);
                return ExtractionOutcome.llm(
                        entityNormalizer.normalize(parseEntities(response.content())));
            } catch (Exception ex) {
                log.warn("Entity extraction failed, using fallback: {}", ex.getMessage());
                return ExtractionOutcome.fallback(fallbackExtract(resumeText, fileName));
            }
        }
    }

    private ResumeEntities parseEntities(String json) {
        try {
            String cleaned = json.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```(?:json)?\\s*", "")
                        .replaceAll("\\s*```$", "");
            }

            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return new ResumeEntities(List.of());
            }

            EntityExtractionResult result = objectMapper.readValue(
                    cleaned.substring(start, end + 1),
                    EntityExtractionResult.class
            );

            List<ResumeEntity> entities = new ArrayList<>();
            if (result.entities != null) {
                for (RawEntity raw : result.entities) {
                    if (raw.type != null && raw.value != null) {
                        try {
                            ResumeEntity.EntityType type = ResumeEntity.EntityType.valueOf(raw.type.toUpperCase());
                            Map<String, String> attrs = raw.attributes != null
                                    ? raw.attributes
                                    : Map.of();
                            entities.add(new ResumeEntity(type, raw.value, raw.context, attrs));
                        } catch (IllegalArgumentException e) {
                            log.debug("Unknown entity type: {}", raw.type);
                        }
                    }
                }
            }

            return new ResumeEntities(entities);
        } catch (Exception ex) {
            log.warn("Failed to parse entity extraction result: {}", ex.getMessage());
            return new ResumeEntities(List.of());
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
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawEntity {
        public String type;
        public String value;
        public String context;
        public Map<String, String> attributes;
    }
}
