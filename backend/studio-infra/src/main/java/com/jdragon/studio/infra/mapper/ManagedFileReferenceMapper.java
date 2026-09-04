package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.ManagedFileReferenceEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface ManagedFileReferenceMapper extends BaseMapper<ManagedFileReferenceEntity> {

    @Delete("delete from so_pf_managed_file_ref " +
            "where tenant_id = #{tenantId} and project_id = #{projectId} " +
            "and owner_type = #{ownerType} and owner_id = #{ownerId}")
    int hardDeleteByOwner(@Param("tenantId") String tenantId,
                          @Param("projectId") Long projectId,
                          @Param("ownerType") String ownerType,
                          @Param("ownerId") Long ownerId);
}
