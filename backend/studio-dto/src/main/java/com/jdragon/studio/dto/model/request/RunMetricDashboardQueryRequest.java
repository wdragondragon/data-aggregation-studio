package com.jdragon.studio.dto.model.request;

import lombok.Data;

@Data
public class RunMetricDashboardQueryRequest {
    private Long datasourceId;
    private Long sourceModelId;
    private Long targetModelId;
    private String startTime;
    private String endTime;
    private String granularity;
    private Integer topN;
}
