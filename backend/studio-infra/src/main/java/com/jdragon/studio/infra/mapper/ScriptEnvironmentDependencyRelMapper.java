package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.ScriptEnvironmentDependencyRelEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ScriptEnvironmentDependencyRelMapper extends BaseMapper<ScriptEnvironmentDependencyRelEntity> {

    /**
     * Reactivate an existing (logically deleted) relation row for the same environment/dependency,
     * or return 0 if no row exists. Returns 1 when a row was reactivated.
     */
    @Update("update so_pf_env_dep_rel set deleted = 0, sort_order = #{sortOrder} " +
            "where tenant_id = #{tenantId} and environment_id = #{environmentId} " +
            "and dependency_id = #{dependencyId} and deleted = 1")
    int reactivateOrUpdate(@Param("tenantId") String tenantId,
                           @Param("environmentId") Long environmentId,
                           @Param("dependencyId") Long dependencyId,
                           @Param("sortOrder") Integer sortOrder);

    /**
     * Soft-delete every active relation row for an environment so the caller can rebuild
     * the dependency set in one pass.
     */
    @Update("update so_pf_env_dep_rel set deleted = 1 " +
            "where tenant_id = #{tenantId} and environment_id = #{environmentId} and deleted = 0")
    int softDeleteActiveByEnvironment(@Param("tenantId") String tenantId,
                                      @Param("environmentId") Long environmentId);
}
