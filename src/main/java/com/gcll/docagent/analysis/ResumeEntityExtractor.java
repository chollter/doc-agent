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
 */
@Service
public class ResumeEntityExtractor {

    private static final Logger log = LoggerFactory.getLogger(ResumeEntityExtractor.class);
    private static final String PROMPT_FILE = "resume-entity-extract.txt";

    private final ObjectProvider<LlmGateway> llmGatewayProvider;
    private final ObjectMapper objectMapper;

    public ResumeEntityExtractor(ObjectProvider<LlmGateway> llmGatewayProvider,
                                  ObjectMapper objectMapper) {
        this.llmGatewayProvider = llmGatewayProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 从简历文本中提取语义实体。
     * LLM 不可用时返回基础实体（降级）。
     */
    public ResumeEntities extract(String resumeText, String fileName) {
        if (resumeText == null || resumeText.isBlank()) {
            return new ResumeEntities(List.of());
        }

        LlmGateway llmGateway = llmGatewayProvider.getIfAvailable();
        if (llmGateway == null) {
            log.warn("LLM not available, falling back to basic entity extraction");
            return fallbackExtract(resumeText, fileName);
        }

        try {
            LlmResponse response = llmGateway.invoke(PROMPT_FILE, resumeText);
            return parseEntities(response.content());
        } catch (Exception ex) {
            log.warn("Entity extraction failed, using fallback: {}", ex.getMessage());
            return fallbackExtract(resumeText, fileName);
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
     * 降级抽取：LLM 不可用时，只提取基础信息。
     */
    private ResumeEntities fallbackExtract(String resumeText, String fileName) {
        List<ResumeEntity> entities = new ArrayList<>();
        entities.add(ResumeEntity.of(
                ResumeEntity.EntityType.ORGANIZATION,
                fileName != null ? fileName : "unknown",
                "fallback: file name only"
        ));
        return new ResumeEntities(entities);
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
