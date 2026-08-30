package com.gcll.docagent.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 方向画像注册表——加载 classpath:archetypes/*.json。
 * <p>方向短语 → 画像的解析优先走代码（id/name/alias 匹配），
 * 匹配不上再由服务层走一次便宜的 LLM 调用（archetype-map.txt），
 * 画像内容本身永远来自 curated 资源，LLM 不参与生成。
 */
@Component
public class ArchetypeRegistry {

    private static final Logger log = LoggerFactory.getLogger(ArchetypeRegistry.class);

    private final ObjectMapper objectMapper;
    private List<Archetype> archetypes = List.of();

    public ArchetypeRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void load() {
        try {
            var resolver = new PathMatchingResourcePatternResolver();
            var resources = resolver.getResources("classpath:archetypes/*.json");
            List<Archetype> loaded = new java.util.ArrayList<>();
            for (var res : resources) {
                try (var in = res.getInputStream()) {
                    loaded.add(objectMapper.readValue(
                            new String(in.readAllBytes(), StandardCharsets.UTF_8), Archetype.class));
                }
            }
            this.archetypes = List.copyOf(loaded);
            log.info("Loaded {} archetypes: {}", loaded.size(),
                    loaded.stream().map(Archetype::getId).toList());
        } catch (Exception ex) {
            log.warn("Failed to load archetypes: {}", ex.getMessage());
            this.archetypes = List.of();
        }
    }

    public List<Archetype> all() {
        return archetypes;
    }

    /** 方向短语解析：精确度优先代码匹配，失败返回 empty 由调用方决定是否走 LLM。 */
    public Optional<Archetype> resolve(String direction) {
        if (direction == null || direction.isBlank()) {
            return Optional.empty();
        }
        return archetypes.stream().filter(a -> a.matches(direction)).findFirst();
    }
}
