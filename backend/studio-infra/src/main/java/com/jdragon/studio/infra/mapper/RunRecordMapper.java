package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.RunRecordEntity;
import com.jdragon.studio.infra.model.RunMetricBucketAggregate;
import com.jdragon.studio.infra.model.RunMetricTaskAggregate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.SelectProvider;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RunRecordMapper extends BaseMapper<RunRecordEntity> {

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
