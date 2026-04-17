package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_service_access_counter")
public class DataServiceAccessCounterEntity extends BaseProjectTenantEntity {
    private Long serviceId;
    private Long subscriptionId;
    private LocalDateTime bucketStart;
    private Integer success;
    private Integer cacheEnabled;
    private Integer cacheHit;
    private Long accessCount;
    private Long rowCount;
}
