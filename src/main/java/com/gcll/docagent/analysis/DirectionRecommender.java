package com.gcll.docagent.analysis;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方向建议的可靠性闸门:把 LLM 的"提名"过滤成可落地结论。
 * <p>三条不变量,全部确定性、可单测:
 * <ol>
 *   <li>反编造——方向的每条 evidence 必须是简历原文的连续片段(空白规整后匹配),
 *       一条都锚不回原文的方向直接丢弃,不进入结论。</li>
 *   <li>tier 由代码定——被引证据能匹配到的最高 {@link EvidenceLevel} ≥ L3_RESULT
 *       才算 BEST_FIT(稳妥),否则 STRETCH(跳一跳)。LLM 不能自称稳妥。</li>
 *   <li>gap 来自证据评估——STRETCH 的缺口优先取匹配评估的 missingFacts,
 *       而非 LLM 自由发挥,保证"补哪块"与质量诊断口径一致。</li>
 * </ol>
 */
@Service
public class DirectionRecommender {

    public List<DirectionRecommendation> recommend(List<DirectionRecommendation.Proposal> proposals,
                                                   String fullText,
                                                   List<EvidenceAssessment> assessments) {
        if (proposals == null || proposals.isEmpty()) {
            return List.of();
        }
        String source = normalize(fullText);
        List<EvidenceAssessment> available = assessments == null ? List.of() : assessments;
        // 同名方向去重:保留 tier 更高者(BEST_FIT > STRETCH),稳定保序
        Map<String, DirectionRecommendation> byDirection = new LinkedHashMap<>();
        for (DirectionRecommendation.Proposal p : proposals) {
            if (p == null || p.direction() == null || p.direction().isBlank()) {
                continue;
            }
            List<String> grounded = p.evidence().stream()
                    .filter(q -> q != null && !q.isBlank() && source.contains(normalize(q)))
                    .map(q -> q.replaceAll("\\s+", " ").trim())
                    .toList();
            if (grounded.isEmpty()) {
                continue; // 无原文支撑的方向不成立
            }
            EvidenceAssessment best = bestMatch(grounded, available);
            EvidenceLevel level = best == null ? EvidenceLevel.L1_ACTIVITY : best.evidenceLevel();
            DirectionRecommendation.Tier tier = level.ordinal() >= EvidenceLevel.L3_RESULT.ordinal()
                    ? DirectionRecommendation.Tier.BEST_FIT
                    : DirectionRecommendation.Tier.STRETCH;
            String gap = tier == DirectionRecommendation.Tier.STRETCH
                    ? stretchGap(best, p.gap())
                    : clean(p.gap());
            DirectionRecommendation rec = new DirectionRecommendation(
                    p.direction().trim(), tier, grounded, clean(p.sectionId()), gap);
            String key = normalize(p.direction());
            DirectionRecommendation existing = byDirection.get(key);
            if (existing == null || rec.tier() == DirectionRecommendation.Tier.BEST_FIT
                    && existing.tier() != DirectionRecommendation.Tier.BEST_FIT) {
                byDirection.put(key, rec);
            }
        }
        List<DirectionRecommendation> out = new ArrayList<>(byDirection.values());
        // 稳妥方向排在前
        out.sort(Comparator.comparingInt(r -> r.tier() == DirectionRecommendation.Tier.BEST_FIT ? 0 : 1));
        return List.copyOf(out);
    }

    /** 找到与已回锚证据文本重合、且等级最高的证据评估。 */
    private static EvidenceAssessment bestMatch(List<String> grounded, List<EvidenceAssessment> assessments) {
        return assessments.stream()
                .filter(a -> a != null && a.evidenceFound().stream()
                        .anyMatch(found -> grounded.stream()
                                .anyMatch(q -> overlaps(found, q))))
                .max(Comparator.comparingInt(a -> a.evidenceLevel().ordinal()))
                .orElse(null);
    }

    private static String stretchGap(EvidenceAssessment best, String proposedGap) {
        if (best != null) {
            String first = best.missingFacts().stream()
                    .filter(m -> m != null && !m.isBlank())
                    .findFirst().orElse(null);
            if (first != null) {
                return first;
            }
        }
        return clean(proposedGap);
    }

    private static boolean overlaps(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        return !na.isEmpty() && !nb.isEmpty() && (na.contains(nb) || nb.contains(na));
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "");
    }

    private static String clean(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").trim();
    }
}
