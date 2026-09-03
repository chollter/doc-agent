package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
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
    private List<Vocabulary> vocabulary = List.of();
    private List<String> screeningQuestions = List.of();

    /** 共性要求：簇内几乎每份 JD 都要，缺失时广撒网救不了。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MustHave {
        private String id;
        private String requirement;
        private List<String> evidenceHints = List.of();

        public String getId() { return id; }
        public String getRequirement() { return requirement; }
        public List<String> getEvidenceHints() { return evidenceHints; }
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
    }

    /** 高频术语与同义词映射——词汇覆盖检查的数据源。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Vocabulary {
        private String term;
        private List<String> synonyms = List.of();

        public String getTerm() { return term; }
        public List<String> getSynonyms() { return synonyms; }
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

    /**
     * 词汇覆盖检查（纯代码）：术语本身未出现、但其同义词在简历中出现 →
     * 建议补术语。同义词也没出现则不建议——只做"表述升级"，不教关键词造假。
     */
    public List<VocabularyGap> findVocabularyGaps(String resumeText) {
        List<VocabularyGap> gaps = new ArrayList<>();
        if (resumeText == null || resumeText.isBlank()) {
            return gaps;
        }
        String text = resumeText.toLowerCase(Locale.ROOT);
        for (Vocabulary v : vocabulary) {
            String term = v.getTerm().toLowerCase(Locale.ROOT);
            if (text.contains(term)) {
                continue;
            }
            for (String syn : v.getSynonyms()) {
                String s = syn.toLowerCase(Locale.ROOT);
                if (text.contains(s)) {
                    gaps.add(new VocabularyGap(v.getTerm(), syn,
                            "简历使用「" + syn + "」，建议补充行业术语「" + v.getTerm() + "」以命中关键词检索"));
                    break;
                }
            }
        }
        return gaps;
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
    public List<Vocabulary> getVocabulary() { return vocabulary; }
    public List<String> getScreeningQuestions() { return screeningQuestions; }
}
