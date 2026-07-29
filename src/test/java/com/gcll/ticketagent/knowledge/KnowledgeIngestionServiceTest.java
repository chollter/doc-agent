package com.gcll.ticketagent.knowledge;

import com.gcll.ticketagent.persistence.entity.KnowledgeDocumentEntity;
import com.gcll.ticketagent.persistence.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeIngestionServiceTest {

    @Test
    void importedDocumentCanBeSearchedByRelationalRetriever() {
        InMemoryKnowledgeDocumentRepository repository = new InMemoryKnowledgeDocumentRepository();
        @SuppressWarnings("unchecked")
        ObjectProvider<VectorStore> vectorStoreProvider = mock(ObjectProvider.class);
        when(vectorStoreProvider.getIfAvailable()).thenReturn(null);

        KnowledgeIngestionService ingestionService = new KnowledgeIngestionService(repository, vectorStoreProvider);
        RelationalKnowledgeSearchService searchService = new RelationalKnowledgeSearchService(repository);

        String document = """
                # 库存出库事件积压处理

                现象：库存系统 outbound-event-consumer 消费延迟升高，订单已支付但出库单迟迟未生成。
                排查：先检查 consumer lag、死信队列、库存扣减幂等表和 outbox 重试任务。
                处理：恢复消费者后触发补偿任务，确认出库单与订单状态一致。
                """;

        var response = ingestionService.importText(
                "inventory-runbook.md",
                document.getBytes(StandardCharsets.UTF_8),
                "库存出库事件积压 Runbook",
                "库存系统",
                "出库事件",
                "库存,出库,MQ,outbox"
        );

        List<KnowledgeHit> hits = searchService.search(
                "订单已支付但是出库单没生成，怀疑 outbound consumer lag",
                "库存系统",
                "出库事件",
                "INCIDENT"
        );

        assertThat(response.chunks()).isEqualTo(1);
        assertThat(response.vectorIndexed()).isFalse();
        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().title()).contains("库存出库事件积压");
        assertThat(hits.getFirst().resolution()).contains("consumer lag");
    }

    private static class InMemoryKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

        private final List<KnowledgeDocumentEntity> documents = new ArrayList<>();

        @Override
        public List<KnowledgeDocumentEntity> findAll() {
            return List.copyOf(documents);
        }

        @Override
        public KnowledgeDocumentEntity save(KnowledgeDocumentEntity document) {
            documents.removeIf(existing -> existing.getId().equals(document.getId()));
            documents.add(document);
            return document;
        }

        @Override
        public List<KnowledgeDocumentEntity> findRecent(int limit) {
            return documents.stream()
                    .sorted(Comparator.comparing(KnowledgeDocumentEntity::getUpdatedAt,
                            Comparator.nullsLast(LocalDateTime::compareTo)).reversed())
                    .limit(limit)
                    .toList();
        }

        @Override
        public List<KnowledgeDocumentEntity> findBySystemNameOrModuleName(String systemName, String moduleName) {
            return documents.stream()
                    .filter(d -> systemName == null || systemName.equals(d.getSystemName())
                            || moduleName == null || moduleName.equals(d.getModuleName()))
                    .toList();
        }
    }
}
