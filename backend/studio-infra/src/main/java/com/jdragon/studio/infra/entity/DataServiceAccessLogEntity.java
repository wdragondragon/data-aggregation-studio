package com.jdragon.studio.infra.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("data_service_access_log")
public class DataServiceAccessLogEntity extends BaseProjectTenantEntity {
    private Long serviceId;
    private String serviceCodeSnapshot;
    private String serviceNameSnapshot;
    private String serviceStatusSnapshot;
    private Long subscriptionId;
    private String subscriptionNameSnapshot;
    private String requestMethod;
    private LocalDateTime occurredAt;
    private Long durationMs;
    private Integer success;
    private Integer httpStatus;
    private String errorCode;
    private String errorMessage;
    private String clientIp;
    private String userAgent;
    private Integer cacheEnabled;
    private Integer cacheHit;
    private Long rowCount;
}
