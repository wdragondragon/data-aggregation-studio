package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.ProjectWorkerBindingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectWorkerBindingMapper extends BaseMapper<ProjectWorkerBindingEntity> {

    @Select("select id, tenant_id, project_id, deleted, created_at, updated_at, worker_group_code, worker_code, enabled "
            + "from studio_project_worker_binding "
            + "where tenant_id = #{tenantId} and project_id = #{projectId} "
            + "and (worker_group_code = #{workerGroupCode} or worker_code = #{workerGroupCode}) "
            + "order by deleted asc, updated_at desc "
            + "limit 1")
    ProjectWorkerBindingEntity selectIncludingDeleted(@Param("tenantId") String tenantId,
                                                      @Param("projectId") Long projectId,
                                                      @Param("workerGroupCode") String workerGroupCode);

    @Update("update studio_project_worker_binding "
            + "set deleted = 0, worker_group_code = #{workerGroupCode}, worker_code = #{workerCode}, enabled = #{enabled}, updated_at = current_timestamp "
            + "where id = #{id}")
    int reviveDeletedById(@Param("id") Long id,
                          @Param("workerGroupCode") String workerGroupCode,
                          @Param("workerCode") String workerCode,
                          @Param("enabled") Integer enabled);
}
