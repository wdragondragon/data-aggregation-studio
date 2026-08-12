package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.EnvironmentDependencyEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EnvironmentDependencyMapper extends BaseMapper<EnvironmentDependencyEntity> {

    /**
     * Finds rows by tenant + name + version including logically deleted ones, because the
     * logical-delete marker shares the {@code uk_so_pf_env_dep_name_ver} unique key. Re-uploading
     * a name/version that was previously deleted must clear the tombstone row before inserting.
     */
    @Select({
            "<script>",
            "select * from so_pf_env_dep",
            "where tenant_id = #{tenantId} and name = #{name}",
            "<if test='version != null'>and version = #{version}</if>",
            "<if test='version == null'>and version is null</if>",
            "order by id desc limit 1",
            "</script>"
    })
    EnvironmentDependencyEntity selectByNameVersionIncludingDeleted(@Param("tenantId") String tenantId,
                                                                    @Param("name") String name,
                                                                    @Param("version") String version);

    /**
     * Physically removes a row that is only occupying the unique key after logical deletion,
     * so a fresh insert for the same name/version can succeed.
     */
    @Delete("delete from so_pf_env_dep where id = #{id}")
    int physicallyDeleteById(@Param("id") Long id);
}
