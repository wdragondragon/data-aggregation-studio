package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.DataServiceAccessLogEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface DataServiceAccessLogMapper extends BaseMapper<DataServiceAccessLogEntity> {

    @Delete("delete from data_service_access_log where occurred_at < #{before}")
    int purgeBefore(@Param("before") LocalDateTime before);
}
