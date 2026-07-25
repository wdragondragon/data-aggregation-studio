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
    private Long requestedClusterId;
    private Long actualClusterId;
    private String serviceCodeSnapshot;
    private String serviceNameSnapshot;
    private String serviceStatusSnapshot;
    private Long subscriptionId;
    private String subscriptionNameSnapshot;
    private String requestId;
    private String requestMethod;
    private LocalDateTime occurredAt;
    private Long durationMs;
    private Integer success;
    private Integer httpStatus;
    private String errorCode;
    private String errorMessage;
    private String systemLog;
    private String clientIp;
    private String userAgent;
    private Integer cacheEnabled;
    private Integer cacheHit;
    private Long rowCount;
    private String logStorageType;
    private String logObjectBucket;
    private String logObjectKey;
    private Long logSizeBytes;
    private String logCharset;
    private String logArchiveStatus;
    private String logArchiveError;
}
