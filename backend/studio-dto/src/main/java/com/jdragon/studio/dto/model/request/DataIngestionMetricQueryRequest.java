package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class DataIngestionMetricQueryRequest {
    private Long serviceId;
    private Long subscriptionId;
    private String serviceStatus;
    private Boolean success;
    private Long requestedClusterId;
    private Long actualClusterId;
    private String startTime;
    private String endTime;
    private String granularity;
    private String logFocus;
    private Long minDurationMs;
    private Integer topN;
    private Integer pageNo;
    private Integer pageSize;
}
