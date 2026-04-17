package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class DataServiceMetricQueryRequest {
    private Long serviceId;
    private Long subscriptionId;
    private String serviceStatus;
    private Boolean success;
    private Boolean cacheHit;
    private String startTime;
    private String endTime;
    private String granularity;
    private String logFocus;
    private Long minDurationMs;
    private Integer topN;
    private Integer pageNo;
    private Integer pageSize;
}
