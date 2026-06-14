package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("protocol_conversion_access_counter")
public class ProtocolConversionAccessCounterEntity extends BaseProjectTenantEntity {
    private Long serviceId;
    private Long subscriptionId;
    private LocalDateTime bucketStart;
    private Integer success;
    private Long accessCount;
    private Long receivedCount;
    private Long successCount;
    private Long failedCount;
}
