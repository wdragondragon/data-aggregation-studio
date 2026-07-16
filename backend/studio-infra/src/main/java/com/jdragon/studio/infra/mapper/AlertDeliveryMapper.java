package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.AlertDeliveryEntity;
import com.jdragon.studio.infra.model.AlertProjectSummaryAggregate;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AlertDeliveryMapper extends BaseMapper<AlertDeliveryEntity> {

    @Delete("delete from studio_alert_delivery " +
            "where status in ('SUCCEEDED','DEAD','SKIPPED') and updated_at < #{cutoff}")
    int hardDeleteTerminalBefore(@Param("cutoff") LocalDateTime cutoff);

    @Select({
            "<script>",
            "select project_id, count(*) as failed_delivery_count from studio_alert_delivery",
            "where deleted = 0 and tenant_id = #{tenantId} and status in ('RETRY','DEAD')",
            "and project_id in",
            "<foreach collection='projectIds' item='projectId' open='(' separator=',' close=')'>",
            "#{projectId}",
            "</foreach>",
            "group by project_id",
            "</script>"
    })
    List<AlertProjectSummaryAggregate> selectFailedCountsByProjectIds(@Param("tenantId") String tenantId,
                                                                       @Param("projectIds") List<Long> projectIds);
}
