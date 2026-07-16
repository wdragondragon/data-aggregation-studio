package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.AlertEventEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface AlertEventMapper extends BaseMapper<AlertEventEntity> {

    @Delete("delete from studio_alert_event where observed_at < #{cutoff}")
    int hardDeleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
