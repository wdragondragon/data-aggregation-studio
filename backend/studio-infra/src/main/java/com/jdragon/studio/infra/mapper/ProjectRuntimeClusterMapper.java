package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.ProjectRuntimeClusterEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ProjectRuntimeClusterMapper extends BaseMapper<ProjectRuntimeClusterEntity> {

    @Select("select count(*) from studio_project_runtime_cluster where tenant_id=#{tenantId} and project_id=#{projectId}")
    Long selectPhysicalCountByProject(@Param("tenantId") String tenantId,
                                      @Param("projectId") Long projectId);
}
