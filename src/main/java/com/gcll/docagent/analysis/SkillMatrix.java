package com.gcll.docagent.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能分类矩阵——将扁平的技能列表按技术领域归类。
 * <p>分类基于硬编码的关键词映射，未命中的归入 others。
 */
public record SkillMatrix(
        List<String> languages,
        List<String> frameworks,
        List<String> infrastructure,
        List<String> databases,
        List<String> others
) {

    private static final Map<String, Set<String>> CATEGORY_KEYWORDS = Map.of(
            "languages", Set.of("java", "python", "go", "golang", "c++", "c#", "ruby",
                    "kotlin", "scala", "rust", "typescript", "javascript", "php", "swift"),
            "frameworks", Set.of("spring", "spring boot", "spring cloud", "mybatis",
                    "hibernate", "react", "vue", "angular", "django", "flask",
                    "express", "fastapi", "langchain", "langchain4j"),
            "infrastructure", Set.of("docker", "kubernetes", "k8s", "jenkins", "gitlab ci",
                    "github actions", "nginx", "redis", "rabbitmq", "kafka", "elasticsearch",
                    "consul", "nacos", "eureka", "zookeeper", "minio", "istio",
                    "service mesh", "terraform", "ansible", "prometheus", "grafana"),
            "databases", Set.of("mysql", "postgresql", "postgres", "oracle", "sql server",
                    "mongodb", "cassandra", "dynamodb", "sqlite", "h2", "tidb",
                    "clickhouse", "elasticsearch")
    );

    /**
     * 从技能名称列表构建分类矩阵。
     * 匹配时忽略大小写，支持部分匹配（如 "Spring Boot" 匹配 "spring boot"）。
     */
    public static SkillMatrix fromSkillNames(List<String> skillNames) {
        List<String> langs = new ArrayList<>();
        List<String> frames = new ArrayList<>();
        List<String> infra = new ArrayList<>();
        List<String> dbs = new ArrayList<>();
        List<String> other = new ArrayList<>();

        for (String skill : skillNames) {
            if (skill == null || skill.isBlank()) continue;
            String lower = skill.toLowerCase().trim();
            String matched = null;

            for (var entry : CATEGORY_KEYWORDS.entrySet()) {
                for (String kw : entry.getValue()) {
                    if (lower.equals(kw) || lower.contains(kw) || kw.contains(lower)) {
                        matched = entry.getKey();
                        break;
                    }
                }
                if (matched != null) break;
            }

            if (matched == null) {
                other.add(skill);
            } else {
                switch (matched) {
                    case "languages" -> langs.add(skill);
                    case "frameworks" -> frames.add(skill);
                    case "infrastructure" -> infra.add(skill);
                    case "databases" -> dbs.add(skill);
                }
            }
        }

        return new SkillMatrix(langs, frames, infra, dbs, other);
    }
}
