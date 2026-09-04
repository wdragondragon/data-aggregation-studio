package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.ManagedFileLeaseEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

public interface ManagedFileLeaseMapper extends BaseMapper<ManagedFileLeaseEntity> {

    @Delete("delete from so_pf_managed_file_lease " +
            "where (released_at is not null and released_at < #{cutoff}) " +
            "or (released_at is null and expires_at < #{cutoff})")
    int hardDeleteExpired(@Param("cutoff") LocalDateTime cutoff);
}
