package com.jdragon.studio.dto.model.request;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class QualityAssetQueryRequest {
    private Long datasourceId;
    private Long modelId;
    private String ruleDimension;
    private String granularity;
    private String taskStatus;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Boolean onlyProblemAssets;
    private Boolean onlyLowCoverageAssets;
}
