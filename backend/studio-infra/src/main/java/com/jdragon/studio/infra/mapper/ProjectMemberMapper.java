package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.ProjectMemberEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProjectMemberMapper extends BaseMapper<ProjectMemberEntity> {
    @Select("""
            select id, tenant_id, deleted, created_at, updated_at, project_id, user_id, role_code, status
            from studio_project_member
            where project_id = #{projectId}
              and user_id = #{userId}
            limit 1
            """)
    ProjectMemberEntity selectByProjectAndUserIncludingDeleted(@Param("projectId") Long projectId,
                                                               @Param("userId") Long userId);

    @Update("""
            update studio_project_member
            set role_code = #{roleCode},
                status = #{status},
                deleted = 0,
                updated_at = current_timestamp
            where id = #{id}
              and project_id = #{projectId}
              and user_id = #{userId}
            """)
    int restoreById(@Param("id") Long id,
                    @Param("projectId") Long projectId,
                    @Param("userId") Long userId,
                    @Param("roleCode") String roleCode,
                    @Param("status") String status);
}
