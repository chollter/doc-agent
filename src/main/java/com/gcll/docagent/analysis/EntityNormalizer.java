package com.gcll.docagent.analysis;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 实体规范化——LLM 抽取与下游确定性计算之间的稳定层。
 * <p>下游所有"纯代码"检查（红旗/时间线/画像）都依赖实体值，抽取格式的抖动
 * 会直接传导成检查结果的抖动。规范化做两件事：
 * TIME_PERIOD 值统一为 yyyy.MM（中文日期在此转换为可解析格式）；
 * 同类型同值的实体去重（LLM 常见的重复抽取）。
 */
@Component
public class EntityNormalizer {

    public ResumeEntities normalize(ResumeEntities entities) {
        if (entities == null || entities.isEmpty()) {
            return entities != null ? entities : new ResumeEntities(List.of());
        }
        List<ResumeEntity> normalized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResumeEntity e : entities.getAll()) {
            if (e == null || e.value() == null) {
                continue;
            }
            ResumeEntity fixed = e.type() == ResumeEntity.EntityType.TIME_PERIOD
                    ? new ResumeEntity(e.type(), ResumeDateParser.normalize(e.value()), e.context(), e.attributes())
                    : e;
            if (seen.add(fixed.type() + "|" + fixed.value().trim().toLowerCase())) {
                normalized.add(fixed);
            }
        }
        return new ResumeEntities(normalized, entities.getProjects());
    }
}
