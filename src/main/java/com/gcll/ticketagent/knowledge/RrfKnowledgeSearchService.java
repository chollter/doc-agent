package com.gcll.ticketagent.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索融合层：同时调用向量检索与关键词检索，用 RRF（Reciprocal Rank Fusion）融合结果。
 *
 * <h3>为什么用 RRF 而非加权融合</h3>
 * <p>向量检索返回 cosine 相似度（0.5~1.0，受 similarityThreshold 约束），关键词检索返回
 * 累加权重分（无上界，可 &gt;1.0）。两者量纲不同，加权融合需先归一化（标定困难）；RRF 只看
 * 每路的排名位置（{@code 1/(k+rank)}），不依赖绝对分，天然绕开量纲问题。
 *
 * <h3>激活条件</h3>
 * <ul>
 *   <li>{@code @ConditionalOnBean(VectorStore.class)}：有向量库才有融合意义；无则让
 *       {@link RelationalKnowledgeSearchService} 兜底（它无条件注册）。</li>
 *   <li>{@code @ConditionalOnProperty(opsmind.rag.fusion.enabled=true)}：可通过配置关闭，
 *       关闭时回退到纯向量检索（此时向量服务的 {@code @Primary} 重新生效）。</li>
 * </ul>
 *
 * <h3>降级策略（对齐项目"不丢召回"理念）</h3>
 * <ul>
 *   <li>向量路异常/空 → 返回关键词路结果（反之类推）。</li>
 *   <li>两路都空 → 返回空（下游 LinearInvestigationStrategy 已有"无命中"降级）。</li>
 * </ul>
 *
 * <p>对调用方完全透明：实现 {@link KnowledgeSearchService}，作为 {@code @Primary} 替换
 * 原 {@link VectorKnowledgeSearchService}（后者去掉 @Primary 降为内部组件）。
 */
@Service
@Primary
@ConditionalOnBean(VectorStore.class)
@ConditionalOnProperty(prefix = "opsmind.rag.fusion", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RrfKnowledgeSearchService implements KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(RrfKnowledgeSearchService.class);

    /** 融合后的命中原因，写入审计便于区分检索来源。 */
    private static final String FUSION_REASON = "RRF 融合(向量+关键词)";

    private final VectorKnowledgeSearchService vectorSearch;
    private final RelationalKnowledgeSearchService keywordSearch;
    private final RrfProperties properties;

    public RrfKnowledgeSearchService(
            VectorKnowledgeSearchService vectorSearch,
            RelationalKnowledgeSearchService keywordSearch,
            RrfProperties properties
    ) {
        this.vectorSearch = vectorSearch;
        this.keywordSearch = keywordSearch;
        this.properties = properties;
    }

    @Override
    public List<KnowledgeHit> search(String query, String systemName, String moduleName, String issueType) {
        List<KnowledgeHit> vectorHits = safeSearch("vector", vectorSearch, query, systemName, moduleName, issueType);
        List<KnowledgeHit> keywordHits = safeSearch("keyword", keywordSearch, query, systemName, moduleName, issueType);

        // 降级：一路空就返回另一路（不丢召回）
        if (vectorHits.isEmpty()) {
            return keywordHits;
        }
        if (keywordHits.isEmpty()) {
            return vectorHits;
        }

        return rrfFusion(vectorHits, keywordHits, properties.getK(), properties.getTopK());
    }

    /**
     * 安全调用一路检索，异常时记录 warn 并返回空（不影响另一路）。
     * 用具体类型而非接口注入，避免自调用循环。
     */
    private List<KnowledgeHit> safeSearch(String name, KnowledgeSearchService search,
                                          String query, String systemName, String moduleName, String issueType) {
        try {
            List<KnowledgeHit> hits = search.search(query, systemName, moduleName, issueType);
            return hits == null ? List.of() : hits;
        } catch (Exception ex) {
            log.warn("{} search failed, degrading to empty: {}", name, ex.getMessage());
            return List.of();
        }
    }

    /**
     * RRF 融合核心：按 sourceId 聚合两路排名分，取 topK。
     *
     * <pre>
     * 向量路 [A, C, D] + 关键词路 [B, A, E]（k=60）：
     *   A: 1/(60+1) + 1/(60+2) = 0.0325  ← 两路都命中，分最高
     *   B: 1/(60+1) = 0.0164
     *   C: 1/(60+2) = 0.0161
     *   ...
     * → [A, B, C, D, E]
     * </pre>
     *
     * @param vHits 向量路结果（已按相关性降序）
     * @param kHits 关键词路结果（已按相关性降序）
     * @param k     平滑参数（默认 60）
     * @param topK  返回条数
     */
    static List<KnowledgeHit> rrfFusion(List<KnowledgeHit> vHits, List<KnowledgeHit> kHits, int k, int topK) {
        Map<String, RrfAccumulator> acc = new LinkedHashMap<>();

        // 向量路：rank 从 0 开始，RRF 公式 1/(k + rank + 1)
        for (int rank = 0; rank < vHits.size(); rank++) {
            KnowledgeHit h = vHits.get(rank);
            // 优先保留向量路的 hit（含 rerank 元信息），关键词路同 sourceId 时只加分不替换 hit
            acc.computeIfAbsent(h.sourceId(), id -> new RrfAccumulator(h))
                    .addScore(rrfScore(k, rank));
        }
        // 关键词路
        for (int rank = 0; rank < kHits.size(); rank++) {
            KnowledgeHit h = kHits.get(rank);
            acc.computeIfAbsent(h.sourceId(), id -> new RrfAccumulator(h))
                    .addScore(rrfScore(k, rank));
        }

        return acc.values().stream()
                .sorted(Comparator.comparingDouble(RrfAccumulator::sumScore).reversed())
                .limit(topK)
                .map(RrfAccumulator::toFusedHit)
                .toList();
    }

    /** RRF 单路得分：{@code 1/(k + rank + 1)}，rank 从 0 起。 */
    private static double rrfScore(int k, int rank) {
        return 1.0 / (k + rank + 1);
    }

    /** 聚合器：累加同一 sourceId 的 RRF 分数，保留首个（优先向量路）的元信息。 */
    private static final class RrfAccumulator {
        private final KnowledgeHit hit;
        private double sumScore;

        RrfAccumulator(KnowledgeHit hit) {
            this.hit = hit;
        }

        void addScore(double score) {
            this.sumScore += score;
        }

        double sumScore() {
            return sumScore;
        }

        /** 重建 KnowledgeHit：score 用 RRF 融合分，matchedReason 标注融合来源，reranked=true。 */
        KnowledgeHit toFusedHit() {
            return new KnowledgeHit(
                    hit.sourceId(),
                    hit.sourceType(),
                    hit.title(),
                    hit.summary(),
                    hit.resolution(),
                    sumScore,
                    FUSION_REASON,
                    true
            );
        }
    }
}
