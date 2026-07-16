package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.AlertRuleEntity;
import com.jdragon.studio.infra.model.AlertProjectSummaryAggregate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AlertRuleMapper extends BaseMapper<AlertRuleEntity> {

    @Select({
            "<script>",
            "select project_id, count(*) as enabled_rule_count from studio_alert_rule",
            "where deleted = 0 and tenant_id = #{tenantId} and enabled = 1",
            "and project_id in",
            "<foreach collection='projectIds' item='projectId' open='(' separator=',' close=')'>",
            "#{projectId}",
            "</foreach>",
            "group by project_id",
            "</script>"
    })
    List<AlertProjectSummaryAggregate> selectEnabledCountsByProjectIds(@Param("tenantId") String tenantId,
                                                                        @Param("projectIds") List<Long> projectIds);
}
