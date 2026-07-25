package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.RuntimeClusterEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface RuntimeClusterMapper extends BaseMapper<RuntimeClusterEntity> {

    @Select("select count(*) from studio_runtime_cluster where tenant_id=#{tenantId}")
    Long selectPhysicalCountByTenant(@Param("tenantId") String tenantId);

    @Select("select count(*) from studio_runtime_cluster where tenant_id=#{tenantId} and upper(code)=upper(#{code})")
    Long selectPhysicalCountByTenantAndCode(@Param("tenantId") String tenantId,
                                            @Param("code") String code);
}
