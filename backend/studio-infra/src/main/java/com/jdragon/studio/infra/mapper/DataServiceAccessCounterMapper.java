package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.DataServiceAccessCounterEntity;
import com.jdragon.studio.infra.model.DataServiceAccessCounterSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface DataServiceAccessCounterMapper extends BaseMapper<DataServiceAccessCounterEntity> {

    @Insert("insert into data_service_access_counter (" +
            "id, tenant_id, project_id, deleted, created_at, updated_at, service_id, subscription_id, bucket_start, success, cache_enabled, cache_hit, access_count, row_count" +
            ") values (" +
            "#{id}, #{tenantId}, #{projectId}, 0, current_timestamp, current_timestamp, #{serviceId}, #{subscriptionId}, #{bucketStart}, #{success}, #{cacheEnabled}, #{cacheHit}, #{accessCount}, #{rowCount}" +
            ") on duplicate key update " +
            "updated_at = current_timestamp, " +
            "access_count = access_count + values(access_count), " +
            "row_count = row_count + values(row_count)")
    int upsert(DataServiceAccessCounterEntity entity);

    @Update("update data_service_access_counter set " +
            "updated_at = current_timestamp, " +
            "access_count = access_count + #{accessCount}, " +
            "row_count = row_count + #{rowCount} " +
            "where deleted = 0 " +
            "and tenant_id = #{tenantId} " +
            "and project_id = #{projectId} " +
            "and service_id = #{serviceId} " +
            "and subscription_id = #{subscriptionId} " +
            "and bucket_start = #{bucketStart} " +
            "and success = #{success} " +
            "and cache_enabled = #{cacheEnabled} " +
            "and cache_hit = #{cacheHit}")
    int increment(DataServiceAccessCounterEntity entity);

    @Select({"<script>",
            "select",
            "  coalesce(sum(access_count), 0) as access_count,",
            "  coalesce(sum(case when success = 1 then access_count else 0 end), 0) as success_count,",
            "  coalesce(sum(case when success &lt;&gt; 1 then access_count else 0 end), 0) as failure_count,",
            "  coalesce(sum(case when cache_enabled = 1 then access_count else 0 end), 0) as cache_enabled_count,",
            "  coalesce(sum(case when cache_enabled = 1 and cache_hit = 1 then access_count else 0 end), 0) as cache_hit_count,",
            "  coalesce(sum(case when cache_enabled = 1 and cache_hit &lt;&gt; 1 then access_count else 0 end), 0) as cache_miss_count,",
            "  coalesce(sum(case when cache_enabled &lt;&gt; 1 then access_count else 0 end), 0) as cache_disabled_count,",
            "  coalesce(sum(row_count), 0) as row_count",
            "from data_service_access_counter",
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
            "  <if test='cacheHit != null'>and cache_enabled = 1 and cache_hit = #{cacheHit}</if>",
            "</script>"})
    DataServiceAccessCounterSummary selectSummary(@Param("tenantId") String tenantId,
                                                  @Param("projectId") Long projectId,
                                                  @Param("serviceIds") List<Long> serviceIds,
                                                  @Param("subscriptionId") Long subscriptionId,
                                                  @Param("success") Integer success,
                                                  @Param("cacheHit") Integer cacheHit,
                                                  @Param("startTime") LocalDateTime startTime,
                                                  @Param("endTime") LocalDateTime endTime);

    @Select({"<script>",
            "select",
            "  bucket_start as bucket_start,",
            "  coalesce(sum(access_count), 0) as access_count,",
            "  coalesce(sum(case when success = 1 then access_count else 0 end), 0) as success_count,",
            "  coalesce(sum(case when success &lt;&gt; 1 then access_count else 0 end), 0) as failure_count,",
            "  coalesce(sum(case when cache_enabled = 1 then access_count else 0 end), 0) as cache_enabled_count,",
            "  coalesce(sum(case when cache_enabled = 1 and cache_hit = 1 then access_count else 0 end), 0) as cache_hit_count,",
            "  coalesce(sum(case when cache_enabled = 1 and cache_hit &lt;&gt; 1 then access_count else 0 end), 0) as cache_miss_count,",
            "  coalesce(sum(case when cache_enabled &lt;&gt; 1 then access_count else 0 end), 0) as cache_disabled_count,",
            "  coalesce(sum(row_count), 0) as row_count",
            "from data_service_access_counter",
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
            "  <if test='cacheHit != null'>and cache_enabled = 1 and cache_hit = #{cacheHit}</if>",
            "group by bucket_start",
            "order by bucket_start asc",
            "</script>"})
    List<DataServiceAccessCounterSummary> selectBucketSummaries(@Param("tenantId") String tenantId,
                                                                @Param("projectId") Long projectId,
                                                                @Param("serviceIds") List<Long> serviceIds,
                                                                @Param("subscriptionId") Long subscriptionId,
                                                                @Param("success") Integer success,
                                                                @Param("cacheHit") Integer cacheHit,
                                                                @Param("startTime") LocalDateTime startTime,
                                                                @Param("endTime") LocalDateTime endTime);
}
