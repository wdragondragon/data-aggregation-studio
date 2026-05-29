package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.DataIngestionAccessCounterEntity;
import com.jdragon.studio.infra.model.DataIngestionAccessCounterSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DataIngestionAccessCounterMapper extends BaseMapper<DataIngestionAccessCounterEntity> {

    @Insert("insert into data_ingestion_access_counter (" +
            "id, tenant_id, project_id, deleted, created_at, updated_at, service_id, subscription_id, bucket_start, success, access_count, received_count, success_count, failed_count" +
            ") values (" +
            "#{id}, #{tenantId}, #{projectId}, 0, current_timestamp, current_timestamp, #{serviceId}, #{subscriptionId}, #{bucketStart}, #{success}, #{accessCount}, #{receivedCount}, #{successCount}, #{failedCount}" +
            ") on duplicate key update " +
            "updated_at = current_timestamp, " +
            "access_count = access_count + values(access_count), " +
            "received_count = received_count + values(received_count), " +
            "success_count = success_count + values(success_count), " +
            "failed_count = failed_count + values(failed_count)")
    int upsert(DataIngestionAccessCounterEntity entity);

    @Select({"<script>",
            "select",
            "  coalesce(sum(access_count), 0) as access_count,",
            "  coalesce(sum(case when success = 1 then access_count else 0 end), 0) as success_count,",
            "  coalesce(sum(case when success &lt;&gt; 1 then access_count else 0 end), 0) as failure_count,",
            "  coalesce(sum(received_count), 0) as received_count,",
            "  coalesce(sum(success_count), 0) as written_count,",
            "  coalesce(sum(failed_count), 0) as failed_count",
            "from data_ingestion_access_counter",
            "where deleted = 0",
            "  and tenant_id = #{tenantId}",
            "  and project_id = #{projectId}",
            "  and bucket_start &gt;= #{startTime}",
            "  and bucket_start &lt;= #{endTime}",
            "  <if test='serviceIds != null and serviceIds.size() &gt; 0'>",
            "    and service_id in",
            "    <foreach collection='serviceIds' item='serviceId' open='(' separator=',' close=')'>#{serviceId}</foreach>",
            "  </if>",
            "  <if test='subscriptionId != null'>and subscription_id = #{subscriptionId}</if>",
            "  <if test='success != null'>and success = #{success}</if>",
            "</script>"})
    DataIngestionAccessCounterSummary selectSummary(@Param("tenantId") String tenantId,
                                                    @Param("projectId") Long projectId,
                                                    @Param("serviceIds") List<Long> serviceIds,
                                                    @Param("subscriptionId") Long subscriptionId,
                                                    @Param("success") Integer success,
                                                    @Param("startTime") LocalDateTime startTime,
                                                    @Param("endTime") LocalDateTime endTime);

    @Select({"<script>",
            "select",
            "  bucket_start as bucket_start,",
            "  coalesce(sum(access_count), 0) as access_count,",
            "  coalesce(sum(case when success = 1 then access_count else 0 end), 0) as success_count,",
            "  coalesce(sum(case when success &lt;&gt; 1 then access_count else 0 end), 0) as failure_count,",
            "  coalesce(sum(received_count), 0) as received_count,",
            "  coalesce(sum(success_count), 0) as written_count,",
            "  coalesce(sum(failed_count), 0) as failed_count",
            "from data_ingestion_access_counter",
            "where deleted = 0",
            "  and tenant_id = #{tenantId}",
            "  and project_id = #{projectId}",
            "  and bucket_start &gt;= #{startTime}",
            "  and bucket_start &lt;= #{endTime}",
            "  <if test='serviceIds != null and serviceIds.size() &gt; 0'>",
            "    and service_id in",
            "    <foreach collection='serviceIds' item='serviceId' open='(' separator=',' close=')'>#{serviceId}</foreach>",
            "  </if>",
            "  <if test='subscriptionId != null'>and subscription_id = #{subscriptionId}</if>",
            "  <if test='success != null'>and success = #{success}</if>",
            "group by bucket_start",
            "order by bucket_start asc",
            "</script>"})
    List<DataIngestionAccessCounterSummary> selectBucketSummaries(@Param("tenantId") String tenantId,
                                                                  @Param("projectId") Long projectId,
                                                                  @Param("serviceIds") List<Long> serviceIds,
                                                                  @Param("subscriptionId") Long subscriptionId,
                                                                  @Param("success") Integer success,
                                                                  @Param("startTime") LocalDateTime startTime,
                                                                  @Param("endTime") LocalDateTime endTime);
}
