package com.jdragon.studio.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jdragon.studio.infra.entity.ProtocolConversionAccessCounterEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProtocolConversionAccessCounterMapper extends BaseMapper<ProtocolConversionAccessCounterEntity> {

    @Insert("insert into protocol_conversion_access_counter (" +
            "id, tenant_id, project_id, deleted, created_at, updated_at, service_id, subscription_id, bucket_start, success, access_count, received_count, success_count, failed_count" +
            ") values (" +
            "#{id}, #{tenantId}, #{projectId}, 0, current_timestamp, current_timestamp, #{serviceId}, #{subscriptionId}, #{bucketStart}, #{success}, #{accessCount}, #{receivedCount}, #{successCount}, #{failedCount}" +
            ") on duplicate key update " +
            "updated_at = current_timestamp, " +
            "access_count = access_count + values(access_count), " +
            "received_count = received_count + values(received_count), " +
            "success_count = success_count + values(success_count), " +
            "failed_count = failed_count + values(failed_count)")
    int upsert(ProtocolConversionAccessCounterEntity entity);

    @Update("update protocol_conversion_access_counter set " +
            "updated_at = current_timestamp, " +
            "access_count = access_count + #{accessCount}, " +
            "received_count = received_count + #{receivedCount}, " +
            "success_count = success_count + #{successCount}, " +
            "failed_count = failed_count + #{failedCount} " +
            "where deleted = 0 " +
            "and tenant_id = #{tenantId} " +
            "and project_id = #{projectId} " +
            "and service_id = #{serviceId} " +
            "and subscription_id = #{subscriptionId} " +
            "and bucket_start = #{bucketStart} " +
            "and success = #{success}")
    int increment(ProtocolConversionAccessCounterEntity entity);
}
