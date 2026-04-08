package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.ResourceShareEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ResourceShareMapper extends BaseMapper<ResourceShareEntity> {

    @Select("select id, source_project_id, target_project_id, resource_type, resource_id, shared_by_user_id, enabled, tenant_id, deleted, created_at, updated_at "
            + "from studio_resource_share "
            + "where tenant_id = #{tenantId} and resource_type = #{resourceType} and resource_id = #{resourceId} and target_project_id = #{targetProjectId} "
            + "limit 1")
    ResourceShareEntity selectIncludingDeleted(@Param("tenantId") String tenantId,
                                               @Param("resourceType") String resourceType,
                                               @Param("resourceId") Long resourceId,
                                               @Param("targetProjectId") Long targetProjectId);

    @Update("update studio_resource_share "
            + "set deleted = 0, enabled = #{enabled}, shared_by_user_id = #{sharedByUserId}, source_project_id = #{sourceProjectId}, updated_at = now() "
            + "where id = #{id}")
    int reviveDeletedById(@Param("id") Long id,
                          @Param("enabled") Integer enabled,
                          @Param("sharedByUserId") Long sharedByUserId,
                          @Param("sourceProjectId") Long sourceProjectId);
}
