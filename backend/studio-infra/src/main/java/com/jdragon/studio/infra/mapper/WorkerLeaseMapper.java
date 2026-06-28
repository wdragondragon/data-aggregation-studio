package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.WorkerLeaseEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkerLeaseMapper extends BaseMapper<WorkerLeaseEntity> {

    @Select({"<script>",
            "select count(*) from (",
            "  select worker_group_code from (",
            "    select coalesce(nullif(worker_group_code, ''), worker_code) as worker_group_code, max(last_heartbeat_at) as latest_heartbeat_at",
            "    from worker_lease",
            "    where tenant_id = #{tenantId} and deleted = 0",
            "      and coalesce(nullif(worker_group_code, ''), worker_code) is not null",
            "      and coalesce(nullif(worker_group_code, ''), worker_code) &lt;&gt; ''",
            "      and (last_heartbeat_at &gt;= #{recentThreshold}",
            "        or (upper(status) = 'ONLINE' and lease_expires_at &gt; #{now}))",
            "    group by coalesce(nullif(worker_group_code, ''), worker_code)",
            "    union all",
            "    select coalesce(nullif(worker_group_code, ''), worker_code) as worker_group_code, null as latest_heartbeat_at",
            "    from studio_project_worker_binding",
            "    where tenant_id = #{tenantId} and project_id = #{projectId} and deleted = 0",
            "      and coalesce(nullif(worker_group_code, ''), worker_code) is not null",
            "      and coalesce(nullif(worker_group_code, ''), worker_code) &lt;&gt; ''",
            "  ) visible_groups",
            "  group by worker_group_code",
            ") counted_groups",
            "</script>"})
    long countVisibleWorkerGroups(@Param("tenantId") String tenantId,
                                  @Param("projectId") Long projectId,
                                  @Param("recentThreshold") LocalDateTime recentThreshold,
                                  @Param("now") LocalDateTime now);

    @Select({"<script>",
            "select worker_group_code from (",
            "  select worker_group_code, max(latest_heartbeat_at) as latest_heartbeat_at from (",
            "    select coalesce(nullif(worker_group_code, ''), worker_code) as worker_group_code, max(last_heartbeat_at) as latest_heartbeat_at",
            "    from worker_lease",
            "    where tenant_id = #{tenantId} and deleted = 0",
            "      and coalesce(nullif(worker_group_code, ''), worker_code) is not null",
            "      and coalesce(nullif(worker_group_code, ''), worker_code) &lt;&gt; ''",
            "      and (last_heartbeat_at &gt;= #{recentThreshold}",
            "        or (upper(status) = 'ONLINE' and lease_expires_at &gt; #{now}))",
            "    group by coalesce(nullif(worker_group_code, ''), worker_code)",
            "    union all",
            "    select coalesce(nullif(worker_group_code, ''), worker_code) as worker_group_code, null as latest_heartbeat_at",
            "    from studio_project_worker_binding",
            "    where tenant_id = #{tenantId} and project_id = #{projectId} and deleted = 0",
            "      and coalesce(nullif(worker_group_code, ''), worker_code) is not null",
            "      and coalesce(nullif(worker_group_code, ''), worker_code) &lt;&gt; ''",
            "  ) visible_groups",
            "  group by worker_group_code",
            ") grouped_worker_groups",
            "order by latest_heartbeat_at desc, worker_group_code asc",
            "limit #{limit} offset #{offset}",
            "</script>"})
    List<String> selectVisibleWorkerGroupPage(@Param("tenantId") String tenantId,
                                              @Param("projectId") Long projectId,
                                              @Param("recentThreshold") LocalDateTime recentThreshold,
                                              @Param("now") LocalDateTime now,
                                              @Param("limit") int limit,
                                              @Param("offset") int offset);

    @Select({"<script>",
            "select worker_group_code, worker_code, worker_kind, instance_id, host_name, pod_name, node_name, status, last_heartbeat_at, lease_expires_at, tenant_id, deleted, created_at, updated_at",
            "from worker_lease",
            "where tenant_id = #{tenantId} and deleted = 0",
            "  and coalesce(nullif(worker_group_code, ''), worker_code) in",
            "  <foreach collection='workerGroupCodes' item='workerGroupCode' open='(' separator=',' close=')'>#{workerGroupCode}</foreach>",
            "  and (last_heartbeat_at &gt;= #{recentThreshold}",
            "    or (upper(status) = 'ONLINE' and lease_expires_at &gt; #{now}))",
            "order by last_heartbeat_at desc, worker_group_code asc, worker_code asc",
            "</script>"})
    List<WorkerLeaseEntity> selectVisibleLeasesForGroups(@Param("tenantId") String tenantId,
                                                         @Param("recentThreshold") LocalDateTime recentThreshold,
                                                         @Param("now") LocalDateTime now,
                                                         @Param("workerGroupCodes") List<String> workerGroupCodes);
}
