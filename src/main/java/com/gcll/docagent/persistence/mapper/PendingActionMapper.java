package com.gcll.docagent.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gcll.docagent.persistence.entity.PendingActionEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PendingActionMapper extends BaseMapper<PendingActionEntity> {
}
