package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.AlertIncidentEntity;
import com.jdragon.studio.infra.model.AlertProjectSummaryAggregate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AlertIncidentMapper extends BaseMapper<AlertIncidentEntity> {

    @Select({
            "<script>",
            "select project_id,",
            "sum(case when status = 'OPEN' then 1 else 0 end) as open_incident_count,",
            "sum(case when severity = 'CRITICAL' and status in ('OPEN','ACKNOWLEDGED') then 1 else 0 end) as critical_incident_count",
            "from studio_alert_incident",
            "where deleted = 0 and tenant_id = #{tenantId}",
            "and project_id in",
            "<foreach collection='projectIds' item='projectId' open='(' separator=',' close=')'>",
            "#{projectId}",
            "</foreach>",
            "group by project_id",
            "</script>"
    })
    List<AlertProjectSummaryAggregate> selectIncidentCountsByProjectIds(@Param("tenantId") String tenantId,
                                                                         @Param("projectIds") List<Long> projectIds);
}
