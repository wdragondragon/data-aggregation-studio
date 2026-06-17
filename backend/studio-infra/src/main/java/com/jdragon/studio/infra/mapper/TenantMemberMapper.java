package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.TenantMemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TenantMemberMapper extends BaseMapper<TenantMemberEntity> {
    @Select("""
            select id, tenant_id, deleted, created_at, updated_at, user_id, role_code, status
            from studio_tenant_member
            where tenant_id = #{tenantId}
              and user_id = #{userId}
            limit 1
            """)
    TenantMemberEntity selectByTenantAndUserIncludingDeleted(@Param("tenantId") String tenantId,
                                                             @Param("userId") Long userId);

    @Update("""
            update studio_tenant_member
            set role_code = #{roleCode},
                status = #{status},
                deleted = 0,
                updated_at = current_timestamp
            where id = #{id}
              and tenant_id = #{tenantId}
              and user_id = #{userId}
            """)
    int restoreById(@Param("id") Long id,
                    @Param("tenantId") String tenantId,
                    @Param("userId") Long userId,
                    @Param("roleCode") String roleCode,
                    @Param("status") String status);
}
