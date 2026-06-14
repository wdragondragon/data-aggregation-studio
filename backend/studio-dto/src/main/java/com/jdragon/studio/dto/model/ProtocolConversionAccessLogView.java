package com.jdragon.studio.dto.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProtocolConversionAccessLogView extends BaseDefinition {
    private Long serviceId;
    private String serviceCode;
    private String serviceName;
    private String serviceStatus;
    private Long subscriptionId;
    private String subscriptionName;
    private String requestId;
    private String requestMethod;
    private String sourceProtocol;
    private String targetProtocol;
    private LocalDateTime occurredAt;
    private Long durationMs;
    private Boolean success;
    private Integer httpStatus;
    private Integer targetHttpStatus;
    private String errorCode;
    private String errorMessage;
    private String systemLog;
    private String clientIp;
    private String userAgent;
    private Long receivedCount;
    private Long successCount;
    private Long failedCount;
    private String logStorageType;
    private String logObjectBucket;
    private String logObjectKey;
    private Long logSizeBytes;
    private String logCharset;
    private String logArchiveStatus;
    private String logArchiveError;
}
