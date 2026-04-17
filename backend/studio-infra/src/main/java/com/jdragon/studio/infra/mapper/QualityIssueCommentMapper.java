package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.QualityIssueCommentEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QualityIssueCommentMapper extends BaseMapper<QualityIssueCommentEntity> {
}
