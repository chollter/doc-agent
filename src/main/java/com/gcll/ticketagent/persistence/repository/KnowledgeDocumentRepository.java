package com.gcll.ticketagent.persistence.repository;

import com.gcll.ticketagent.persistence.entity.KnowledgeDocumentEntity;

import java.util.List;

public interface KnowledgeDocumentRepository {
    List<KnowledgeDocumentEntity> findAll();

    KnowledgeDocumentEntity save(KnowledgeDocumentEntity document);

    List<KnowledgeDocumentEntity> findRecent(int limit);

    /**
     * SQL 层过滤：按 systemName 或 moduleName 缩小结果集。
     * 替代 findAll() 全表扫描。参数为 null 时不过滤该维度。
     */
    List<KnowledgeDocumentEntity> findBySystemNameOrModuleName(String systemName, String moduleName);
}
