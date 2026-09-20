package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Locale;

/**
 * 岗位方向画像——curated 的版本化资源（resources/archetypes/*.json），不是 LLM 现编的。
 * <p>为什么用画像簇而不是单一 JD：广撒网场景下没有具体 JD，但"AI 应用开发"这类
 * 方向背后是一个稳定的要求分布——共性要求（mustHaves）严格照查，
 * 分化要求（variants）转为"你最适合哪个子方向"的定位建议。
 * 画像 curated 保证稳定、可版本化、可进 golden case（与 prompt 的 eval 哲学一致）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Archetype {

    private String id;
    private String version;
    private String name;
    private String summary;
    private List<String> aliases = List.of();
    private List<MustHave> mustHaves = List.of();
    private List<Variant> variants = List.of();
    private List<String> screeningQuestions = List.of();

    /** 共性要求：簇内几乎每份 JD 都要，缺失时广撒网救不了。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MustHave {
        private String id;
        private String priority = "MUST";
        private String requirement;
        private List<String> evidenceExpected = List.of();
        private List<String> evidenceHints = List.of();

        public String getId() { return id; }
        public String getPriority() { return priority; }
        public String getRequirement() { return requirement; }
        public List<String> getEvidenceExpected() { return evidenceExpected; }
        public List<String> getEvidenceHints() { return evidenceHints; }

        public void setId(String id) { this.id = id; }
        public void setPriority(String priority) { this.priority = priority; }
        public void setRequirement(String requirement) { this.requirement = requirement; }
        public void setEvidenceExpected(List<String> evidenceExpected) {
            this.evidenceExpected = evidenceExpected == null ? List.of() : evidenceExpected;
        }
        public void setEvidenceHints(List<String> evidenceHints) {
            this.evidenceHints = evidenceHints == null ? List.of() : evidenceHints;
        }
    }

    /** 分化要求：各子方向不一致，转为适配排序而非缺口。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Variant {
        private String id;
        private String name;
        private List<String> differentiators = List.of();

        public String getId() { return id; }
        public String getName() { return name; }
        public List<String> getDifferentiators() { return differentiators; }

        public void setId(String id) { this.id = id; }
        public void setName(String name) { this.name = name; }
        public void setDifferentiators(List<String> differentiators) {
            this.differentiators = differentiators == null ? List.of() : differentiators;
        }
    }

    /** 方向短语是否命中此画像（id/name/aliases，忽略大小写与空白）。 */
    public boolean matches(String direction) {
        if (direction == null || direction.isBlank()) {
            return false;
        }
        String d = normalize(direction);
        if (d.contains(normalize(id)) || d.contains(normalize(name))) {
            return true;
        }
        return aliases.stream().anyMatch(a -> d.contains(normalize(a)) || normalize(a).contains(d));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_/]", "");
    }

    public String getId() { return id; }
    public String getVersion() { return version; }
    public String getName() { return name; }
    public String getSummary() { return summary; }
    public List<String> getAliases() { return aliases; }
    public List<MustHave> getMustHaves() { return mustHaves; }
    public List<Variant> getVariants() { return variants; }
    public List<String> getScreeningQuestions() { return screeningQuestions; }

    public void setId(String id) { this.id = id; }
    public void setVersion(String version) { this.version = version; }
    public void setName(String name) { this.name = name; }
    public void setSummary(String summary) { this.summary = summary; }
    public void setAliases(List<String> aliases) { this.aliases = aliases == null ? List.of() : aliases; }
    public void setMustHaves(List<MustHave> mustHaves) { this.mustHaves = mustHaves == null ? List.of() : mustHaves; }
    public void setVariants(List<Variant> variants) { this.variants = variants == null ? List.of() : variants; }
    public void setScreeningQuestions(List<String> screeningQuestions) { this.screeningQuestions = screeningQuestions == null ? List.of() : screeningQuestions; }
}
