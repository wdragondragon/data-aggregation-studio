package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.ManagedFileEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

public interface ManagedFileMapper extends BaseMapper<ManagedFileEntity> {

    @Update("update so_pf_managed_file set status = 'DELETE_PENDING', " +
            "next_delete_attempt_at = #{now}, expires_at = #{now}, updated_at = #{now} " +
            "where id = #{id} and tenant_id = #{tenantId} and project_id = #{projectId} " +
            "and status in ('READY','UPLOAD_FAILED','DELETE_PENDING','DELETE_FAILED') " +
            "and not exists (select 1 from so_pf_managed_file_ref r " +
            "where r.file_id = #{id} and r.deleted = 0)")
    int markDeletePendingIfUnreferenced(@Param("id") Long id,
                                        @Param("tenantId") String tenantId,
                                        @Param("projectId") Long projectId,
                                        @Param("now") LocalDateTime now);

    @Update("update so_pf_managed_file set updated_at = updated_at " +
            "where id = #{id} and tenant_id = #{tenantId} and project_id = #{projectId} " +
            "and status = 'READY'")
    int lockReadyForBinding(@Param("id") Long id,
                            @Param("tenantId") String tenantId,
                            @Param("projectId") Long projectId);
}
