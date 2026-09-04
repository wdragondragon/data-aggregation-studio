package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.ManagedFileAuditEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface ManagedFileAuditMapper extends BaseMapper<ManagedFileAuditEntity> {

    @Delete("delete from so_pf_managed_file_audit where created_at < #{cutoff}")
    int hardDeleteBefore(@Param("cutoff") LocalDateTime cutoff);
}
