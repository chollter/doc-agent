package com.gcll.docagent.analysis;

import com.gcll.docagent.analysis.FunnelVerdict.Evaluation;
import com.gcll.docagent.analysis.FunnelVerdict.Evaluation.DimensionComment;
import com.gcll.docagent.analysis.IterationReport.LandingStatus;
import com.gcll.docagent.analysis.IterationReport.SuggestionLanding;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 确定性迭代 diff——对两个已落库的 {@link AnalysisResult}（上一版基线 + 本次新版）算出"变了什么"。
 *
 * <p>Phase 2 S1 核心。全程纯代码、无 LLM、无 token：红旗按 type+severity+message 集合差、
 * strength 档位迁移、presentation 分数、positioning 锚点变化、evaluation 各维 level 变化、
 * 改法落地检测。缺任一 funnelVerdict 时返回 {@link IterationReport#unavailable}，不编造变化。
 *
 * <p>设计约束（见记忆 no-fabricated-tradeoff-narratives / no-fake-precision）：
 * 只报"由两个结论直接可得的差异"，不推断因果、不给合成分数。
 */
@Component
public class IterationDiffService {

    /** 落地检测：规范化后至少这么多字符才敢判 LANDED/NOT_LANDED，否则判 INDETERMINATE（改法多为占位符时）。 */
    private static final int MIN_LANDABLE_CHARS = 6;

    public IterationReport diff(String baseRunId, String newRunId,
                                AnalysisResult base, AnalysisResult next, String newFullText) {
        FunnelVerdict bf = base == null ? null : base.funnelVerdict();
        FunnelVerdict nf = next == null ? null : next.funnelVerdict();
        if (bf == null || nf == null) {
            return IterationReport.unavailable(baseRunId, newRunId);
        }

        List<RedFlag> added = new ArrayList<>();
        List<RedFlag> removed = new ArrayList<>();
        diffRedFlags(bf.redFlags(), nf.redFlags(), added, removed);

        String bandBefore = bf.strength() == null ? null : bf.strength().band().name();
        String bandAfter = nf.strength() == null ? null : nf.strength().band().name();

        Integer scoreBefore = bf.presentation() == null ? null : bf.presentation().score();
        Integer scoreAfter = nf.presentation() == null ? null : nf.presentation().score();

        boolean posChanged = false;
        String posBefore = anchorOf(bf.positioning());
        String posAfter = anchorOf(nf.positioning());
        if (!safeEquals(posBefore, posAfter)) {
            posChanged = true;
        }

        List<IterationReport.DimensionChange> dimChanges = diffDimensions(bf.evaluation(), nf.evaluation());

        List<SuggestionLanding> landings = detectLandings(base.actionableSuggestions(), newFullText);

        return new IterationReport(baseRunId, newRunId, false,
                added, removed, bandBefore, bandAfter, scoreBefore, scoreAfter,
                posChanged, posBefore, posAfter, dimChanges, landings);
    }

    private static void diffRedFlags(List<RedFlag> before, List<RedFlag> after,
                                     List<RedFlag> added, List<RedFlag> removed) {
        Set<String> beforeKeys = flagKeys(before);
        Set<String> afterKeys = flagKeys(after);
        if (after != null) {
            for (RedFlag f : after) {
                if (!beforeKeys.contains(key(f))) added.add(f);
            }
        }
        if (before != null) {
            for (RedFlag f : before) {
                if (!afterKeys.contains(key(f))) removed.add(f);
            }
        }
    }

    private static Set<String> flagKeys(List<RedFlag> flags) {
        Set<String> keys = new LinkedHashSet<>();
        if (flags != null) {
            for (RedFlag f : flags) keys.add(key(f));
        }
        return keys;
    }

    /** 红旗稳定键：type + severity + message 全等才算同一面旗——跨版本措辞变即视为"移除+新增"，不做模糊归并（避免假判定）。 */
    private static String key(RedFlag f) {
        return f.type() + "::" + f.severity() + "::" + (f.message() == null ? "" : f.message());
    }

    private static List<IterationReport.DimensionChange> diffDimensions(Evaluation before, Evaluation after) {
        Map<String, String> beforeLevels = dimensionLevels(before);
        Map<String, String> afterLevels = dimensionLevels(after);
        List<IterationReport.DimensionChange> changes = new ArrayList<>();
        Set<String> all = new LinkedHashSet<>();
        all.addAll(beforeLevels.keySet());
        all.addAll(afterLevels.keySet());
        for (String dim : all) {
            String lvBefore = beforeLevels.get(dim);
            String lvAfter = afterLevels.get(dim);
            if (!safeEquals(lvBefore, lvAfter)) {
                changes.add(new IterationReport.DimensionChange(dim, lvBefore, lvAfter));
            }
        }
        return changes;
    }

    private static Map<String, String> dimensionLevels(Evaluation eval) {
        Map<String, String> map = new LinkedHashMap<>();
        if (eval == null || eval.dimensions() == null) {
            return map;
        }
        for (DimensionComment d : eval.dimensions()) {
            if (d.dimension() != null) {
                map.put(d.dimension(), d.level());
            }
        }
        return map;
    }

    /**
     * 改法落地检测：基线每条 actionableSuggestion 的 {@code after}，去掉【填：…】占位符并规范化后，
     * 若足够长且作为连续子串出现在新版全文（同样规范化）→ LANDED；太短（改法几乎全是待填占位符）
     * → INDETERMINATE（不假装判得出）；否则 NOT_LANDED。
     */
    private static List<SuggestionLanding> detectLandings(List<ActionableSuggestion> suggestions, String newFullText) {
        List<SuggestionLanding> landings = new ArrayList<>();
        if (suggestions == null || suggestions.isEmpty()) {
            return landings;
        }
        String normalizedNew = normalize(newFullText);
        for (ActionableSuggestion s : suggestions) {
            String target = s.target() == null ? s.sectionId() : s.target();
            String stripped = stripPlaceholders(s.after());
            String needle = normalize(stripped);
            if (needle.length() < MIN_LANDABLE_CHARS) {
                landings.add(new SuggestionLanding(target, LandingStatus.INDETERMINATE));
            } else if (normalizedNew.contains(needle)) {
                landings.add(new SuggestionLanding(target, LandingStatus.LANDED));
            } else {
                landings.add(new SuggestionLanding(target, LandingStatus.NOT_LANDED));
            }
        }
        return landings;
    }

    /** 去掉 【…】 占位符片段（改法里让候选人自填的部分，未填时不应导致判 not-landed 之外还误判 landed）。 */
    private static String stripPlaceholders(String text) {
        if (text == null) return "";
        return text.replaceAll("【[^】]*】", "");
    }

    /** 规范化：仅保留中日韩文字与字母数字，去空白与标点，使落地匹配不受排版差异影响。 */
    private static String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("[^\\p{IsHan}\\p{Alnum}]", "");
    }

    private static String anchorOf(PositioningCheck p) {
        if (p == null) return null;
        if (p.anchored() && p.currentAnchor() != null) return p.currentAnchor();
        if (p.suggestedAnchor() != null) return p.suggestedAnchor();
        return null;
    }

    private static boolean safeEquals(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
