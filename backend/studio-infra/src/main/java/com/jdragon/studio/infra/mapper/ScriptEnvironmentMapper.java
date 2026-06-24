package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.ScriptEnvironmentEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ScriptEnvironmentMapper extends BaseMapper<ScriptEnvironmentEntity> {
}
