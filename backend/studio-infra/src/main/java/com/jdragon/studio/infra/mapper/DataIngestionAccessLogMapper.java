package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.DataIngestionAccessLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DataIngestionAccessLogMapper extends BaseMapper<DataIngestionAccessLogEntity> {
}
