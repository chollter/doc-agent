package com.gcll.docagent.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LLM 原始 JSON → 领域对象的容错映射器。
 * <p>职责单一：把模型输出（可能带代码围栏、字段畸形、违反对象契约）解析为
 * {@link ParsedAnalysis}（稳定结果 + {@link LlmFunnelFields} 五角度字段）与
 * {@link FunnelVerdict.Evaluation}（评价专调）。解析失败一律抛
 * {@link IllegalArgumentException}，由调用方决定降级策略——本类不做兜底判断。
 */
@Component
public class FunnelFieldsMapper {

    private final ObjectMapper objectMapper;

    public FunnelFieldsMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 容错解析 LLM 输出：剥代码围栏、截取最外层 JSON 对象。 */
    ParsedAnalysis parseResult(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("empty llm content");
        }
        String json = stripFences(content);
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no json object in llm content");
        }
        try {
            // 容错：先读树清洗（模型偶尔违反对象契约输出字符串数组），再映射为 RawResult，
            // 避免单字段畸形导致整个结果作废落入规则兑底。
            JsonNode tree = objectMapper.readTree(json.substring(start, end + 1));
            sanitizeStringElements(tree);
            RawResult raw = objectMapper.treeToValue(tree, RawResult.class);
            if (raw.summary == null || raw.summary.isBlank()) {
                throw new IllegalArgumentException("missing summary field");
            }
            List<AnalysisResult.Citation> citations = new ArrayList<>();
            if (raw.citations != null) {
                for (RawCitation c : raw.citations) {
                    if (c != null && c.sectionId != null && c.quote != null) {
                        citations.add(new AnalysisResult.Citation(c.sectionId, c.quote));
                    }
                }
            }
            // keyPoints/risks 已从 LLM 输出契约下线（v8）：优先用显式输出（兼容 mock/评测旧数据），
            // 缺失时从 leverageCards 派生——避免模型手抄结构化字段造成两份不一致的真相。
            List<LeverageCard> leverageCards = parseLeverageCards(raw.leverageCards);
            List<String> keyPoints = raw.keyPoints != null && !raw.keyPoints.isEmpty()
                    ? raw.keyPoints
                    : leverageCards.stream()
                            .filter(c -> c.kind() == LeverageCard.Kind.STRENGTH)
                            .map(LeverageCard::point)
                            .toList();
            List<String> risks = raw.risks != null && !raw.risks.isEmpty()
                    ? raw.risks
                    : leverageCards.stream()
                            .filter(c -> c.kind() == LeverageCard.Kind.RISK)
                            .map(LeverageCard::point)
                            .toList();
            AnalysisResult analysis = new AnalysisResult(
                    raw.summary,
                    keyPoints,
                    risks,
                    citations,
                    null,
                    parseActionableSuggestions(raw.actionableSuggestions),
                    null);
            LlmFunnelFields funnel = new LlmFunnelFields(
                    parsePresentation(raw.presentation),
                    parseExperienceStrength(raw.experienceStrength),
                    leverageCards,
                    parseCoverage(raw.mustHaveCoverage),
                    parsePositioning(raw.positioning),
                    parseDirectionProposals(raw.recommendedDirections));
            return new ParsedAnalysis(analysis, funnel);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid result json: " + ex.getMessage(), ex);
        }
    }

    /** 解析评价专调 JSON（容错：剥围栏、截最外层对象），失败向上抛由调用方兑现降级。 */
    FunnelVerdict.Evaluation parseEvaluationJson(String content) throws JsonProcessingException {
        String json = stripFences(content);
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no json object in evaluation content");
        }
        return parseEvaluation(objectMapper.readValue(json.substring(start, end + 1), RawEvaluation.class));
    }

    private static String stripFences(String content) {
        String s = content.trim();
        if (s.startsWith("```")) {
            int firstLineEnd = s.indexOf('\n');
            if (firstLineEnd > 0) {
                s = s.substring(firstLineEnd + 1);
            }
            int fenceEnd = s.lastIndexOf("```");
            if (fenceEnd >= 0) {
                s = s.substring(0, fenceEnd);
            }
        }
        return s.trim();
    }

    /**
     * 解析容错：模型偶尔把对象数组字段输出为字符串数组（违反 prompt 契约），
     * 逐字段把字符串元素包成对象，让其余字段的分析结果幸存。
     */
    private static void sanitizeStringElements(JsonNode tree) {
        if (tree == null || !tree.isObject()) return;
        wrapStringElements(tree, "mustHaveCoverage", "requirement");
    }

    private static void wrapStringElements(JsonNode root, String field, String targetKey) {
        JsonNode arr = root.get(field);
        if (arr == null || !arr.isArray()) return;
        ArrayNode array = (ArrayNode) arr;
        for (int i = 0; i < array.size(); i++) {
            JsonNode el = array.get(i);
            if (el.isTextual()) {
                ObjectNode wrapped = new ObjectNode(JsonNodeFactory.instance);
                wrapped.put(targetKey, el.asText());
                array.set(i, wrapped);
            }
        }
    }

    private static Presentation parsePresentation(RawPresentation raw) {
        if (raw == null || raw.score == null) return null;
        return Presentation.of(raw.score, raw.issues);
    }

    private static List<ExperienceStrength> parseExperienceStrength(List<RawExperienceStrength> raw) {
        if (raw == null) return List.of();
        List<ExperienceStrength> out = new ArrayList<>();
        for (RawExperienceStrength r : raw) {
            if (r == null || r.star == null) continue;
            try {
                out.add(new ExperienceStrength(
                        r.sectionId, r.entryRef, r.star,
                        r.resultQuality == null ? ExperienceStrength.ResultQuality.NONE
                                : ExperienceStrength.ResultQuality.valueOf(r.resultQuality.toUpperCase()),
                        r.attribution == null ? ExperienceStrength.Attribution.PARTICIPANT
                                : ExperienceStrength.Attribution.valueOf(r.attribution.toUpperCase()),
                        r.concern));
            } catch (IllegalArgumentException ignored) {
                // 枚举值非法的条目丢弃，不影响其余
            }
        }
        return out;
    }

    private static List<LeverageCard> parseLeverageCards(List<RawLeverageCard> raw) {
        if (raw == null) return List.of();
        List<LeverageCard> out = new ArrayList<>();
        for (RawLeverageCard r : raw) {
            if (r == null || r.point == null || r.point.isBlank()) continue;
            LeverageCard.Kind kind;
            try {
                kind = r.kind == null ? LeverageCard.Kind.STRENGTH
                        : LeverageCard.Kind.valueOf(r.kind.toUpperCase());
            } catch (IllegalArgumentException e) {
                kind = "RISK".equalsIgnoreCase(r.kind) ? LeverageCard.Kind.RISK : LeverageCard.Kind.STRENGTH;
            }
            out.add(new LeverageCard(kind, r.point, r.sectionId,
                    r.likelyQuestion, r.prepHint, r.defenseStrategy));
        }
        return out;
    }

    private static List<MustHaveCoverage> parseCoverage(List<RawCoverage> raw) {
        if (raw == null) return List.of();
        List<MustHaveCoverage> out = new ArrayList<>();
        for (RawCoverage r : raw) {
            if (r == null || r.requirementId == null || r.status == null) continue;
            try {
                out.add(new MustHaveCoverage(r.requirementId, r.requirement,
                        MustHaveCoverage.Status.valueOf(r.status.toUpperCase()),
                        r.evidence, r.sectionId));
            } catch (IllegalArgumentException ignored) {
                // 非法状态值丢弃
            }
        }
        return out;
    }

    private static PositioningCheck parsePositioning(RawPositioning raw) {
        if (raw == null || raw.anchored == null) return null;
        return new PositioningCheck(raw.anchored, raw.currentAnchor, raw.suggestedAnchor, raw.comment);
    }

    /** LLM 提名 → Proposal(未经校验)。grounding 与 tier 判定在 DirectionRecommender 里做。 */
    private static List<DirectionRecommendation.Proposal> parseDirectionProposals(List<RawDirection> raw) {
        if (raw == null) return List.of();
        return raw.stream()
                .filter(d -> d != null && d.direction != null && !d.direction.isBlank())
                .map(d -> new DirectionRecommendation.Proposal(d.direction, d.evidence, d.sectionId))
                .toList();
    }

    /** 定性评价（v6）：清洗空白条目；全部为空时返回 null（历史 run/LLM 未产出时前端优雅跳过）。 */
    private static FunnelVerdict.Evaluation parseEvaluation(RawEvaluation raw) {
        if (raw == null) return null;
        String overall = raw.overall != null && !raw.overall.isBlank() ? raw.overall.trim() : null;
        List<FunnelVerdict.Evaluation.DimensionComment> dims = raw.dimensions == null ? List.of()
                : raw.dimensions.stream()
                        .filter(d -> d != null && d.dimension != null && !d.dimension.isBlank()
                                && d.comment != null && !d.comment.isBlank())
                        .map(d -> new FunnelVerdict.Evaluation.DimensionComment(
                                d.dimension.trim(),
                                d.level == null || d.level.isBlank() ? "MEDIUM" : d.level.trim().toUpperCase(),
                                d.comment.trim(),
                                d.evidence,
                                d.issueType == null || d.issueType.isBlank() ? "NONE" : d.issueType.trim().toUpperCase()))
                        .toList();
        List<String> strengths = cleanStrings(raw.strengths);
        List<String> weaknesses = cleanStrings(raw.weaknesses);
        if (overall == null && dims.isEmpty() && strengths.isEmpty() && weaknesses.isEmpty()) {
            return null;
        }
        return new FunnelVerdict.Evaluation(overall, dims, strengths, weaknesses);
    }

    private static List<String> cleanStrings(List<String> list) {
        if (list == null) return List.of();
        return list.stream().filter(s -> s != null && !s.isBlank()).map(String::trim).toList();
    }

    private static List<ActionableSuggestion> parseActionableSuggestions(List<RawActionableSuggestion> raw) {
        if (raw == null) return null;
        return raw.stream().filter(Objects::nonNull)
                .map(s -> new ActionableSuggestion(
                        s.severity, s.target, s.sectionId, s.before, s.after, s.reason))
                .toList();
    }

    // --- LLM 原始输出 DTO（弱契约，仅本类可见） ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawResult {
        public String summary;
        public List<String> keyPoints;
        public List<String> risks;
        public List<RawCitation> citations;
        public List<RawActionableSuggestion> actionableSuggestions;
        // P12 五角度漏斗字段
        public RawPresentation presentation;
        public List<RawExperienceStrength> experienceStrength;
        public List<RawLeverageCard> leverageCards;
        public List<RawCoverage> mustHaveCoverage;
        public RawPositioning positioning;
        public List<RawDirection> recommendedDirections;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawPresentation {
        public Integer score;
        public List<String> issues;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawExperienceStrength {
        public String sectionId;
        public String entryRef;
        public Map<String, Boolean> star;
        public String resultQuality;
        public String attribution;
        public String concern;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawLeverageCard {
        public String kind;
        public String point;
        public String sectionId;
        public String likelyQuestion;
        public String prepHint;
        public String defenseStrategy;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawCoverage {
        public String requirementId;
        public String requirement;
        public String status;
        public String evidence;
        public String sectionId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawPositioning {
        public Boolean anchored;
        public String currentAnchor;
        public String suggestedAnchor;
        public String comment;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawDirection {
        public String direction;
        public List<String> evidence;
        public String sectionId;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawEvaluation {
        public String overall;
        public List<RawDimensionComment> dimensions;
        public List<String> strengths;
        public List<String> weaknesses;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawDimensionComment {
        public String dimension;
        public String level;
        public String comment;
        public List<String> evidence;
        public String issueType;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawActionableSuggestion {
        public String severity;
        public String target;
        public String sectionId;
        public String before;
        public String after;
        public String reason;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class RawCitation {
        public String sectionId;
        public String quote;
    }
}
