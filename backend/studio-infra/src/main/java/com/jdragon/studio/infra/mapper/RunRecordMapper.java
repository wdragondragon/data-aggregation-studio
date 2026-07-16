package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.model.RunMetricBucketAggregate;
import com.jdragon.studio.infra.model.RunMetricTaskAggregate;
import com.jdragon.studio.infra.model.WorkflowRunOutcome;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RunRecordMapper extends BaseMapper<RunRecordEntity> {

    @Select({
            "select rr.workflow_run_id as workflow_run_id,",
            "max(coalesce(rr.ended_at, rr.updated_at, rr.created_at)) as observed_at,",
            "case when sum(case when upper(rr.status) in ('FAILED','ERROR') then 1 else 0 end) > 0 then 1 else 0 end as failed",
            "from run_record rr",
            "where rr.deleted = 0 and rr.tenant_id = #{tenantId} and rr.project_id = #{projectId}",
            "and rr.workflow_definition_id = #{workflowDefinitionId} and rr.workflow_run_id is not null",
            "and upper(rr.status) in ('SUCCESS','FAILED','ERROR')",
            "and not exists (select 1 from dispatch_task dt where dt.deleted = 0",
            "and dt.tenant_id = rr.tenant_id and dt.project_id = rr.project_id",
            "and dt.workflow_run_id = rr.workflow_run_id and upper(dt.status) in ('QUEUED','RUNNING'))",
            "and not exists (select 1 from run_record active_rr where active_rr.deleted = 0",
            "and active_rr.tenant_id = rr.tenant_id and active_rr.project_id = rr.project_id",
            "and active_rr.workflow_run_id = rr.workflow_run_id and upper(active_rr.status) = 'RUNNING')",
            "group by rr.workflow_run_id",
            "order by max(coalesce(rr.ended_at, rr.updated_at, rr.created_at)) desc, rr.workflow_run_id desc",
            "limit #{limit}"
    })
    List<WorkflowRunOutcome> selectRecentWorkflowRunOutcomes(@Param("tenantId") String tenantId,
                                                              @Param("projectId") Long projectId,
                                                              @Param("workflowDefinitionId") Long workflowDefinitionId,
                                                              @Param("limit") int limit);

    @SelectProvider(type = RunMetricSqlProvider.class, method = "selectDashboardBuckets")
    List<RunMetricBucketAggregate> selectRunMetricDashboardBuckets(@Param("tenantId") String tenantId,
                                                                   @Param("projectId") Long projectId,
                                                                   @Param("taskIds") List<Long> taskIds,
                                                                   @Param("startTime") LocalDateTime startTime,
                                                                   @Param("endTime") LocalDateTime endTime);

    @SelectProvider(type = RunMetricSqlProvider.class, method = "selectDashboardTaskAggregates")
    List<RunMetricTaskAggregate> selectRunMetricDashboardTaskAggregates(@Param("tenantId") String tenantId,
                                                                        @Param("projectId") Long projectId,
                                                                        @Param("taskIds") List<Long> taskIds,
                                                                        @Param("startTime") LocalDateTime startTime,
                                                                        @Param("endTime") LocalDateTime endTime);
}
