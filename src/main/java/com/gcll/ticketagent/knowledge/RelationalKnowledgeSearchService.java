package com.gcll.ticketagent.knowledge;

import com.gcll.ticketagent.persistence.entity.KnowledgeDocumentEntity;
import com.gcll.ticketagent.persistence.repository.KnowledgeDocumentRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RelationalKnowledgeSearchService implements KnowledgeSearchService {

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;

    public RelationalKnowledgeSearchService(KnowledgeDocumentRepository knowledgeDocumentRepository) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
    }

    @Override
    public List<KnowledgeHit> search(String query, String systemName, String moduleName, String issueType) {
        // SQL 层预过滤：按 systemName/moduleName 缩小结果集（替代 findAll 全表扫描）
        // 后续 Java 层打分负责中英文别名模糊匹配和 query token 匹配
        List<KnowledgeDocumentEntity> documents;
        if (systemName != null || moduleName != null) {
            documents = knowledgeDocumentRepository.findBySystemNameOrModuleName(systemName, moduleName);
        } else {
            documents = knowledgeDocumentRepository.findAll();
        }
        List<KnowledgeHit> hits = new ArrayList<>();
        String lowerQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<String> queryTokens = tokenize(lowerQuery);

        for (KnowledgeDocumentEntity doc : documents) {
            // 不硬过滤 system/module：中英文别名（Payment service vs 支付系统）会误杀，
            // 改由 scoreDocument 打分体现相关度（匹配上的加分，不匹配的不丢弃）
            double score = scoreDocument(doc, lowerQuery, queryTokens, systemName, moduleName, issueType);
            if (score > 0) {
                hits.add(new KnowledgeHit(
                        doc.getSourceId(),
                        doc.getSourceType(),
                        doc.getTitle(),
                        truncate(doc.getContent(), 120),
                        doc.getContent(),
                        score,
                        "关键词与系统模块匹配"
                ));
            }
        }
        hits.sort(Comparator.comparingDouble(KnowledgeHit::score).reversed());
        return hits.stream().limit(5).toList();
    }

    private double scoreDocument(
            KnowledgeDocumentEntity doc,
            String lowerQuery,
            Set<String> queryTokens,
            String systemName,
            String moduleName,
            String issueType
    ) {
        double score = 0;
        String content = lower(doc.getContent());
        String title = lower(doc.getTitle());
        String tags = lower(doc.getTags());
        String combined = title + " " + content + " " + tags;

        if (systemName != null && doc.getSystemName() != null && systemName.contains(doc.getSystemName())) {
            score += 0.35;
        }
        if (moduleName != null && doc.getModuleName() != null && moduleName.contains(doc.getModuleName())) {
            score += 0.25;
        }
        if (issueType != null && combined.contains(issueType.toLowerCase(Locale.ROOT))) {
            score += 0.15;
        }

        for (String token : queryTokens) {
            if (title.contains(token)) {
                score += 0.25;
            }
            if (content.contains(token)) {
                score += 0.12;
            }
            if (tags.contains(token)) {
                score += 0.18;
            }
        }

        if (lowerQuery.contains("支付") && (content.contains("支付") || title.contains("支付"))) {
            score += 0.4;
        }
        if (lowerQuery.contains("回调") && content.contains("回调")) {
            score += 0.3;
        }
        if (lowerQuery.contains("500") && content.contains("500")) {
            score += 0.2;
        }
        if (lowerQuery.contains("订单") && content.contains("订单")) {
            score += 0.2;
        }
        if (score == 0 && (content.contains("回调") || title.contains("回调"))) {
            score = 0.5;
        }
        return score;
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = text.replaceAll("[^0-9a-zA-Z\\u4e00-\\u9fa5/_-]+", " ");
        for (String part : normalized.split("\\s+")) {
            if (part.length() >= 2) {
                tokens.add(part);
            }
        }
        String[] domainTerms = {
                "支付", "回调", "订单", "待支付", "结算", "批处理", "权限", "账号",
                "缓存", "redis", "mq", "消息", "积压", "oom", "oomkilled", "timeout",
                "超时", "慢", "失败", "报错", "500", "hikari", "连接池"
        };
        for (String term : domainTerms) {
            if (text.contains(term.toLowerCase(Locale.ROOT))) {
                tokens.add(term.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    private String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
