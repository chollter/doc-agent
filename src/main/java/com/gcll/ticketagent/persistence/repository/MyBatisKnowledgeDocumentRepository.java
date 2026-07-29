package com.gcll.ticketagent.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gcll.ticketagent.persistence.entity.KnowledgeDocumentEntity;
import com.gcll.ticketagent.persistence.mapper.KnowledgeDocumentMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class MyBatisKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;

    public MyBatisKnowledgeDocumentRepository(KnowledgeDocumentMapper knowledgeDocumentMapper) {
        this.knowledgeDocumentMapper = knowledgeDocumentMapper;
    }

    @Override
    public List<KnowledgeDocumentEntity> findAll() {
        return knowledgeDocumentMapper.selectList(null);
    }

    @Override
    public KnowledgeDocumentEntity save(KnowledgeDocumentEntity document) {
        int updated = knowledgeDocumentMapper.updateById(document);
        if (updated == 0) {
            knowledgeDocumentMapper.insert(document);
        }
        return document;
    }

    @Override
    public List<KnowledgeDocumentEntity> findRecent(int limit) {
        return knowledgeDocumentMapper.selectList(new LambdaQueryWrapper<KnowledgeDocumentEntity>()
                .orderByDesc(KnowledgeDocumentEntity::getUpdatedAt)
                .last("limit " + Math.max(1, limit)));
    }

    @Override
    public List<KnowledgeDocumentEntity> findBySystemNameOrModuleName(String systemName, String moduleName) {
        LambdaQueryWrapper<KnowledgeDocumentEntity> wrapper = new LambdaQueryWrapper<>();
        // 构建 OR 条件：systemName 匹配 OR moduleName 匹配
        // 任一参数为 null 时忽略该维度
        wrapper.and(w -> {
            boolean hasSystem = systemName != null && !systemName.isBlank();
            boolean hasModule = moduleName != null && !moduleName.isBlank();
            if (hasSystem && hasModule) {
                w.eq(KnowledgeDocumentEntity::getSystemName, systemName)
                 .or()
                 .eq(KnowledgeDocumentEntity::getModuleName, moduleName);
            } else if (hasSystem) {
                w.eq(KnowledgeDocumentEntity::getSystemName, systemName);
            } else if (hasModule) {
                w.eq(KnowledgeDocumentEntity::getModuleName, moduleName);
            } else {
                // 都没传，返回全表（降级行为，不应常走）
                w.isNotNull(KnowledgeDocumentEntity::getId);
            }
        });
        return knowledgeDocumentMapper.selectList(wrapper);
    }
}
